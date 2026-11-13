import { describe, expect, it } from 'vitest'
import { buildTrace, formatMs, latencyTone, serviceColor } from './trace.js'

const entry = (serviceName, message, timestamp, status = 'success') => ({
  traceId: 't-1',
  serviceName,
  message,
  status,
  timestamp,
})

// A complete 3-hop trace, deliberately given in arrival (not timestamp) order.
const fullTrace = [
  entry('api-gateway', 'Received order request', 1000),
  entry('inventory-service', 'Received inventory check request', 1030),
  entry('orders-service', 'Received order request', 1010),
  entry('inventory-service', 'Completed - inventory check finished', 1040),
  entry('orders-service', 'Completed - response received from Inventory', 1050),
  entry('api-gateway', 'Completed - response received from Orders', 1060),
]

describe('latencyTone', () => {
  it('uses <100 fast, 100..500 moderate, >500 slow', () => {
    expect(latencyTone(99)).toBe('fast')
    expect(latencyTone(100)).toBe('moderate')
    expect(latencyTone(500)).toBe('moderate')
    expect(latencyTone(501)).toBe('slow')
  })
})

describe('formatMs / serviceColor', () => {
  it('rounds to whole milliseconds', () => {
    expect(formatMs(12.6)).toBe('13ms')
  })
  it('falls back to a neutral color for unknown services', () => {
    expect(serviceColor('api-gateway')).toBe('#38bdf8')
    expect(serviceColor('unknown')).toBe('#94a3b8')
  })
})

describe('buildTrace', () => {
  it('sorts by timestamp and computes total duration and offsets', () => {
    const trace = buildTrace('t-1', fullTrace, 'live')
    expect(trace.total).toBe(60)
    expect(trace.events.map((e) => e.offset)).toEqual([0, 10, 30, 40, 50, 60])
    expect(trace.spans.map((s) => s.service)).toEqual([
      'api-gateway',
      'orders-service',
      'inventory-service',
    ])
  })

  it('pairs received/completed into spans with nesting depth', () => {
    const { spans } = buildTrace('t-1', fullTrace, 'live')
    const byName = Object.fromEntries(spans.map((s) => [s.service, s]))
    expect(byName['api-gateway']).toMatchObject({ startOffset: 0, endOffset: 60, duration: 60, depth: 0 })
    expect(byName['orders-service']).toMatchObject({ startOffset: 10, endOffset: 50, duration: 40, depth: 1 })
    expect(byName['inventory-service']).toMatchObject({ startOffset: 30, endOffset: 40, duration: 10, depth: 2 })
  })

  it('leaves a span open when a service never completed', () => {
    const partial = [
      entry('api-gateway', 'Received order request', 0),
      entry('orders-service', 'Received order request', 5),
    ]
    const { spans } = buildTrace('t-2', partial, 'live')
    expect(spans.find((s) => s.service === 'orders-service')).toMatchObject({ endOffset: null, duration: null })
  })

  it('marks a span failed when any of its entries is not success', () => {
    const failing = [
      entry('api-gateway', 'Received order request', 0),
      entry('api-gateway', 'Completed - error', 9, 'error'),
    ]
    expect(buildTrace('t-3', failing, 'live').spans[0].failed).toBe(true)
    expect(buildTrace('t-1', fullTrace, 'live').spans.every((s) => !s.failed)).toBe(true)
  })

  it('does not mutate its input', () => {
    const input = [...fullTrace]
    buildTrace('t-1', input, 'live')
    expect(input).toEqual(fullTrace)
  })
})
