# Request Tracer dashboard

React + Vite single-page app that renders one trace (the six log entries the aggregator stores per request) as a waterfall.

- **Demo mode** (default for production builds with no env vars, e.g. the Vercel deploy): traces are generated in the browser. No backend is contacted; nothing shown is live tracing.
- **Live mode**: set `VITE_AGGREGATOR_URL` (and optionally `VITE_GATEWAY_URL` so "Simulate a request" places a real order and reads the trace ID from the gateway's `X-Trace-Id` response header). See `.env.example`. `npm run dev` falls back to `http://localhost:8084` for the aggregator.

```bash
npm ci
npm run dev     # http://localhost:5173
npm test        # Vitest: pure trace-building logic in src/lib/trace.js
npm run lint
npm run build
```

Under `docker compose up` (repo root) the dashboard is served by nginx on http://localhost:3000.
