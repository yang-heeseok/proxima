#!/usr/bin/env bash
#
# R24 section 3.1 -- POOL SIZE x INSTANCE COUNT AGAINST max_connections.
#
# THE AXIS R2 AND R18 COULD NOT SEE.
#
#   Both sized a connection pool, and both measured ONE application process. R18 went as far
#   as noticing the ceiling was near -- "max_connections=100 was never reached but was never
#   far" -- and recorded how close it came as 미측정, because measuring it means querying
#   pg_stat_activity inside a measurement window and that is contamination.
#
#   Nothing here measures latency, so there is no window to contaminate. That is the whole
#   reason this arm can ask the question those two could not: `pool x instances` is arithmetic
#   over a resource that is GLOBAL to the database and LOCAL to each process, and one process
#   cannot show it. A pool of 60 is sober on one instance and fatal on two.
#
# WHAT THE ARMS ARE, AND WHY THE LAST ONE IS NOT A CONTROL
#
#   A   1 instance  x pool 60 =  60   under the ceiling
#   B   2 instances x pool 60 = 120   over it
#   C   3 instances x pool 60 = 180   well over it
#   D   3 instances x pool 25 =  75   the same fleet, arithmetic that fits
#
#   D is the remedy, measured in the same session on the same jar rather than argued. It is
#   not a control for A -- the drift control is a separate re-run of A at the end, because
#   R18 section 3.3 measured 1.27x of drift between identical configurations seventy minutes
#   apart and killed one of its own conclusions with it.
#
# Usage:  ./load/ops/trap-pool.sh
set -uo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/harness.sh"

BURST=${BURST:-40}
WRITE_CONCURRENCY=${WRITE_CONCURRENCY:-80}

# What the database will actually hand out. `max_connections` is not that number: PostgreSQL
# holds `superuser_reserved_connections` back so that an administrator can still get in when
# an application has taken everything, and PostgreSQL 16 adds `reserved_connections` on top
# for roles with pg_use_reserved_connections. Both are read from the server rather than
# assumed, because the arithmetic this script is about is off by exactly this much otherwise.
ceiling() {
  docker exec proxima-db psql -U "$DB_USER" -d "$DB_NAME" -tAF' ' -c \
    "select current_setting('max_connections'),
            current_setting('superuser_reserved_connections'),
            current_setting('reserved_connections')"
}

# A concurrent WRITE burst at ONE instance, which is the only workload here that makes the
# pool the binding constraint.
#
#   The read path against this fixture returns an empty list in single-digit milliseconds, so
#   forty concurrent reads never need more than a handful of connections and a starved pool
#   looks exactly like a healthy one. Writes contend: every recording below moves the SAME
#   (learner, concept) mastery row, so each request holds its connection while waiting for
#   that row's lock. `scoreDelta` is 0.000 -- inside the 0..1 band whatever the score already
#   is -- so the band never becomes the reason a request fails, and the only thing being
#   measured is whether a connection was available.
#
#   This is the workload ADR-009 named as what would flip it: "connection-pool behaviour under
#   concurrent writes is the likeliest".
write_burst() {
  local n=$1 token=$2 concurrency=$3
  local port; port=$(app_port "$n")
  local body='[{"itemId":1,"conceptId":1,"correct":true,"elapsedMs":10,"at":"2026-08-10T00:00:00Z","scoreDelta":0.000}]'
  local i
  for i in $(seq 1 "$concurrency"); do
    curl -s -o /dev/null -w "%{http_code} %{time_total}\n" --max-time 90 \
      -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
      -X POST -d "$body" \
      "http://localhost:$port/api/v1/learners/1/attempts" &
  done
  wait
}

# The status distribution, and the shape of the durations behind it.
#
#   80 responses of `200` is not an answer to "does the starved instance serve worse". A
#   duration is -- and it is the only duration this directory publishes, so it carries the
#   caveats: ONE run, not the median of three that measurement-discipline rule 5 requires for
#   a headline figure, and taken against a one-row fixture that no latency number elsewhere in
#   this repository shares. R24 section 3.1 quotes it as an observation and says so.
summarise_burst() {
  local raw; raw=$(mktemp)
  cat > "$raw"
  printf '     status: %s\n' "$(awk '{print $1}' "$raw" | sort | uniq -c | awk '{printf "%s x%s  ", $2, $1}')"
  local sorted; sorted=$(mktemp)
  awk '{print $2}' "$raw" | sort -n > "$sorted"
  local n; n=$(wc -l < "$sorted")
  printf '     time_total  p50 %ss  p95 %ss  max %ss  (n=%s)\n' \
    "$(sed -n "$(((n + 1) / 2))p" "$sorted")" \
    "$(sed -n "$(((n * 95 + 99) / 100))p" "$sorted")" \
    "$(tail -1 "$sorted")" "$n"
  rm -f "$raw" "$sorted"
}

# Drives every instance at once, so that demand is concurrent across the fleet rather than
# one instance at a time -- the failure is a race for a global resource and a serial probe
# would let each instance win it in turn.
burst() {
  local instances=$1 token=$2 n port i
  for n in $(seq 1 "$instances"); do
    port=$(app_port "$n")
    for i in $(seq 1 "$BURST"); do
      curl -s -o /dev/null -w "$n %{http_code}\n" --max-time 60 \
        -H "Authorization: Bearer $token" \
        "http://localhost:$port/api/v1/learners/1/recommendations" &
    done
  done
  wait
}

