import { serve } from '@hono/node-server'
import { createApp } from './app'
import { ensureAdminUser } from './bootstrap'
import { createDb } from './db'
import { loadEnv } from './env'
import { createSafeFetch } from './safeFetch'

try {
  process.loadEnvFile('.env')
} catch {
  // No .env file — fine when the environment provides the vars directly.
}

const env = loadEnv()
const db = createDb(env.dbPath)

const app = createApp({
  db,
  auth:
    env.auth.mode === 'oidc'
      ? { mode: 'oidc', issuer: env.auth.issuer, clientId: env.auth.clientId, adminGroupId: env.auth.adminGroup }
      : { mode: 'local', jwtSecret: env.auth.jwtSecret },
  corsOrigins: env.corsOrigins,
  ...(env.addonEgress ? { safeFetch: createSafeFetch(env.addonEgress) } : {}),
})

if (env.auth.mode === 'local') ensureAdminUser(db, env.auth.adminPassword)

serve({ fetch: app.fetch, port: env.port }, (info) => {
  const egress = env.addonEgress ? `, egress via ${env.addonEgress.proxyUrl} for ${[...env.addonEgress.hosts].join(', ')}` : ''
  console.log(`halo api listening on :${info.port} (auth: ${env.auth.mode}${egress})`)
})
