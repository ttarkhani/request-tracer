import { useEffect, useState } from 'react'
import { formatMs, latencyTone, serviceColor } from '../lib/trace'

const prefersReducedMotion = () =>
  window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false

// Replays a trace in slow motion: returns the current position in trace time
// (ms). Real traces take ~100ms, far too fast to see, so time is stretched.
function usePlayhead(total, animate) {
  const [playing] = useState(() => animate && !prefersReducedMotion())
  const [playhead, setPlayhead] = useState(playing ? 0 : total)

  useEffect(() => {
    if (!playing) return
    const duration = Math.min(Math.max(total * 12, 1200), 2600)
    let frame
    let startedAt
    const step = (now) => {
      startedAt ??= now
      const progress = Math.min((now - startedAt) / duration, 1)
      setPlayhead(progress * total)
      if (progress < 1) frame = requestAnimationFrame(step)
    }
    frame = requestAnimationFrame(step)
    return () => cancelAnimationFrame(frame)
  }, [playing, total])

  return { playhead, playing }
}

export function LatencyBadge({ ms, pending = false }) {
  return (
    <span className={`latency-badge tone-${latencyTone(ms)}${pending ? ' is-pending' : ''}`}>
      {formatMs(ms)}
    </span>
  )
}

const SOURCE_LABELS = {
  sample: 'Sample trace',
  simulated: 'Simulated in browser',
  live: 'Live from aggregator',
}

function formatStartedAt(timestamp) {
  const date = new Date(timestamp)
  const day = date.toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })
  const time = date.toLocaleTimeString(undefined, { hour12: false })
  return `${day}, ${time}.${String(date.getMilliseconds()).padStart(3, '0')}`
}

function axisTicks(total) {
  return [0, 0.25, 0.5, 0.75, 1].map((f) => ({ pct: f * 100, label: formatMs(f * total) }))
}

export default function TraceView({ trace, animate }) {
  const { playhead, playing } = usePlayhead(trace.total, animate)
  const [hovered, setHovered] = useState(null)
  const total = trace.total || 1
  const elapsed = Math.min(playhead, trace.total)
  const inFlight = playing && playhead < trace.total

  return (
    <section className="trace-card" aria-label="Trace details">
      <header className="trace-summary">
        <div className="trace-summary-main">
          <span className="eyebrow">{SOURCE_LABELS[trace.source]}</span>
          <code className="trace-id" title="Trace ID">{trace.id}</code>
        </div>
        <dl className="trace-stats">
          <div>
            <dt>Total</dt>
            <dd><LatencyBadge ms={elapsed} pending={inFlight} /></dd>
          </div>
          <div>
            <dt>Services</dt>
            <dd>{trace.spans.length}</dd>
          </div>
          <div>
            <dt>Log entries</dt>
            <dd>{trace.events.length}</dd>
          </div>
          <div className="stat-started">
            <dt>Started</dt>
            <dd>{formatStartedAt(trace.startedAt)}</dd>
          </div>
        </dl>
      </header>

      <div className="waterfall" role="table" aria-label="Service spans">
        <div className="waterfall-axis" role="row" aria-hidden="true">
          <span className="axis-label">Service</span>
          <div className="axis-scale">
            {axisTicks(trace.total).map((tick) => (
              <span key={tick.pct} className="axis-tick" style={{ left: `${tick.pct}%` }}>
                {tick.label}
              </span>
            ))}
          </div>
          <span className="axis-label axis-label-end">Duration</span>
        </div>

        {trace.spans.map((span, i) => {
          const started = playhead >= span.startOffset
          const endOffset = span.endOffset ?? trace.total
          const visibleEnd = Math.min(endOffset, playhead)
          const running = started && (span.endOffset === null || playhead < span.endOffset)
          const width = Math.max(((visibleEnd - span.startOffset) / total) * 100, 0.6)
          const color = serviceColor(span.service)

          return (
            <div
              key={span.service}
              role="row"
              className={[
                'span-row',
                playing ? (started ? 'is-revealed' : 'is-pending') : 'is-staggered',
                hovered === span.service ? 'is-hovered' : '',
              ].join(' ')}
              style={{ '--i': i, '--depth': span.depth, '--service': color }}
              onMouseEnter={() => setHovered(span.service)}
              onMouseLeave={() => setHovered(null)}
            >
              <div className="span-label" role="cell">
                <span className="service-dot" />
                <span className="service-name">{span.service}</span>
              </div>
              <div className="span-track" role="cell">
                <div
                  className={`span-bar${running ? ' is-running' : ''}${span.endOffset === null ? ' is-open' : ''}`}
                  style={{ left: `${(span.startOffset / total) * 100}%`, width: `${width}%` }}
                  title={`${span.service}: +${formatMs(span.startOffset)} → +${formatMs(endOffset)}`}
                >
                  {span.eventOffsets
                    .filter((offset) => offset <= playhead)
                    .map((offset, j) => (
                      <span
                        key={j}
                        className="span-event"
                        style={{
                          left: `${((offset - span.startOffset) / Math.max(visibleEnd - span.startOffset, 1)) * 100}%`,
                        }}
                      />
                    ))}
                </div>
              </div>
              <div className="span-duration" role="cell">
                {span.duration === null ? (
                  <span className="latency-badge tone-open">open</span>
                ) : (
                  <LatencyBadge
                    ms={running ? visibleEnd - span.startOffset : span.duration}
                    pending={running}
                  />
                )}
              </div>
            </div>
          )
        })}
      </div>

      <div className="events">
        <h3 className="section-label">Log entries</h3>
        <ol className="event-list">
          {trace.events.map((event, i) => {
            const shown = !playing || playhead >= event.offset
            return (
              <li
                key={i}
                className={[
                  'event-row',
                  shown ? '' : 'is-pending',
                  hovered === event.serviceName ? 'is-hovered' : '',
                ].join(' ')}
                style={{ '--service': serviceColor(event.serviceName) }}
                onMouseEnter={() => setHovered(event.serviceName)}
                onMouseLeave={() => setHovered(null)}
              >
                <span className="event-offset">+{formatMs(event.offset)}</span>
                <span className="event-service">
                  <span className="service-dot" />
                  {event.serviceName}
                </span>
                <span className="event-message">{event.message}</span>
              </li>
            )
          })}
        </ol>
      </div>
    </section>
  )
}

export function TraceSkeleton() {
  return (
    <section className="trace-card is-skeleton" aria-busy="true" aria-label="Loading trace">
      <div className="trace-summary">
        <div className="trace-summary-main">
          <span className="shimmer shimmer-line" style={{ width: '6rem' }} />
          <span className="shimmer shimmer-line shimmer-lg" style={{ width: 'min(22rem, 80%)' }} />
        </div>
      </div>
      <div className="waterfall">
        {[0, 1, 2].map((i) => (
          <div key={i} className="span-row" style={{ '--depth': i }}>
            <span className="shimmer shimmer-line" style={{ width: '7.5rem' }} />
            <div className="span-track">
              <span
                className="shimmer shimmer-bar"
                style={{ left: `${[0, 4, 45][i]}%`, width: `${[100, 92, 30][i]}%` }}
              />
            </div>
            <span className="shimmer shimmer-line" style={{ width: '3rem' }} />
          </div>
        ))}
      </div>
    </section>
  )
}
