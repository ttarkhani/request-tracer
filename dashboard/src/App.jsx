import { useState } from 'react'
import './App.css'

const AGGREGATOR_URL = 'http://localhost:8084'

const SERVICE_COLORS = {
  'api-gateway': '#61dafb',
  'orders-service': '#f7b731',
  'inventory-service': '#26de81',
}

function App() {
  const [traceId, setTraceId] = useState('')
  const [logs, setLogs] = useState([])
  const [error, setError] = useState(null)
  const [loading, setLoading] = useState(false)

  async function fetchTrace() {
    if (!traceId.trim()) return

    setLoading(true)
    setError(null)
    setLogs([])

    try {
      const response = await fetch(`${AGGREGATOR_URL}/traces/${traceId.trim()}`)
      if (!response.ok) {
        throw new Error(`Aggregator returned ${response.status}`)
      }
      const data = await response.json()

      if (data.length === 0) {
        setError('No logs found for that trace ID.')
      } else {
        setLogs(data)
      }
    } catch (err) {
      setError(`Could not reach the aggregator: ${err.message}`)
    } finally {
      setLoading(false)
    }
  }

  const startTime = logs.length > 0 ? logs[0].timestamp : 0
  const endTime = logs.length > 0 ? logs[logs.length - 1].timestamp : 0
  const totalDuration = endTime - startTime

  return (
    <div className="app">
      <h1>Request Tracer</h1>
      <p className="subtitle">Enter a trace ID to see its full path across services</p>

      <div className="search-bar">
        <input
          type="text"
          value={traceId}
          onChange={(e) => setTraceId(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && fetchTrace()}
          placeholder="Paste a trace ID..."
        />
        <button onClick={fetchTrace} disabled={loading}>
          {loading ? 'Loading...' : 'Trace'}
        </button>
      </div>

      {error && <p className="error">{error}</p>}

      {logs.length > 0 && (
        <div className="results">
          <div className="summary">
            <strong>Total duration:</strong> {totalDuration}ms &nbsp;|&nbsp;
            <strong> Log entries:</strong> {logs.length}
          </div>

          <div className="waterfall">
            {logs.map((log, i) => {
              const offset = log.timestamp - startTime
              const leftPercent = totalDuration > 0 ? (offset / totalDuration) * 100 : 0

              return (
                <div key={i} className="log-row">
                  <div className="log-label">
                    <span
                      className="service-dot"
                      style={{ background: SERVICE_COLORS[log.serviceName] || '#999' }}
                    />
                    {log.serviceName}
                  </div>
                  <div className="log-bar-track">
                    <div
                      className="log-bar-marker"
                      style={{
                        left: `${leftPercent}%`,
                        background: SERVICE_COLORS[log.serviceName] || '#999',
                      }}
                      title={`+${offset}ms`}
                    />
                  </div>
                  <div className="log-message">
                    {log.message} <span className="log-offset">+{offset}ms</span>
                  </div>
                </div>
              )
            })}
          </div>
        </div>
      )}
    </div>
  )
}

export default App
