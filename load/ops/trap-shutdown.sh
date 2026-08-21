#!/usr/bin/env bash
#
# R24 section 3.3 -- A DEPLOYMENT AND A REQUEST IN FLIGHT, ON THE WRITE PATH.
#
# WHY THIS NEEDED AN ENDPOINT AND WHY ADR-009 IS THE ONE THAT SAID SO.
#
#   R6, R7 and R12 all measured the write path with JVM THREADS calling the service. A thread
#   has no socket to reset, no Tomcat worker to drain and no relationship to `server.shutdown`,
#   so none of them can answer what happens to a request that is BEING PROCESSED when the
#   container goes away. ADR-009 refused an endpoint and named this as what would flip it:
#   "a load measurement whose question genuinely needs HTTP on the write path".
#
# WHAT IS ACTUALLY BEING ASKED, AND HOW IT IS SEPARATED FROM WHAT IS EASY TO ASK
#
#   "Is anything half-committed" is the question, and the client's view cannot answer it -- a
#   client whose connection was reset knows nothing about what landed. The table can:
#   `AttemptRecorder.record` writes an `attempt` row AND moves the `mastery` row in ONE
#   transaction, so `count(attempt)` and `mastery.attempts_count` are two independent records
#   of the same set of commits. IF THEY EVER DISAGREE, a transaction was torn in half.
#
#   That is what makes this measurable at all, and it is why the batch is large: a request
#   that finishes before the signal arrives measures nothing.
#
# THE THREE ARMS, AND WHY THE FIRST ONE IS NOT THE DEFAULT ANY MORE
#
#   A  SIGTERM, server.shutdown=graceful    the Boot 4.1.0 DEFAULT, read out of
#                                           spring-boot-web-server-4.1.0.jar's own
#                                           spring-configuration-metadata.json
#   B  SIGTERM, server.shutdown=immediate   what the default was before, and what most
#                                           deployment documentation still assumes
#   C  SIGKILL                              `docker kill`. No grace of any kind
#
# Usage:  ./load/ops/trap-shutdown.sh
set -uo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/harness.sh"

# Big enough that the request is still running when the signal arrives, and every entry is
# scoreDelta 0.000 so the 0..1 band is never the reason one fails -- the only thing under
# measurement is whether the process survived long enough to commit it.
BATCH=${BATCH:-400}
# When to signal, measured from the moment the request was sent.
CUT_AFTER=${CUT_AFTER:-3}

batch_body() {
  local n=$1 i out=''
  for i in $(seq 1 "$n"); do
    out="$out,{\"itemId\":1,\"conceptId\":1,\"correct\":true,\"elapsedMs\":10,\"at\":\"2026-08-10T00:00:00Z\",\"scoreDelta\":0.000}"
  done
  echo "[${out#,}]"
}

rows() {
  docker exec proxima-db psql -U "$DB_USER" -d "$DB_NAME" -tAF' ' -c \
    "select (select count(*) from attempt where learner_id = 1),
            (select attempts_count from mastery where learner_id = 1)" 2>/dev/null
}

# One arm: start clean, send a batch, cut the instance down mid-flight, and read both tables.
arm() {
  local label=$1 signal=$2; shift 2
  echo ""
  echo "=================================================================="
  echo "ARM $label -- $signal"
  echo "=================================================================="

  harness_up 1 10 "$@" > /tmp/proxima-up.log 2>&1
  grep -E 'live after|never became live' /tmp/proxima-up.log || true

  local before; before=$(rows)
  echo "   before          attempt=$(echo "$before" | cut -d' ' -f1)  mastery.attempts_count=$(echo "$before" | cut -d' ' -f2)"

  local body; body=$(batch_body "$BATCH")
  local token; token=$(harness_token 1)
  local out=/tmp/proxima-client.out
  local t0; t0=$(date +%s%3N)

  (
    curl -s -o /tmp/proxima-client.body -w '%{http_code} %{time_total}' --max-time 180 \
      -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
      -X POST -d "$body" \
      "http://localhost:$(app_port 1)/api/v1/learners/1/attempts" > "$out" 2>&1 \
      || echo "TRANSPORT FAILURE ($?)" > "$out"
  ) &
  local client=$!

  sleep "$CUT_AFTER"
  local t1; t1=$(date +%s%3N)
  case "$signal" in
    SIGTERM) docker stop "$(app_name 1)" > /dev/null ;;
    SIGKILL) docker kill "$(app_name 1)" > /dev/null ;;
  esac
  local t2; t2=$(date +%s%3N)
  echo "   signal sent     ${signal} at +$((t1 - t0))ms, container gone at +$((t2 - t0))ms  (docker took $((t2 - t1))ms)"

  wait "$client"
  echo "   client saw      $(cat "$out")   body: $(head -c 60 /tmp/proxima-client.body 2>/dev/null)"

  local after; after=$(rows)
  local a m; a=$(echo "$after" | cut -d' ' -f1); m=$(echo "$after" | cut -d' ' -f2)
  echo "   after           attempt=$a  mastery.attempts_count=$m  (of $BATCH sent)"
  if [ "$a" = "$m" ]; then
    echo "   TORN?           no -- the two tables agree, so every transaction that started either committed or rolled back"
  else
    echo "   TORN?           YES -- attempt=$a and mastery.attempts_count=$m disagree by $((a - m))"
  fi
  echo "   exit / oom      $(docker inspect "$(app_name 1)" --format '{{.State.ExitCode}} / OOMKilled={{.State.OOMKilled}}' 2>/dev/null)"
  echo "   shutdown log    $(docker logs "$(app_name 1)" 2>&1 | grep -cE 'Commencing graceful shutdown') graceful-shutdown line(s), $(docker logs "$(app_name 1)" 2>&1 | grep -cE 'Graceful shutdown complete') completion line(s)"
  docker logs "$(app_name 1)" 2>&1 | grep -E 'GracefulShutdown' | tail -2 | sed 's/^/   /'
}

harness_env

# `server.shutdown` defaults to `graceful` on this version, so arm A passes nothing.
arm A SIGTERM
arm B SIGTERM "--server.shutdown=immediate"
arm C SIGKILL

echo ""
echo "=================================================================="
echo "THE TWO TIMEOUTS NOBODY LINED UP"
echo "=================================================================="
# spring.lifecycle.timeout-per-shutdown-phase defaults to 30s -- from the framework's own
# configuration metadata -- and `docker stop` sends SIGKILL after 10s by default. A request
# that needs more than ten seconds to drain is killed by the platform while the framework is
# still politely waiting for it. Both numbers are defaults; neither knows about the other.
BATCH=$((BATCH * 6)) arm D SIGTERM

harness_down
