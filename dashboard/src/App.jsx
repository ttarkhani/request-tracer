import { useEffect, useRef, useState } from 'react'
import './App.css'
import TraceView, { LatencyBadge, TraceSkeleton } from './components/TraceView'
import {
  AGGREGATOR_URL,
  GATEWAY_URL,
  fetchTrace,
  fetchTraceWhenReady,
  placeOrder,
  probeAggregator,
} from './lib/api'
import { SAMPLE_TRACES, generateTrace } from './lib/demoData'
import { buildTrace } from './lib/trace'

const REPO_URL = 'https://github.com/ttarkhani/request-tracer'
const MAX_HISTORY = 8
const INITIAL_TRACES = SAMPLE_TRACES.map((t) => buildTrace(t.id, t.entries, 'sample'))

function GitHubIcon() {
  return (
    <svg viewBox="0 0 16 16" width="16" height="16" aria-hidden="true" fill="currentColor">
      <path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0016 8c0-4.42-3.58-8-8-8z" />
    </svg>
  )
}

function ModeBadge({ mode }) {
  const labels = {
    checking: ['Connecting', 'Checking for a running log aggregator…'],
    live: ['Live', `Connected to the log aggregator at ${AGGREGATOR_URL}`],
    demo: ['Demo', 'No backend connected — traces are generated in your browser'],
  }
  const [label, title] = labels[mode]
  return (
    <span className={`mode-badge mode-${mode}`} title={title}>
      <span className="mode-dot" />
      {label}
    </span>
  )
}

function StatePanel({ icon, title, children, actions }) {
  return (
    <section className="state-panel" role="status">
      <div className="state-icon" aria-hidden="true">{icon}</div>
      <h2>{title}</h2>
      <div className="state-body">{children}</div>
      {actions && <div className="state-actions">{actions}</div>}
    </section>
  )
}