arm() {
  local label=$1 instances=$2 pool=$3
  echo ""
  echo "=================================================================="
  echo "ARM $label -- $instances instance(s) x pool $pool = $((instances * pool)) requested"
  echo "=================================================================="

  harness_up "$instances" "$pool" > /tmp/proxima-up.log 2>&1
  grep -E 'live after|never became live' /tmp/proxima-up.log || true

  harness_fixture > /dev/null 2>&1
  local token; token=$(harness_token 1)

  # Idle first. minimum-idle defaults to maximum-pool-size, so the pool fills without being
  # asked -- which means the failure does not need load to arrive, only time.
  sleep 10
  echo "-- backends by application_name, at idle --"
  harness_conns

  echo "-- $BURST concurrent requests per instance --"
  burst "$instances" "$token" | sort | uniq -c | sed 's/^/   /'

  echo "-- backends by application_name, under load --"
  harness_conns

  echo "-- what each side said --"
  echo "   database  FATAL: sorry, too many clients already : $(docker logs proxima-db 2>&1 | grep -c 'too many clients already')"
  local n
  for n in $(seq 1 "$instances"); do
    local log; log=$(docker logs "$(app_name "$n")" 2>&1)
    printf '   instance %s  connection-not-available: %-4s  too-many-clients: %-4s  pool total: %s\n' \
      "$n" \
      "$(echo "$log" | grep -c 'Connection is not available')" \
      "$(echo "$log" | grep -c 'too many clients already')" \
      "$(curl -s --max-time 5 "http://localhost:$(app_port "$n")/actuator/metrics/hikaricp.connections" \
         | grep -o '"value":[0-9.]*' | head -1 | cut -d: -f2)"
  done
}

harness_env
echo ""
echo "database ceiling (max_connections / superuser_reserved / reserved): $(harness_up 1 10 >/dev/null 2>&1; ceiling)"

arm A 1 60
arm B 2 60
arm C 3 60
arm D 3 25

# ARM E -- the same starved fleet as C, and a workload that can actually feel it.
#
#   C ends with three instances holding wildly unequal pools and every HTTP request answering
#   200, because the read path against this fixture is too cheap to need connections. E asks
#   the question that matters to a user: does the instance that LOST the race for the ceiling
#   serve worse than the one that won it? Same fleet, same jar, same moment -- the only
#   variable is which port the writes go to.
echo ""
echo "=================================================================="
echo "ARM E -- C's fleet, and $WRITE_CONCURRENCY concurrent writes at the winner vs the loser"
echo "=================================================================="
harness_up 3 60 > /tmp/proxima-up.log 2>&1
grep -E 'live after|never became live' /tmp/proxima-up.log || true
harness_fixture > /dev/null 2>&1
sleep 10
echo "-- pools each instance managed to fill --"
for n in 1 2 3; do
  printf '   instance %s  hikaricp.connections %s\n' "$n" \
    "$(curl -s --max-time 5 "http://localhost:$(app_port "$n")/actuator/metrics/hikaricp.connections" \
       | grep -o '"value":[0-9.]*' | head -1 | cut -d: -f2)"
done
token=$(harness_token 1)
for n in 1 3; do
  echo "-- $WRITE_CONCURRENCY concurrent single-recording POSTs at instance $n --"
  before=$(docker logs "$(app_name "$n")" 2>&1 | grep -c 'Connection is not available')
  write_burst "$n" "$token" "$WRITE_CONCURRENCY" | summarise_burst
  after=$(docker logs "$(app_name "$n")" 2>&1 | grep -c 'Connection is not available')
  echo "     Connection is not available, on instance $n: $((after - before))"
done

# The rows, not the responses. This has to wait until the fleet is torn down: with three
# instances holding every slot, the query that would read the table is refused -- which is
# section 3.1's own finding turned against the person trying to check it.
echo "-- attempt rows landed (fleet stopped first, so a slot exists to ask from) --"
docker rm -f "$(app_name 2)" "$(app_name 3)" >/dev/null 2>&1
sleep 2
docker exec proxima-db psql -U "$DB_USER" -d "$DB_NAME" -tAc \
  "select 'attempt rows ' || count(*) from attempt where learner_id = 1" 2>&1 | sed 's/^/   /'
docker exec proxima-db psql -U "$DB_USER" -d "$DB_NAME" -tAc \
  "select 'mastery attempts_count ' || attempts_count from mastery where learner_id = 1" 2>&1 | sed 's/^/   /'

# THE DRIFT CONTROL. R18 section 3.3: identical configuration seventy minutes apart differed
# by 1.27x, which was larger than one of the effects that report set out to claim. Nothing
# below is a duration, so drift cannot move it the same way -- but that is a claim, and this
# arm is what turns it into a measurement. A connection count that moves between two runs of
# arm A means the counts above are not the property of the configuration they are labelled
# with.
arm "A-prime (drift control)" 1 60

harness_down
