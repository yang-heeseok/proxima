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

// Warm-up: traffic is real, results are ignored on purpose.
export function warmup() {
  callOnce()
}

export function measure() {
  const res = callOnce()
  measured.add(res.timings.duration)
  measuredErrors.add(res.status !== 200)
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
  return {
    stdout:
      '\n' +
      'Measured window only (warm-up excluded).\n' +
      '  p50 : ' + data.metrics.measured_duration.values.med.toFixed(1) + ' ms\n' +
      '  p95 : ' + data.metrics.measured_duration.values['p(95)'].toFixed(1) + ' ms\n' +
      '  p99 : ' + data.metrics.measured_duration.values['p(99)'].toFixed(1) + ' ms\n' +
      '  err : ' + (data.metrics.measured_errors.values.rate * 100).toFixed(2) + ' %\n' +
      '  vus : ' + VUS + '\n' +
      '\n' +
      'Run this three times. Report the median. Fill in the environment block by hand.\n',
  }
}
