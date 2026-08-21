#!/usr/bin/env bash
#
# THE BOUNDARY HARNESS. Two or three application containers and one database container, with
# the container limits as the variable.
#
# WHY THIS IS NOT A k6 SCENARIO AND DOES NOT GO THROUGH run.sh.
#
#   `load/recommendations.js` measures latency under concurrency against ONE process with no
#   memory limit. Every number in R2, R4, R16 and R18 was taken that way, and ADR-004's audit
#   ends by naming what that leaves unanswered. The four traps this harness exists for are not
#   latency questions at all:
#
#     what heap a JVM gets when the container is limited        -- R23 section 3.1
#     what `pool size x instance count` does to max_connections -- R24 section 3.1
#     what /actuator/health says while the database is gone     -- R24 section 3.2
#     what a deployment does to a request in flight             -- R24 section 3.3
#
#   None of them is a percentile, so none of them goes through `run.sh` -- that wrapper exists
#   to refuse a run whose steady-state verdict says DO NOT PUBLISH, and a run that measures an
#   exit code has no measurement window to be steady over. Putting these behind it would make
#   the wrapper mean two different things.
#
# WHAT THE APPLICATION CONTAINER IS, AND WHY IT IS NOT AN IMAGE THIS REPOSITORY BUILDS.
#
#   `ubuntu:24.04` with the toolchain JDK and the boot jar bind-mounted in. A Dockerfile would
#   pin a base image whose JVM is NOT the one in measurement-discipline.md's environment block
#   -- `eclipse-temurin` has no `21.0.12` tag on Docker Hub (queried 2026-08-21; the list stops
#   at `21.0.11_10`) -- and rule 3 is exactly about comparing across that. Binding
#   `$PROXIMA_JDK` puts the SAME JVM build inside the container that every other number here
#   was taken with, and leaves the base image with no job except holding a cgroup.
#
#   IT REQUIRES THE DOCKER DAEMON TO SHARE A FILESYSTEM NAMESPACE WITH THIS SHELL. It does
#   here: the engine is native inside WSL2. On Docker Desktop the binds resolve against a
#   different root and nothing starts.
#
# Usage:
#   ./load/ops/harness.sh env                     print the measurement environment block
#   ./load/ops/harness.sh build                   build the boot jar and stage it
#   ./load/ops/harness.sh up <instances> <pool>   database + N application containers
#   ./load/ops/harness.sh fixture                 one learner, concept, item and mastery row
#   ./load/ops/harness.sh token <learnerId>       mint a bearer token
#   ./load/ops/harness.sh conns                   connections per client, from pg_stat_activity
#   ./load/ops/harness.sh down                    remove everything
#
# It is also sourced by the trap scripts beside it, which is why every function is defined
# before anything runs and nothing executes on import.
set -uo pipefail

# --------------------------------------------------------------------------------------
# Configuration. Every one of these appears in a report's environment block, so each is a
# variable rather than a literal buried in a docker command.
# --------------------------------------------------------------------------------------

: "${PROXIMA_JDK:=${JAVA_HOME:-}}"
: "${PROXIMA_OPS_HOME:=$HOME/.proxima-ops}"

BASE_IMAGE=${BASE_IMAGE:-ubuntu:24.04}
DB_IMAGE=${DB_IMAGE:-postgres:16-alpine}
NETWORK=${NETWORK:-proxima-boundary}

# PER APPLICATION INSTANCE. memory-swap is set equal to memory on purpose: docker's default
# when only --memory is given allows swap up to twice the limit, and a JVM that can swap is a
# JVM that does not die -- the limit would be enforced by nothing observable. R23 section 3.1.
APP_MEMORY=${APP_MEMORY:-512m}
APP_SWAP=${APP_SWAP:-$APP_MEMORY}

DB_NAME=${DB_NAME:-proxima}
DB_USER=${DB_USER:-proxima}
# Not a credential: it belongs to a container this script creates and destroys, on a network
# nothing else joins. The same reasoning application-test.yml applies to its signing key.
DB_PASSWORD=${DB_PASSWORD:-proxima-boundary-harness-local-only}
TOKEN_SECRET=${TOKEN_SECRET:-boundary-harness-signing-key-not-a-credential}

# Host ports. The first instance is at BASE_PORT, the second at BASE_PORT+1, and so on.
BASE_PORT=${BASE_PORT:-18080}

REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)

# --------------------------------------------------------------------------------------