function App() {
  const [mode, setMode] = useState(AGGREGATOR_URL ? 'checking' : 'demo')
  const [traces, setTraces] = useState(INITIAL_TRACES)
  const [selectedId, setSelectedId] = useState(INITIAL_TRACES[0].id)
  const [animateId, setAnimateId] = useState(null)
  const [replayCount, setReplayCount] = useState(0)
  const [view, setView] = useState({ status: 'trace' })
  const [query, setQuery] = useState('')
  const [simulating, setSimulating] = useState(false)
  const gatewayUsable = useRef(Boolean(GATEWAY_URL))

  useEffect(() => {
    if (!AGGREGATOR_URL) return
    let cancelled = false
    probeAggregator().then((ok) => {
      if (!cancelled) setMode(ok ? 'live' : 'demo')
    })
    return () => {
      cancelled = true
    }
  }, [])

  const selected = traces.find((t) => t.id === selectedId) ?? traces[0]

  function showTrace(trace, animate) {
    setTraces((prev) => [trace, ...prev.filter((t) => t.id !== trace.id)].slice(0, MAX_HISTORY))
    setSelectedId(trace.id)
    setAnimateId(animate ? trace.id : null)
    setView({ status: 'trace' })
  }

  function selectTrace(id) {
    setSelectedId(id)
    setAnimateId(null)
    setView({ status: 'trace' })
  }

  function replay() {
    setAnimateId(selected.id)
    setReplayCount((n) => n + 1)
  }

  async function simulate() {
    if (mode === 'live' && gatewayUsable.current) {
      setSimulating(true)
      try {
        const traceId = await placeOrder()
        const entries = traceId ? await fetchTraceWhenReady(traceId) : []
        if (entries.length > 0) {
          showTrace(buildTrace(traceId, entries, 'live'), true)
          return
        }
      } catch {
        // Fall through to a simulated trace below.
      } finally {
        setSimulating(false)
      }
      // Don't retry a gateway that can't hand us a trace ID this session.
      gatewayUsable.current = false
    }
    const generated = generateTrace()
    showTrace(buildTrace(generated.id, generated.entries, 'simulated'), true)
  }

  async function lookup(event) {
    event.preventDefault()
    const id = query.trim()
    if (!id) return

    const known = traces.find((t) => t.id === id)
    if (known) {
      selectTrace(known.id)
      return
    }
    if (mode !== 'live') {
      setView({ status: 'notfound', id })
      return
    }

    setView({ status: 'loading' })
    try {
      const entries = await fetchTrace(id)
      if (entries.length === 0) setView({ status: 'notfound', id })
      else showTrace(buildTrace(id, entries, 'live'), false)
    } catch {
      setView({ status: 'error', id })
    }
  }

  const backToTrace = (
    <button type="button" className="btn btn-ghost" onClick={() => setView({ status: 'trace' })}>
      Back to last trace
    </button>
  )
  const simulateAgain = (
    <button type="button" className="btn btn-primary" onClick={simulate}>
      Simulate a request
    </button>
  )

  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">
          <img src="/favicon.svg" alt="" width="28" height="28" />
          <span>Request Tracer</span>
          <ModeBadge mode={mode} />
        </div>
        <a className="repo-link" href={REPO_URL} target="_blank" rel="noreferrer">
          <GitHubIcon />
          <span>Source</span>
        </a>
      </header>

      <section className="hero">
        <h1>Follow one request across three microservices.</h1>
        <p className="lede">
          A from-scratch distributed tracer: the API gateway stamps each request with a
          correlation ID, every Spring Boot service forwards it and ships timestamped logs to a
          central aggregator, and this dashboard reassembles them into a waterfall.
        </p>
        {mode === 'demo' && (
          <p className="demo-note">
            You're viewing demo mode — no backend is connected, so traces are generated in your
            browser using the same shape and timings measured on the real system.{' '}
            <a href={REPO_URL} target="_blank" rel="noreferrer">
              Run it locally with Docker Compose
            </a>
            .
          </p>
        )}

        <div className="controls">
          <button type="button" className="btn btn-primary btn-lg" onClick={simulate} disabled={simulating}>
            {simulating ? (
              <>
                <span className="spinner" aria-hidden="true" /> Tracing…
              </>
            ) : (
              <>
                <svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true" fill="currentColor">
                  <path d="M4 2.5v11a.5.5 0 0 0 .77.42l8.5-5.5a.5.5 0 0 0 0-.84l-8.5-5.5A.5.5 0 0 0 4 2.5z" />
                </svg>
                Simulate a request
              </>
            )}
          </button>
          <form className="lookup" onSubmit={lookup} role="search">
            <label htmlFor="trace-id" className="visually-hidden">Trace ID</label>
            <input
              id="trace-id"
              type="text"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Look up a trace ID…"
              autoComplete="off"
              spellCheck="false"
            />
            <button type="submit" className="btn btn-secondary" disabled={view.status === 'loading'}>
              Trace
            </button>
          </form>
        </div>
      </section>

      <nav className="history" aria-label="Recent traces">
        <span className="section-label">Recent traces</span>
        <ul>
          {traces.map((trace) => (
            <li key={trace.id}>
              <button
                type="button"
                className={`history-chip${trace.id === selected.id && view.status === 'trace' ? ' is-active' : ''}`}
                onClick={() => selectTrace(trace.id)}
                title={trace.id}
              >
                <code>{trace.id.slice(0, 8)}</code>
                <LatencyBadge ms={trace.total} />
                <span className="history-source">{trace.source}</span>
              </button>
            </li>
          ))}
        </ul>
      </nav>

      <main className="stage">
        {view.status === 'trace' && (
          <>
            <TraceView
              key={`${selected.id}-${replayCount}`}
              trace={selected}
              animate={animateId === selected.id}
            />
            <div className="stage-footer">
              <span className="legend">
                <span className="latency-badge tone-fast">&lt;100ms</span>
                <span className="latency-badge tone-moderate">100–500ms</span>
                <span className="latency-badge tone-slow">&gt;500ms</span>
              </span>
              <button type="button" className="btn btn-link" onClick={replay}>
                ↻ Replay
              </button>
            </div>
          </>
        )}

        {view.status === 'loading' && <TraceSkeleton />}

        {view.status === 'notfound' && (
          <StatePanel
            icon="∅"
            title="No trace with that ID"
            actions={<>{simulateAgain}{backToTrace}</>}
          >
            <p>
              Nothing was recorded for <code>{view.id}</code>.
            </p>
            <p>
              {mode === 'live'
                ? 'The aggregator keeps traces in memory, so IDs from before its last restart are gone. Double-check the ID or trace a fresh request.'
                : 'In demo mode there is no backend to query — only the sample traces and ones you simulate in this session can be looked up.'}
            </p>
          </StatePanel>
        )}

        {view.status === 'error' && (
          <StatePanel
            icon="!"
            title="Couldn't reach the log aggregator"
            actions={<>{simulateAgain}{backToTrace}</>}
          >
            <p>
              The lookup for <code>{view.id}</code> timed out or was refused. The aggregator may
              have stopped — you can still explore with a simulated request.
            </p>
          </StatePanel>
        )}
      </main>

      <footer className="footer">
        <span>
          Spring Boot · React · Docker Compose — correlation IDs propagated via{' '}
          <code>X-Trace-Id</code>
        </span>
        <a href={REPO_URL} target="_blank" rel="noreferrer">
          README, measured latencies &amp; source on GitHub →
        </a>
      </footer>
    </div>
  )
}

export default App
