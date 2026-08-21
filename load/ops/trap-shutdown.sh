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
# THE BATCH HAS TO OUTLIVE THE SIGNAL, AND THE FIRST VERSION OF THIS SCRIPT DID NOT MANAGE IT.
#
#   `cd3f34f` sent 400 recordings, which take 1.8-2.3s, and signalled at 3.0s. Every arm
#   therefore killed an idle container, all three agreed, and the agreement looked like a
#   result. THREE ARMS AGREEING IS WHAT A BROKEN EXPERIMENT LOOKS LIKE. BATCH is now sized
#   against a measured per-recording cost and the arm prints how long the batch runs
#   uninterrupted, so the premise is visible rather than assumed.
#
# THE FOUR ARMS
#
#   A  SIGTERM, grace 60s, server.shutdown=graceful   the Boot 4.1.0 DEFAULT, read out of
#                                                      spring-boot-web-server-4.1.0.jar's own
#                                                      spring-configuration-metadata.json
#   B  SIGTERM, grace 60s, server.shutdown=immediate  what the default was before, and what
#                                                      most deployment documentation assumes
#   C  SIGKILL                                         `docker kill`. No grace of any kind
#   D  SIGTERM, grace 10s (docker's DEFAULT), graceful the two timeouts nobody lined up
#
# Usage:  ./load/ops/trap-shutdown.sh
set -uo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/harness.sh"

# Every entry is scoreDelta 0.000, so the 0..1 band is never the reason one fails -- the only
# thing under measurement is whether the process survived long enough to commit it.
BATCH=${BATCH:-4000}
# When to signal, measured from the moment the request was sent.
CUT_AFTER=${CUT_AFTER:-3}

BODY_FILE=/tmp/proxima-client.body
STAT_FILE=/tmp/proxima-client.stat
# THE REQUEST BODY IS A FILE AND NOT AN ARGUMENT. A batch long enough to still be running when
# the signal arrives is around 450 kB, and Linux caps a SINGLE argument at MAX_ARG_STRLEN --
# 32 pages, 131072 bytes -- so `curl -d "$BODY"` dies with `Argument list too long` well before
# the batch is big enough to measure anything. `-d @file` has no such limit.
REQUEST_FILE=/tmp/proxima-request.json

batch_body() {
  local n=$1 i
  : > "$REQUEST_FILE"
  printf '[' >> "$REQUEST_FILE"
  for i in $(seq 1 "$n"); do
    [ "$i" -gt 1 ] && printf ',' >> "$REQUEST_FILE"
    printf '{"itemId":1,"conceptId":1,"correct":true,"elapsedMs":10,"at":"2026-08-10T00:00:00Z","scoreDelta":0.000}' >> "$REQUEST_FILE"
  done
  printf ']' >> "$REQUEST_FILE"
  echo "   request body    $(wc -c < "$REQUEST_FILE") bytes, $n recordings"
}

rows() {
  docker exec proxima-db psql -U "$DB_USER" -d "$DB_NAME" -tAF' ' -c \
    "select (select count(*) from attempt where learner_id = 1),
            (select coalesce(max(attempts_count), 0) from mastery where learner_id = 1)" 2>/dev/null
}

# How long the batch takes when NOTHING interrupts it. Without this the arms below cannot say
# whether a request was in flight when the signal arrived, which is the premise cd3f34f got
# wrong and reported around.
baseline() {  # reads REQUEST_FILE
  harness_up 1 10 > /tmp/proxima-up.log 2>&1
  local token; token=$(harness_token 1)
  local t0; t0=$(date +%s%3N)
  curl -s -o /dev/null --max-time 300 \
    -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
    -X POST -d "@$REQUEST_FILE" "http://localhost:$(app_port 1)/api/v1/learners/1/attempts"
  local t1; t1=$(date +%s%3N)
  echo "$((t1 - t0))"
}