die() { echo "FAIL: $*" >&2; exit 1; }

require_jdk() {
  [ -n "$PROXIMA_JDK" ] || die "PROXIMA_JDK or JAVA_HOME must point at the toolchain JDK."
  [ -x "$PROXIMA_JDK/bin/java" ] || die "no java at $PROXIMA_JDK/bin/java"
}

app_name() { echo "proxima-app-$1"; }
app_port() { echo $((BASE_PORT + $1 - 1)); }

# The environment block, printed rather than described -- the same move ADR-004 put into
# .github/workflows/build.yml. A number from this harness quoted without it is a number whose
# container limits are unknown, which measurement-discipline rule 10 forbids.
harness_env() {
  require_jdk
  echo "측정 환경 / Measurement environment  (load/ops/harness.sh)"
  echo "  Host CPU       : $(nproc) x $(sed -n 's/^model name[ \t]*: //p' /proc/cpuinfo | head -1)"
  echo "  Host memory    : $(free -m | awk '/^Mem:/ {printf "%.1f GiB", $2/1024}')"
  echo "  Kernel         : $(uname -sr)"
  echo "  Docker         : Engine $(docker version --format '{{.Server.Version}}'), native inside WSL2"
  echo "  JVM            : $("$PROXIMA_JDK/bin/java" -version 2>&1 | sed -n 2p | sed 's/^ *//')"
  echo "  Base image     : $BASE_IMAGE $(docker image inspect "$BASE_IMAGE" --format '{{index .RepoDigests 0}}' 2>/dev/null | sed 's/.*@//')"
  echo "  Database image : $DB_IMAGE $(docker image inspect "$DB_IMAGE" --format '{{index .RepoDigests 0}}' 2>/dev/null | sed 's/.*@//')"
  if docker ps --format '{{.Names}}' | grep -qx proxima-db; then
    echo "  Server version : $(docker exec proxima-db psql -U "$DB_USER" -d "$DB_NAME" -tAc 'show server_version' 2>/dev/null)"
    echo "  max_connections: $(docker exec proxima-db psql -U "$DB_USER" -d "$DB_NAME" -tAc 'show max_connections' 2>/dev/null), superuser_reserved_connections $(docker exec proxima-db psql -U "$DB_USER" -d "$DB_NAME" -tAc 'show superuser_reserved_connections' 2>/dev/null)"
  fi
  echo "  Container      : memory=$APP_MEMORY memory-swap=$APP_SWAP cpus=<unset>  (per APPLICATION instance)"
  echo "  Heap           : ergonomic -- no -Xmx is passed. R23 measures what that resolves to"
}

harness_build() {
  require_jdk
  mkdir -p "$PROXIMA_OPS_HOME/app"
  ( cd "$REPO_ROOT" && JAVA_HOME="$PROXIMA_JDK" ./gradlew :api:bootJar --no-daemon --console=plain ) || die "bootJar failed"
  cp "$REPO_ROOT/api/build/libs/api.jar" "$PROXIMA_OPS_HOME/app/api.jar" || die "no boot jar to stage"
  echo "staged $PROXIMA_OPS_HOME/app/api.jar  sha256 $(sha256sum "$PROXIMA_OPS_HOME/app/api.jar" | cut -c1-64)"
}

db_up() {
  docker rm -f proxima-db >/dev/null 2>&1
  docker network create "$NETWORK" >/dev/null 2>&1
  docker run -d --name proxima-db --network "$NETWORK" \
    -e POSTGRES_DB="$DB_NAME" -e POSTGRES_USER="$DB_USER" -e POSTGRES_PASSWORD="$DB_PASSWORD" \
    "$DB_IMAGE" >/dev/null || die "database did not start"
  for _ in $(seq 1 90); do
    docker exec proxima-db pg_isready -U "$DB_USER" -q && return 0
    sleep 1
  done
  die "database never became ready"
}

