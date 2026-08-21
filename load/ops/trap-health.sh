#!/usr/bin/env bash
#
# R24 section 3.2 -- THE HEALTH CHECK AND THE DATABASE.
#
# THE TRAP HAS TWO HALVES AND BOTH OF THEM ARE INCIDENTS.
#
#   If /actuator/health answers 200 while the database is gone, the load balancer keeps
#   routing to an instance that cannot serve a request. If it answers 503, one database blip
#   takes every instance out at the same instant, because they all check the same database.
#   Splitting liveness from readiness is the answer -- liveness says "this process is not
#   wedged, do not restart me", readiness says "I can serve, send me traffic".
#
#   ON SPRING BOOT 4.1.0 THE SPLIT ALREADY EXISTS AND `db` IS ON NEITHER SIDE OF IT.
#   `management.endpoint.health.probes.enabled` defaults to `true` -- read out of
#   spring-boot-health-4.1.0.jar's own spring-configuration-metadata.json -- and the groups it
#   creates are built by AvailabilityProbesHealthEndpointGroups, whose entire constant pool is
#   `liveness / livenessState / readiness / readinessState`. The datasource indicator is not in
#   either. So this script does not ask whether the split exists. It asks what each of the
#   three URLs says while the database is unreachable, and what a real request says beside them.
#
# WHY THE DATABASE IS KILLED THREE DIFFERENT WAYS
#
#   `docker stop` removes the container from the network's DNS, so the failure the application
#   sees is UnknownHostException -- the NAME is gone. Killing postgres inside a container that
#   stays up leaves the name resolving and the port closed, which is `Connection refused`.
#   They are different code paths with different timings and a report that measures one and
#   says "the database was down" has measured one.
#
# Usage:  ./load/ops/trap-health.sh
set -uo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/harness.sh"

INSTANCES=${INSTANCES:-2}
PROBE_TIMEOUT=${PROBE_TIMEOUT:-60}
# 210 is one more than server.tomcat.threads.max, whose default is 200 -- read out of
# spring-boot-web-server-4.1.0.jar's spring-configuration-metadata.json. A storm smaller than
# that cannot exhaust the worker pool and would answer the question with "no" for the wrong
# reason.
PROBE_STORM=${PROBE_STORM:-210}
# Seconds to wait after the database stops before probing, so that every arm sees the same
# pool state. See the boundary arm.
SETTLE=${SETTLE:-5}

# Status and wall time for one URL. The time is the point: a probe that eventually answers
# correctly after thirty seconds has already been counted as a timeout by anything watching it.
probe() {
  local label=$1 url=$2
  printf '   %-34s %s\n' "$label" \
    "$(curl -s -o /tmp/proxima-probe.body -w '%{http_code} in %{time_total}s' --max-time "$PROBE_TIMEOUT" "$url" 2>/dev/null || echo 'no answer within '"$PROBE_TIMEOUT"'s')  $(head -c 90 /tmp/proxima-probe.body)"
}

probe_instance() {
  local n=$1 token=$2
  local port; port=$(app_port "$n")
  echo "  instance $n (port $port)"
  probe "/actuator/health"            "http://localhost:$port/actuator/health"
  probe "/actuator/health/liveness"   "http://localhost:$port/actuator/health/liveness"
  probe "/actuator/health/readiness"  "http://localhost:$port/actuator/health/readiness"
  printf '   %-34s %s\n' "GET .../recommendations" \
    "$(curl -s -o /tmp/proxima-probe.body -w '%{http_code} in %{time_total}s' --max-time "$PROBE_TIMEOUT" \
        -H "Authorization: Bearer $token" \
        "http://localhost:$port/api/v1/learners/1/recommendations" 2>/dev/null || echo 'no answer')  $(head -c 60 /tmp/proxima-probe.body)"
}

probe_all() {
  local token=$1 n
  for n in $(seq 1 "$INSTANCES"); do probe_instance "$n" "$token"; done
}

harness_env
harness_up "$INSTANCES" 10 > /tmp/proxima-up.log 2>&1
grep -E 'live after|never became live' /tmp/proxima-up.log || true
harness_env | grep -E 'Server version|max_connections'
token=$(harness_token 1)

echo ""
echo "=================================================================="
echo "BASELINE -- database up"
echo "=================================================================="
probe_all "$token"

echo ""
echo "=================================================================="
echo "ARM 1 -- the database's NAME is gone (docker stop: UnknownHostException)"
echo "=================================================================="
docker stop proxima-db > /dev/null
probe_all "$token"
echo "  what the application logged about its own health check:"
docker logs "$(app_name 1)" 2>&1 | grep -E 'took [0-9]+ms to respond' | tail -2 | sed 's/^/   /'
docker start proxima-db > /dev/null
for _ in $(seq 1 90); do docker exec proxima-db pg_isready -U "$DB_USER" -q && break; sleep 1; done

echo ""
echo "  -- and how long recovery takes, probed once a second --"
for i in $(seq 1 20); do
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 40 "http://localhost:$(app_port 1)/actuator/health")
  echo "   +${i}s after the database is ready: /actuator/health $code"
  [ "$code" = "200" ] && break
  sleep 1
done

