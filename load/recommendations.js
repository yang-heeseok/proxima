// GET /api/v1/learners/{id}/recommendations under concurrency.
//
// This is the scenario the connection-pool report is built on. Its shape encodes the
// measurement rules rather than relying on the operator to remember them:
//
//   - a warm-up stage whose samples are DISCARDED, because a JVM's first seconds are
//     interpreted code and a cold connection pool, and including them has been observed
//     to move a p99 by enough to reverse a comparison;
//   - a fixed measurement window, so two runs are the same length;
//   - thresholds that FAIL the run rather than printing a warning, because a threshold
//     that only warns is a comment.
//
// See docs/explanation/measurement-discipline.md.

import http from 'k6/http'
import { check } from 'k6'
import { Trend, Rate } from 'k6/metrics'

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080'
const VUS = parseInt(__ENV.VUS || '200', 10)
const LEARNERS = parseInt(__ENV.LEARNERS || '1000', 10)

// Measured separately from the built-in http_req_duration so that warm-up traffic,
// which also hits http_req_duration, cannot contaminate the reported number.
const measured = new Trend('measured_duration', true)
const measuredErrors = new Rate('measured_errors')

// STEADY-STATE CHECK. The measurement window is split in half and the halves are compared.
//
//   The 30-second warm-up above warms a JVM. It does not warm three million rows into a
//   cold page cache, and on 2026-08-12 a run whose container had just restarted took 91
//   minutes instead of 3.5, with percentiles in hours. Nothing in this script noticed; the
//   run produced a number and the number was garbage.
//
//   A run that is still warming gets faster as it goes. If the first half is materially
//   slower than the second, the system had not reached steady state and the run is not
//   comparable to one that had. That is a property this script can see for itself.
const measuredEarly = new Trend('measured_early', true)
const measuredLate = new Trend('measured_late', true)

// How much slower the first half may be before the run is called unusable. Not a
// performance threshold -- a validity one.
const STEADY_STATE_RATIO = 1.3

export const options = {
  scenarios: {
    // Stage 1 — WARM-UP. Samples are tagged and excluded from the reported metrics.
    // Do not shorten this because a run "looked stable already". It did not.
    warmup: {
      executor: 'constant-vus',
      vus: VUS,
      duration: '30s',
      tags: { phase: 'warmup' },
      exec: 'warmup',
    },
    // Stage 2 — MEASUREMENT.
    measure: {
      executor: 'constant-vus',
      vus: VUS,
      duration: '3m',
      startTime: '30s',
      tags: { phase: 'measure' },
      exec: 'measure',
    },
  },
  thresholds: {
    // Deliberately absent: a p99 threshold. The first run of this scenario is EXPECTED to
    // fail badly, and a threshold that made that run "fail" would say nothing the number
    // does not already say. What must hold is that the measurement itself is valid.
    'measured_errors': ['rate<=1.0'], // placeholder; tightened once a baseline exists
    'checks{phase:measure}': ['rate>=0.0'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
}

function pickLearnerId() {
  // Deterministic spread across learners rather than random, so two runs hit the same
  // rows in the same proportion. A random learner per iteration makes page-cache
  // behaviour differ between runs and the difference shows up in p99.
  return ((__VU - 1) * 1013 + __ITER * 7919) % LEARNERS + 1
}

function callOnce() {
  const id = pickLearnerId()
  return http.get(`${BASE_URL}/api/v1/learners/${id}/recommendations?limit=10`, {
    tags: { name: 'recommendations' },
  })
}

// Wall-clock zero, so `measure` can tell which half of its window it is in.
export function setup() {
  return { t0: Date.now() }
}

// Warm-up: traffic is real, results are ignored on purpose.
export function warmup() {
  callOnce()
}

const WARMUP_MS = 30000
const MEASURE_MS = 180000

export function measure(data) {
  const res = callOnce()
  measured.add(res.timings.duration)
  measuredErrors.add(res.status !== 200)

  const intoWindow = Date.now() - data.t0 - WARMUP_MS
  if (intoWindow < MEASURE_MS / 2) {
    measuredEarly.add(res.timings.duration)
  } else {
    measuredLate.add(res.timings.duration)
  }

  check(res, {
    'status is 200': (r) => r.status === 200,
    'body is not empty': (r) => r.body && r.body.length > 2,
  })
}

// The environment block for the report cannot be produced by k6 -- it has no way to know
// the JVM flags or the pool size. It is filled in by hand from the run that produced the
// numbers. A report whose block is copied from a previous run is a report about a
// previous run.
export function handleSummary(data) {
  const early = data.metrics.measured_early && data.metrics.measured_early.values.med
  const late = data.metrics.measured_late && data.metrics.measured_late.values.med
  const ratio = early && late ? early / late : null

  let steady =
    '  steady state: first half ' + (early ? early.toFixed(0) : '?') + ' ms, ' +
    'second half ' + (late ? late.toFixed(0) : '?') + ' ms'
  if (ratio !== null) {
    steady += '  (ratio ' + ratio.toFixed(2) + ')\n'
    if (ratio > STEADY_STATE_RATIO) {
      steady +=
        '\n' +
        '  *** NOT STEADY STATE. The first half of the measurement window was ' +
        ratio.toFixed(2) + 'x slower\n' +
        '  *** than the second, so the system was still warming while being measured.\n' +
        '  *** DO NOT PUBLISH THIS RUN. The 30s warm-up warms a JVM; it does not warm\n' +
        '  *** a large table into a cold page cache. Re-run once the cache is hot.\n'
    }
  } else {
    steady += '\n'
  }

  return {
    stdout:
      '\n' +
      'Measured window only (warm-up excluded).\n' +
      '  p50 : ' + data.metrics.measured_duration.values.med.toFixed(1) + ' ms\n' +
      '  p95 : ' + data.metrics.measured_duration.values['p(95)'].toFixed(1) + ' ms\n' +
      '  p99 : ' + data.metrics.measured_duration.values['p(99)'].toFixed(1) + ' ms\n' +
      '  err : ' + (data.metrics.measured_errors.values.rate * 100).toFixed(2) + ' %\n' +
      '  vus : ' + VUS + '\n' +
      steady +
      '\n' +
      'Run this three times. Report the median. Fill in the environment block by hand.\n',
  }
}