# One application instance. `pool` is maximum-pool-size; every remaining argument is a
# KEY=VALUE environment entry, which is how the arms of R24 differ from each other inside ONE
# jar -- R4 section 2's argument, applied to a container instead of to a flag.
app_up() {
  local n=$1 pool=$2; shift 2
  local name; name=$(app_name "$n")
  local port; port=$(app_port "$n")
  local env_args=()
  local kv; for kv in "$@"; do env_args+=(-e "$kv"); done

  docker rm -f "$name" >/dev/null 2>&1
  docker run -d --name "$name" --network "$NETWORK" \
    --memory="$APP_MEMORY" --memory-swap="$APP_SWAP" \
    -v "$PROXIMA_JDK":/jdk:ro -v "$PROXIMA_OPS_HOME/app":/app:ro \
    -p "$port":8080 \
    -e PROXIMA_DB_URL="jdbc:postgresql://proxima-db:5432/$DB_NAME?ApplicationName=$name" \
    -e PROXIMA_DB_USER="$DB_USER" \
    -e PROXIMA_DB_PASSWORD="$DB_PASSWORD" \
    -e PROXIMA_TOKEN_SECRET="$TOKEN_SECRET" \
    -e SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE="$pool" \
    -e SPRING_DATASOURCE_HIKARI_POOL_NAME="pool-$n" \
    "${env_args[@]}" \
    "$BASE_IMAGE" /jdk/bin/java -jar /app/api.jar >/dev/null || die "instance $n did not start"
}

