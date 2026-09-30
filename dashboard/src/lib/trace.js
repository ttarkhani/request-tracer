// Turns raw aggregator log entries into a renderable trace model.

export const SERVICE_COLORS = {
  'api-gateway': '#38bdf8',
  'orders-service': '#a78bfa',
  'inventory-service': '#f472b6',
}

export function serviceColor(serviceName) {
  return SERVICE_COLORS[serviceName] ?? '#94a3b8'
}

// Single source of truth for latency color coding.
export function latencyTone(ms) {
  if (ms < 100) return 'fast'
  if (ms <= 500) return 'moderate'
  return 'slow'
}

export function formatMs(ms) {
  return `${Math.round(ms)}ms`
}

// Pairs each service's "Received" / "Completed" entries into a span.
// A service with no completion entry yields an open span (end = null).
export function buildTrace(id, rawEntries, source) {
  const entries = [...rawEntries].sort((a, b) => a.timestamp - b.timestamp)
  const start = entries[0]?.timestamp ?? 0
  const end = entries.at(-1)?.timestamp ?? 0

  const byService = new Map()
  for (const entry of entries) {
    if (!byService.has(entry.serviceName)) byService.set(entry.serviceName, [])
    byService.get(entry.serviceName).push(entry)
  }

  const spans = [...byService].map(([service, events]) => {
    const received = events.find((e) => e.message?.startsWith('Received'))
    const completed = events.findLast((e) => e.message?.startsWith('Completed'))
    const spanStart = (received ?? events[0]).timestamp
    const spanEnd = completed?.timestamp ?? (events.length > 1 ? events.at(-1).timestamp : null)
    return {
      service,
      startOffset: spanStart - start,
      endOffset: spanEnd === null ? null : spanEnd - start,
      duration: spanEnd === null ? null : spanEnd - spanStart,
      failed: events.some((e) => e.status && e.status !== 'success'),
      eventOffsets: events.map((e) => e.timestamp - start),
    }
  })

  // Nesting depth = how many other spans fully enclose this one.
  for (const span of spans) {
    const spanEnd = span.endOffset ?? end - start
    span.depth = spans.filter(
      (other) =>
        other !== span &&
        other.startOffset <= span.startOffset &&
        (other.endOffset ?? end - start) >= spanEnd &&
        spans.indexOf(other) < spans.indexOf(span),
    ).length
  }

  return {
    id,
    source,
    startedAt: start,
    total: end - start,
    spans,
    events: entries.map((e) => ({ ...e, offset: e.timestamp - start })),
  }
}
