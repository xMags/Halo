import { serve } from '@hono/node-server'
import { createApp } from './app'
import { ensureAdminUser } from './bootstrap'
import { createDb } from './db'
import { loadEnv } from './env'

try {
  process.loadEnvFile('.env')
} catch {
  // No .env file — fine when the environment provides the vars directly.
}

const env = loadEnv()
const db = createDb(env.dbPath)
ensureAdminUser(db, env.adminPassword)
const app = createApp({
  db,
  jwtSecret: env.jwtSecret,
  corsOrigins: env.corsOrigins,
})

serve({ fetch: app.fetch, port: env.port }, (info) => {
  console.log(`halo api listening on :${info.port}`)
})
