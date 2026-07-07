import { join } from 'node:path'

export interface Env {
  adminPassword: string
  jwtSecret: string
  dbPath: string
  port: number
  corsOrigins: string[]
}

/** Fail fast on missing secrets — a half-configured auth setup must not boot. */
export function loadEnv(): Env {
  const adminPassword = process.env.ADMIN_PASSWORD
  const jwtSecret = process.env.JWT_SECRET
  if (!adminPassword || !jwtSecret) {
    throw new Error('ADMIN_PASSWORD and JWT_SECRET are required (see .env.example)')
  }
  if (adminPassword === 'change-me' || jwtSecret === 'change-me-too') {
    throw new Error('ADMIN_PASSWORD / JWT_SECRET still have example values — set real ones')
  }
  return {
    adminPassword,
    jwtSecret,
    dbPath: join(process.env.DATA_DIR ?? './data', 'halo.sqlite'),
    port: Number(process.env.PORT ?? 8787),
    corsOrigins: [
      'http://localhost:5173',
      'https://halo.ditto.moe',
      ...(process.env.CORS_ORIGINS?.split(',').map((s) => s.trim()).filter(Boolean) ?? []),
    ],
  }
}
