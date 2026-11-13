// k6 load test for POST /orders/place (open model: arrival rate is fixed, so slow
// responses cannot hide behind fewer requests).
//
// Modes (env MODE):
//   steady (default): warm-up ramping 5/s -> RATE over WARMUP_S (tagged phase=warmup, excluded),
//                     then RATE for DURATION_S (tagged phase=main, reported).
//   ramp:             stepped ramp, STEP_S seconds at each rate in STEPS (tagged step=<rate>),
//                     to find the saturation knee.
//
// Env: BASE (default http://gateway:8080), RATE, WARMUP_S, DURATION_S, MAX_VUS, TIMEOUT,
//      SAMPLE_EVERY (log every Nth successful trace ID as "TRACE <id>" for loss checks),
//      OUT (summary JSON path), STEPS (comma list), STEP_S.
import http from 'k6/http'
import { check } from 'k6'
import { Counter } from 'k6/metrics'

const BASE = __ENV.BASE || 'http://gateway:8080'
const MODE = __ENV.MODE || 'steady'
const RATE = Number(__ENV.RATE || 50)
const WARMUP_S = Number(__ENV.WARMUP_S || 60)
const DURATION_S = Number(__ENV.DURATION_S || 60)
const MAX_VUS = Number(__ENV.MAX_VUS || 300)
const TIMEOUT = __ENV.TIMEOUT || '10s'
const SAMPLE_EVERY = Number(__ENV.SAMPLE_EVERY || 50)
const OUT = __ENV.OUT || '/out/summary.json'
const STEPS = (__ENV.STEPS || '25,50,100,150,200,300').split(',').map(Number)
const STEP_S = Number(__ENV.STEP_S || 30)

const okWithTraceId = new Counter('ok_with_trace_id')

function steadyScenarios() {
  const common = { executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', preAllocatedVUs: 50, maxVUs: MAX_VUS }
  return {
    // Warm-up ramps up from 5/s so a cold JVM is not hit with a backlog it then carries into the main window.
    warmup: {
      executor: 'ramping-arrival-rate', startRate: 5, timeUnit: '1s', preAllocatedVUs: 50, maxVUs: MAX_VUS,
      stages: [{ target: RATE, duration: `${WARMUP_S}s` }], tags: { phase: 'warmup' },
    },
    main: { ...common, duration: `${DURATION_S}s`, startTime: `${WARMUP_S}s`, tags: { phase: 'main' } },
  }
}

function rampScenarios() {
  const s = {}
  STEPS.forEach((rate, i) => {
    s[`step_${rate}`] = {
      executor: 'constant-arrival-rate', rate, timeUnit: '1s', duration: `${STEP_S}s`,
      startTime: `${i * STEP_S}s`, preAllocatedVUs: 50, maxVUs: MAX_VUS, tags: { step: String(rate) },
    }
  })
  return s
}

// Thresholds that always pass; they exist so the end-of-test summary includes per-tag sub-metrics.
function thresholds() {
  const t = {}
  const tags = MODE === 'ramp' ? STEPS.map((r) => `step:${r}`) : ['phase:main', 'phase:warmup']
  for (const tag of tags) {
    t[`http_req_duration{${tag}}`] = ['max>=0']
    t[`http_req_failed{${tag}}`] = ['rate>=0']
    t[`http_reqs{${tag}}`] = ['count>=0']
    t[`iterations{${tag}}`] = ['count>=0']
  }
  // dropped_iterations carries only the scenario tag, not custom tags.
  const scenarios = MODE === 'ramp' ? STEPS.map((r) => `step_${r}`) : ['main', 'warmup']
  for (const sc of scenarios) t[`dropped_iterations{scenario:${sc}}`] = ['count>=0']
  return t
}

export const options = {
  scenarios: MODE === 'ramp' ? rampScenarios() : steadyScenarios(),
  thresholds: thresholds(),
  summaryTrendStats: ['min', 'med', 'avg', 'p(90)', 'p(95)', 'p(99)', 'max', 'count'],
  discardResponseBodies: false,
}

const payload = JSON.stringify({ customerId: 'LOAD', items: 'sku-001,sku-002' })
const params = { headers: { 'Content-Type': 'application/json' }, timeout: TIMEOUT }

export default function () {
  const res = http.post(`${BASE}/orders/place`, payload, params)
  const traceId = res.headers['X-Trace-Id']
  const ok = check(res, { 'status 200': (r) => r.status === 200 })
  if (ok && traceId) {
    okWithTraceId.add(1)
    // Random ~1/SAMPLE_EVERY sample of trace IDs for the per-trace loss check.
    if (Math.floor(Math.random() * SAMPLE_EVERY) === 0) console.log(`TRACE ${traceId}`)
  }
}

export function handleSummary(data) {
  return { [OUT]: JSON.stringify(data, null, 2) }
}
