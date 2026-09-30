import react from '@vitejs/plugin-react'
import { defineConfig, loadEnv } from 'vite'

// Open Graph tags need absolute URLs. Uses VITE_SITE_URL if set, otherwise
// Vercel's production domain (provided automatically at build time).
function siteUrlPlugin(env) {
  const siteUrl = (
    env.VITE_SITE_URL ||
    (env.VERCEL_PROJECT_PRODUCTION_URL ? `https://${env.VERCEL_PROJECT_PRODUCTION_URL}` : '')
  ).replace(/\/+$/, '')

  return {
    name: 'site-url',
    transformIndexHtml: (html) => html.replaceAll('__SITE_URL__', siteUrl),
  }
}

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  return {
    plugins: [react(), siteUrlPlugin(env)],
  }
})
