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
import crypto from 'k6/crypto'
import { check } from 'k6'
import { Trend, Rate } from 'k6/metrics'

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080'
const VUS = parseInt(__ENV.VUS || '200', 10)
const LEARNERS = parseInt(__ENV.LEARNERS || '1000', 10)

// THE HARNESS HAD TO LEARN TO AUTHENTICATE, AND FINDING THAT OUT IS WHY R15 EXISTS.
//
//   R4's numbers were taken before T9 put a token filter in front of /api/v1. This script
//   sent no Authorization header, so every request would now be answered 401 -- and the
//   threshold below permitted an error rate of 1.0, so the run would have finished, printed
//   a p99, and failed nothing. The number would have been the latency of being refused.
//
//   The signature must match RequestToken exactly: HMAC-SHA256 over `<sub>.<iat>.<exp>`,
//   base64url with no padding. k6's 'base64rawurl' is Java's
//   Base64.getUrlEncoder().withoutPadding().
const TOKEN_SECRET = __ENV.PROXIMA_TOKEN_SECRET
if (!TOKEN_SECRET) {
  throw new Error(
    'PROXIMA_TOKEN_SECRET is not set. Without it every request is 401 and the run measures ' +
      'the cost of a refusal. See docs/reports/R15.',
  )
}

// Measured separately from the built-in http_req_duration so that warm-up traffic,
// which also hits http_req_duration, cannot contaminate the reported number.
const measured = new Trend('measured_duration', true)
const measuredErrors = new Rate('measured_errors')

// HOW MANY RESPONSES ACTUALLY CARRIED A RECOMMENDATION.
//
//   Reported, and deliberately NOT thresholded. `body is not empty` used to be a `check`,
//   which made it a pass/fail assertion about something the domain does not guarantee: the
//   rule only yields items for a learner who has an unmastered concept whose prerequisites
//   are all mastered, and on this dataset that is 210 learners in 1,000.
//
//   So an empty body is not a defect. Measuring the endpoint without knowing how often it
//   happens IS one -- 79 % of the traffic in a run like this exercises none of the work the
//   report is about, and R4 published a p99 without saying so. The number belongs in the
//   summary, next to the percentiles it explains.
const measuredNonEmpty = new Rate('measured_nonempty')

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
    //
    // THE ERROR THRESHOLD WAS A PLACEHOLDER — `rate<=1.0`, permitting every request to fail
    // — with a comment saying it would be tightened "once a baseline exists". The baseline
    // has existed since R4, which measured 0.00 % across all three arms, and the placeholder
    // stayed. It is what would have let a run of nothing but 401s publish a p99.
    //
    // This script's own header says a threshold that only warns is a comment. So did that
    // line, for four days.
    'measured_errors': ['rate<0.01'],
    'checks{phase:measure}': ['rate>0.99'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
}

function pickLearnerId() {
  // Deterministic spread across learners rather than random, so two runs hit the same
  // rows in the same proportion. A random learner per iteration makes page-cache
  // behaviour differ between runs and the difference shows up in p99.
  return ((__VU - 1) * 1013 + __ITER * 7919) % LEARNERS + 1
}

function callOnce(tokens) {
  const id = pickLearnerId()
  return http.get(`${BASE_URL}/api/v1/learners/${id}/recommendations?limit=10`, {
    headers: { Authorization: `Bearer ${tokens[id - 1]}` },
    tags: { name: 'recommendations' },
  })
}

/**
 * Wall-clock zero, so `measure` can tell which half of its window it is in — and one token
 * per learner.
 *
 * **Signed here rather than per iteration.** An HMAC in the request loop is client-side work
 * inside the thing being measured, and this script exists to measure a server. One hour of
 * validity against a 3m30s run leaves no chance of an expiry landing mid-window and turning
 * a latency measurement into an authentication measurement.
 */
export function setup() {
  const iat = Math.floor(Date.now() / 1000)
  const exp = iat + 3600
  const tokens = []
  for (let id = 1; id <= LEARNERS; id++) {
    const body = `${id}.${iat}.${exp}`
    tokens.push(`${body}.${crypto.hmac('sha256', TOKEN_SECRET, body, 'base64rawurl')}`)
  }
  return { t0: Date.now(), tokens }
}

// Warm-up: traffic is real, results are ignored on purpose.
export function warmup(data) {
  callOnce(data.tokens)
}

const WARMUP_MS = 30000
const MEASURE_MS = 180000

export function measure(data) {
  const res = callOnce(data.tokens)
  measured.add(res.timings.duration)
  measuredErrors.add(res.status !== 200)

  const intoWindow = Date.now() - data.t0 - WARMUP_MS
  if (intoWindow < MEASURE_MS / 2) {
    measuredEarly.add(res.timings.duration)
  } else {
    measuredLate.add(res.timings.duration)
  }

  measuredNonEmpty.add(res.status === 200 && res.body && res.body.length > 2)

  // Only the status is asserted. Emptiness is a property of the dataset, not of the system
  // under test, and asserting it would fail every honest run on this seed.
  check(res, { 'status is 200': (r) => r.status === 200 })
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
      '  responses carrying a recommendation : ' +
      (data.metrics.measured_nonempty.values.rate * 100).toFixed(1) + ' %\n' +
      '    (the rest are 200 with an empty list -- a property of the seed, not a failure.\n' +
      '     Percentiles above are over BOTH, so a low figure here means most of the\n' +
      '     measured traffic did none of the work the report is about.)\n' +
      steady +
      '\n' +
      'Run this three times. Report the median. Fill in the environment block by hand.\n',
  }
}
