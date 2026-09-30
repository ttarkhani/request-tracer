// Backend configuration and network calls.
//
// VITE_AGGREGATOR_URL — log aggregator base URL. Falls back to localhost:8084
//   in `npm run dev` only. Production builds without it never touch the
//   network, so a cold visitor on Vercel gets demo mode with no failed
//   requests, no console noise, and no browser "local network access" prompt.
// VITE_GATEWAY_URL — API gateway base URL. Only when this is set (and the
//   aggregator is reachable) does "Simulate a request" hit the real system.

const env = import.meta.env

function cleanUrl(value) {
  const trimmed = value?.trim().replace(/\/+$/, '')
  return trimmed || null
}

export const AGGREGATOR_URL =
  cleanUrl(env.VITE_AGGREGATOR_URL) ?? (env.DEV ? 'http://localhost:8084' : null)

export const GATEWAY_URL = cleanUrl(env.VITE_GATEWAY_URL)

async function fetchWithTimeout(url, options = {}, timeoutMs = 2000) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  try {
    return await fetch(url, { ...options, signal: controller.signal })
  } finally {
    clearTimeout(timer)
  }
}

// Resolves true only if the aggregator answers with the expected JSON shape.
// The aggregator returns [] for unknown trace IDs, which makes a cheap probe.
export async function probeAggregator() {
  if (!AGGREGATOR_URL) return false
  try {
    const res = await fetchWithTimeout(`${AGGREGATOR_URL}/traces/connectivity-check`)
    if (!res.ok) return false
    return Array.isArray(await res.json())
  } catch {
    return false
  }
}

// Returns the trace's log entries ([] when unknown). Throws if unreachable.
export async function fetchTrace(traceId) {
  const res = await fetchWithTimeout(
    `${AGGREGATOR_URL}/traces/${encodeURIComponent(traceId)}`,
    {},
    4000,
  )
  if (!res.ok) throw new Error(`Aggregator returned ${res.status}`)
  const data = await res.json()
  return Array.isArray(data) ? data : []
}

// Places a real order through the gateway and returns its trace ID, or null
// if the gateway is unreachable or doesn't expose the ID to the browser
// (it needs CORS plus an exposed X-Trace-Id header or a `traceId` body field).
export async function placeOrder() {
  if (!GATEWAY_URL) return null
  try {
    // Generous timeout: the first request after a cold start took 1565ms.
    const res = await fetchWithTimeout(
      `${GATEWAY_URL}/orders/place`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ customerId: 'CUST-DEMO', items: 'sku-001,sku-002' }),
      },
      5000,
    )
    if (!res.ok) return null
    const fromHeader = res.headers.get('X-Trace-Id')
    if (fromHeader) return fromHeader
    const body = await res.json().catch(() => null)
    return typeof body?.traceId === 'string' ? body.traceId : null
  } catch {
    return null
  }
}

// Logs are shipped synchronously before the gateway responds, but allow a
// couple of short retries in case the aggregator is still storing them.
export async function fetchTraceWhenReady(traceId, attempts = 4) {
  for (let i = 0; i < attempts; i++) {
    const entries = await fetchTrace(traceId)
    if (entries.length >= 6 || (i === attempts - 1 && entries.length > 0)) return entries
    await new Promise((resolve) => setTimeout(resolve, 250))
  }
  return []
}