arm() {
  local label=$1 signal=$2 grace=$3; shift 3
  echo ""
  echo "=================================================================="
  echo "ARM $label -- $signal${grace:+, docker grace ${grace}s} ${*:-(shipped defaults)}"
  echo "=================================================================="

  # Stale output from a previous arm is how cd3f34f printed arm C's body under arm D.
  rm -f "$BODY_FILE" "$STAT_FILE"

  harness_up 1 10 "$@" > /tmp/proxima-up.log 2>&1
  grep -E 'live after|never became live' /tmp/proxima-up.log || true

  local before; before=$(rows)
  echo "   before          attempt=$(echo "$before" | cut -d' ' -f1)  mastery.attempts_count=$(echo "$before" | cut -d' ' -f2)"

  local token; token=$(harness_token 1)
  local t0; t0=$(date +%s%3N)

  (
    code=$(curl -s -o "$BODY_FILE" -w '%{http_code} %{time_total}' --max-time 300 \
             -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
             -X POST -d "@$REQUEST_FILE" "http://localhost:$(app_port 1)/api/v1/learners/1/attempts")
    status=$?
    if [ "$status" -ne 0 ]; then
      echo "TRANSPORT FAILURE, curl exit $status" > "$STAT_FILE"
    else
      echo "HTTP $code" > "$STAT_FILE"
    fi
  ) &
  local client=$!

  sleep "$CUT_AFTER"
  local t1; t1=$(date +%s%3N)
  case "$signal" in
    SIGTERM) docker stop ${grace:+-t "$grace"} "$(app_name 1)" > /dev/null ;;
    SIGKILL) docker kill "$(app_name 1)" > /dev/null ;;
  esac
  local t2; t2=$(date +%s%3N)
  echo "   signal          $signal at +$((t1 - t0))ms; container gone at +$((t2 - t0))ms (docker waited $((t2 - t1))ms)"

  wait "$client"
  echo "   client saw      $(cat "$STAT_FILE" 2>/dev/null || echo '(no result)')   body: $(head -c 55 "$BODY_FILE" 2>/dev/null)"

  local after; after=$(rows)
  local a m; a=$(echo "$after" | cut -d' ' -f1); m=$(echo "$after" | cut -d' ' -f2)
  echo "   after           attempt=$a  mastery.attempts_count=$m  of $BATCH sent"
  if [ "$a" = "$m" ]; then
    echo "   torn?           NO -- the two tables agree, so every transaction either committed or rolled back whole"
  else
    echo "   torn?           YES -- attempt=$a and mastery.attempts_count=$m disagree by $((a - m))"
  fi
  echo "   exit code       $(docker inspect "$(app_name 1)" --format '{{.State.ExitCode}}' 2>/dev/null)"
  docker logs "$(app_name 1)" 2>&1 | grep -E 'GracefulShutdown' | sed 's/.*: /   shutdown log    /' | tail -2
}

harness_env
batch_body "$BATCH"
echo ""
uninterrupted=$(baseline)
echo "PREMISE: $BATCH recordings take ${uninterrupted}ms uninterrupted; the signal is sent at $((CUT_AFTER * 1000))ms."
if [ "$uninterrupted" -lt $((CUT_AFTER * 1000 + 5000)) ]; then
  echo "PREMISE FAILS: the batch finishes too close to the signal for anything to be in flight."
  echo "Raise BATCH. This is the defect cd3f34f shipped and reported around."
  harness_down; exit 1
fi

arm A SIGTERM 60
arm B SIGTERM 60 "--server.shutdown=immediate"
arm C SIGKILL ""
# `docker stop` sends SIGKILL after 10 seconds by default, and
# spring.lifecycle.timeout-per-shutdown-phase defaults to 30 -- from the framework's own
# configuration metadata. Both are defaults, neither knows about the other, and a request that
# needs more than ten seconds to drain is killed by the platform while the framework is still
# waiting for it. Arm D passes no -t, so docker's default is the variable.
arm D SIGTERM ""

# ARM E -- the same configuration as D with a batch that CANNOT drain inside ten seconds.
#
#   D is the interesting arm and it passes, by 456ms: with 3s already spent, 4000 recordings
#   have about 9.1s of work left and docker's patience is 10. That margin is not a design; it
#   is the batch size. E doubles it so the arm reports what the boundary does rather than what
#   it nearly did -- because "it fits" and "it fits today" are the same observation until
#   somebody makes the request longer.
BATCH=$((BATCH * 2))
batch_body "$BATCH"
arm E SIGTERM ""

harness_down
