// Demo-mode data: sample traces and a client-side trace generator.
// Shapes, service names and messages match what the real services ship to
// the aggregator (see the Java controllers); timings are modeled on the
// measured runs in the project README.

const HOPS = [
  ['api-gateway', 'Received order request'],
  ['orders-service', 'Received order request'],
  ['inventory-service', 'Received inventory check request'],
  ['inventory-service', 'Completed - inventory check finished'],
  ['orders-service', 'Completed - response received from Inventory'],
  ['api-gateway', 'Completed - response received from Orders'],
]

function entriesFromOffsets(traceId, startedAt, offsets) {
  return HOPS.map(([serviceName, message], i) => ({
    traceId,
    serviceName,
    message,
    status: 'success',
    timestamp: startedAt + offsets[i],
  }))
}

export const SAMPLE_TRACES = [
  // The fully broken-down 160ms native run from the README.
  {
    id: '3f8c2a71-9d4e-4b6a-a1c5-7e2f0b9d4c18',
    entries: entriesFromOffsets(
      '3f8c2a71-9d4e-4b6a-a1c5-7e2f0b9d4c18',
      1757894012337,
      [0, 6, 101, 135, 155, 160],
    ),
  },
  // A warm Docker Compose run (runs 2–7 measured 64–119ms).
  {
    id: 'b71e04d9-52c3-4f8a-9e6b-0d3a8c5f2e97',
    entries: entriesFromOffsets(
      'b71e04d9-52c3-4f8a-9e6b-0d3a8c5f2e97',
      1757959786204,
      [0, 4, 49, 66, 79, 86],
    ),
  },
]

function randomUUID() {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  // Fallback for non-secure contexts (e.g. testing over a LAN IP).
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = [...bytes].map((b) => b.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

const between = (min, max) => min + Math.floor(Math.random() * (max - min + 1))

export function generateTrace() {
  const traceId = randomUUID()
  const gatewayIn = 0
  const ordersIn = gatewayIn + between(3, 9)
  // Occasionally model a cold hop, like the first request after startup.
  const coldPenalty = Math.random() < 0.12 ? between(250, 650) : 0
  const inventoryIn = ordersIn + between(30, 80) + coldPenalty
  const inventoryOut = inventoryIn + between(6, 34)
  const ordersOut = inventoryOut + between(6, 20)
  const gatewayOut = ordersOut + between(3, 8)
  return {
    id: traceId,
    entries: entriesFromOffsets(traceId, Date.now(), [
      gatewayIn,
      ordersIn,
      inventoryIn,
      inventoryOut,
      ordersOut,
      gatewayOut,
    ]),
  }
}