echo ""
echo "=================================================================="
echo "ARM 2 -- the NAME resolves and the port is closed (postgres killed in place)"
echo "=================================================================="
# `docker exec ... pg_ctl stop -m immediate` leaves the container running, so DNS still
# answers and the TCP connect is refused rather than unresolvable. Different code path,
# different timing -- and it is the shape a database crash actually has.
docker exec -u postgres proxima-db pg_ctl -D /var/lib/postgresql/data stop -m immediate > /dev/null 2>&1
sleep 2
probe_all "$token"

echo ""
echo "=================================================================="
echo "ARM 3 -- what a slow health check costs while it is slow"
echo "=================================================================="
# A probe that takes 30 seconds holds a Tomcat worker for 30 seconds. Tomcat's default
# max-threads is 200, so a load balancer polling every few seconds with a shorter timeout of
# its own stacks probes faster than they drain. This arm asks whether that is a real cost here
# or an arithmetic worry: fire PROBE_STORM health checks at once and see whether anything else
# on the same instance can still be served.
echo "  firing $PROBE_STORM concurrent /actuator/health at instance 1, database still gone"
for _ in $(seq 1 "$PROBE_STORM"); do
  curl -s -o /dev/null --max-time 90 "http://localhost:$(app_port 1)/actuator/health" &
done
sleep 3
printf '   %-34s %s\n' "liveness, during the storm" \
  "$(curl -s -o /dev/null -w '%{http_code} in %{time_total}s' --max-time 20 "http://localhost:$(app_port 1)/actuator/health/liveness")"
printf '   %-34s %s\n' "readiness, during the storm" \
  "$(curl -s -o /dev/null -w '%{http_code} in %{time_total}s' --max-time 20 "http://localhost:$(app_port 1)/actuator/health/readiness")"
# The one that matters. Readiness and liveness are cheap by construction; the question is
# whether an application request can still get a worker while 210 health checks are each
# holding one for thirty seconds.
printf '   %-34s %s\n' "GET .../recommendations, during it" \
  "$(curl -s -o /dev/null -w '%{http_code} in %{time_total}s' --max-time 20 "http://localhost:$(app_port 1)/api/v1/learners/1/recommendations" -H "Authorization: Bearer $token")"
wait

echo ""
echo "=================================================================="
echo "THE BOUNDARY -- db moved into the readiness group, one instance only"
echo "=================================================================="
# The remedy, measured rather than argued: management.endpoint.health.group.readiness.include
# adds `db` to the group the load balancer reads, and leaves `liveness` alone so that a
# database outage does not get every instance RESTARTED on top of being drained.
harness_down > /dev/null
harness_up 1 10 \
  "MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE=readinessState,db" \
  > /tmp/proxima-up.log 2>&1
grep -E 'live after|never became live' /tmp/proxima-up.log || true
token=$(harness_token 1)
echo "  database up:"
INSTANCES=1 probe_all "$token"
docker stop proxima-db > /dev/null
# THE SETTLE IS A CONTROL, NOT POLITENESS. Probed immediately after the stop, a dead but
# not-yet-evicted connection is still in the pool and gets handed out, so the failure is a
# closed socket -- fast -- rather than a pool with nothing in it, which waits the full
# connection-timeout. Without this the arm would report a change in /actuator/health's latency
# beside a configuration change that cannot affect it, and R18 section 3.3 is this
# repository's report on attributing a timing difference to the wrong variable.
sleep "$SETTLE"
echo "  database name gone (after ${SETTLE}s, so the pool state matches arms 1 and 2):"
INSTANCES=1 probe_all "$token"

echo ""
echo "=================================================================="
echo "THE SECOND HALF -- the signal is right and it is still 30 seconds late"
echo "=================================================================="
# Moving `db` into the readiness group fixes WHAT the probe says and not HOW LONG it takes to
# say it, so a readiness probe polled more often than 30 seconds reproduces arm 3's storm on
# the endpoint the load balancer reads. The 30 seconds is `spring.datasource.hikari.
# connection-timeout` -- HikariCP's default of 30000ms, quoted in measurement-discipline.md's
# environment block since 2026-08-10 and never varied here.
#
# THE COST IS NOT FREE AND IS NOT LOCAL TO THE PROBE. connection-timeout applies to every
# caller of getConnection, so lowering it also fails REAL requests that would have waited and
# succeeded. This arm measures what it buys; R24 section 5 is where the trade is argued.
harness_down > /dev/null
# A PROGRAM ARGUMENT AND NOT AN ENVIRONMENT VARIABLE, and the reason is in app_up's comment:
# SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT makes the binder call getConnection() and seal
# the pool, and this arm is the one that found it (e18f82a).
harness_up 1 10 \
  "MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE=readinessState,db" \
  "--spring.datasource.hikari.connection-timeout=2000" \
  > /tmp/proxima-up.log 2>&1
grep -E 'live after|never became live' /tmp/proxima-up.log || true
token=$(harness_token 1)
echo "  database up:"
INSTANCES=1 probe_all "$token"
docker stop proxima-db > /dev/null
sleep "$SETTLE"
echo "  database name gone (after ${SETTLE}s):"
INSTANCES=1 probe_all "$token"

harness_down