# Waits for the instance to answer its LIVENESS probe, not /actuator/health.
#
#   R24 section 3.2 is the report on why: with the database unreachable, /actuator/health
#   blocks for the pool's connection-timeout -- 30 seconds by default -- before answering 503.
#   A readiness loop built on it would spend an arm's whole budget waiting, and would call an
#   instance "not started" when what happened is that its health check is slow.
app_wait() {
  local n=$1 limit=${2:-120}
  local port; port=$(app_port "$n")
  for i in $(seq 1 "$limit"); do
    if [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://localhost:$port/actuator/health/liveness")" = "200" ]; then
      echo "instance $n live after ${i}s (port $port)"; return 0
    fi
    sleep 1
  done
  echo "instance $n never became live"; docker logs "$(app_name "$n")" 2>&1 | tail -20; return 1
}

harness_up() {
  local instances=${1:-1} pool=${2:-10}
  shift 2 2>/dev/null || true
  require_jdk
  [ -f "$PROXIMA_OPS_HOME/app/api.jar" ] || die "run '$0 build' first"

  # EVERY instance, not only the ones about to be started.
  #
  #   `app_up` removes the container it is about to create, which is enough when a run only
  #   ever grows the fleet. It is not enough when an arm of three is followed by an arm of
  #   one: instances 2 and 3 survive, reconnect to the new database, and take slots the arm
  #   being measured believes it has to itself.
  #
  #   That happened. `ae5401b`'s drift control -- one instance, pool 60 -- reported 25
  #   connections and 15 `too many clients already` where arm A had reported 60 and none. The
  #   control was run because R18 section 3.3 asks for one, and the first thing it caught was
  #   this line missing.
  local stale; stale=$(docker ps -aq --filter "name=proxima-app-")
  [ -n "$stale" ] && docker rm -f $stale >/dev/null 2>&1

  db_up

  # ONE instance, then the fixture, then the rest. The order is a correction and each step of
  # it is load-bearing.
  #
  #   The fixture cannot go before any instance: there is no schema until Flyway has run, and
  #   Flyway runs when the application starts. It cannot go after ALL of them either -- an arm
  #   that asks for more connections than the database has leaves no slot for psql, so the
  #   insert silently inserts nothing. `on conflict do nothing` and a refused connection look
  #   identical to a caller that discarded stderr.
  #
  #   Measured in f120a13: eighty POSTs answered `200` and landed zero rows, every one of them
  #   rejected against a learner that was never created.
  local n
  app_up 1 "$pool" "$@"
  app_wait 1 || true
  harness_fixture > /dev/null || die "the fixture could not be inserted"

  for n in $(seq 2 "$instances"); do app_up "$n" "$pool" "$@"; done
  for n in $(seq 2 "$instances"); do app_wait "$n" || true; done
}

harness_down() {
  local apps; apps=$(docker ps -aq --filter "name=proxima-app-")
  [ -n "$apps" ] && docker rm -f $apps >/dev/null 2>&1
  docker rm -f proxima-db >/dev/null 2>&1
  docker network rm "$NETWORK" >/dev/null 2>&1
  echo "down"
}

# One learner with enough graph behind them for a recommendation and for a recording. The
# same shape as api/src/test/.../LearnerFixtures.kt, in SQL, because this harness has no JVM
# of its own to run Kotlin in.
#
# THIS IS NOT THE SEEDED DATASET. seed/ produces 3,963,719 rows from seed value 20260810 and
# every latency number in this repository is against those. Nothing here measures latency;
# the traps are about exit codes, connection counts and HTTP statuses, and a 3.9-million-row
# load would add tens of minutes to every arm without moving any of them. Any report using
# this harness says so in its environment block, and says what that costs.
harness_fixture() {
  docker exec -i proxima-db psql -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 -q <<'SQL'
insert into learner (external_ref) values ('ops-1')
  on conflict (external_ref) do nothing;
insert into concept (code, name, grade_band) values ('ops-1', 'Ops concept', 'G5-6')
  on conflict (code) do nothing;
insert into item (code, concept_primary_id, difficulty, is_active)
  select 'ops-1-item', c.id, 5, true from concept c where c.code = 'ops-1'
  on conflict (code) do nothing;
insert into item_concept (item_id, concept_id, weight)
  select i.id, c.id, 1.000 from item i, concept c where i.code = 'ops-1-item' and c.code = 'ops-1'
  on conflict do nothing;
insert into mastery (learner_id, concept_id, score, attempts_count, version, updated_at)
  select l.id, c.id, 0.000, 0, 0, now() from learner l, concept c
  where l.external_ref = 'ops-1' and c.code = 'ops-1'
  on conflict (learner_id, concept_id) do nothing;
SQL
  docker exec proxima-db psql -U "$DB_USER" -d "$DB_NAME" -tAc \
    "select l.id || ' ' || i.id || ' ' || c.id from learner l, item i, concept c
      where l.external_ref='ops-1' and i.code='ops-1-item' and c.code='ops-1'"
}

# RequestToken's format, in shell: <subject>.<issuedAt>.<expiresAt>.<base64url HMAC-SHA256>.
# The same three-field body load/recommendations.js signs with k6's crypto module.
harness_token() {
  local subject=$1 now body sig
  now=$(date +%s)
  body="$subject.$now.$((now + 3600))"
  sig=$(printf '%s' "$body" | openssl dgst -sha256 -hmac "$TOKEN_SECRET" -binary | base64 | tr '+/' '-_' | tr -d '=')
  echo "$body.$sig"
}

# Connections per application instance -- the whole question in R24 section 3.1.
#
#   `spring.datasource.hikari.pool-name` does NOT reach the wire. Measured: with it set to
#   `pool-1`, pg_stat_activity reported `PostgreSQL JDBC Driver 60`. The pool name is a JVM-side
#   label used for the pool's own logs and metrics, and the server has never heard of it.
#
#   What reaches the server is pgjdbc's `ApplicationName` URL parameter -- read out of
#   postgresql-42.7.11.jar, where PGProperty.APPLICATION_NAME carries the wire name
#   "ApplicationName" -- so `app_up` puts the container's name there. Without it every
#   instance's connections are indistinguishable, and the arithmetic this function exists to
#   check cannot be attributed to anybody.
harness_conns() {
  local out
  out=$(docker exec proxima-db psql -U "$DB_USER" -d "$DB_NAME" -tAF' ' -c \
    "select coalesce(nullif(application_name, ''), '(unnamed)'), count(*)
       from pg_stat_activity
      where datname = '$DB_NAME'
      group by 1
      order by 1" 2>&1)
  # THE INSTRUMENT IS SUBJECT TO THE THING IT MEASURES, and saying so is not optional. Once
  # the fleet has taken every slot, this query cannot get one either -- and a raw psql error
  # dropped into the middle of an arm's output reads like a script bug rather than like the
  # measurement it is. R24 section 3.1 quotes this line.
  if echo "$out" | grep -q 'too many clients already'; then
    echo "REFUSED: the monitoring connection could not be opened -- FATAL: sorry, too many"
    echo "REFUSED: clients already. Per-instance counts below come from each instance's own"
    echo "REFUSED: hikaricp.connections gauge, which is in-process and needs no slot."
    return 0
  fi
  echo "$out"
}

# Only dispatch when executed. When sourced by a trap script, everything above is a library.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  cmd=${1:-}; shift 2>/dev/null || true
  case "$cmd" in
    env)     harness_env ;;
    build)   harness_build ;;
    up)      harness_up "$@" ;;
    down)    harness_down ;;
    fixture) harness_fixture ;;
    token)   harness_token "$@" ;;
    conns)   harness_conns ;;
    *)       sed -n '/^# Usage:/,/^# It is also sourced/p' "$0"; exit 64 ;;
  esac
fi
