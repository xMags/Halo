import { join } from 'node:path'

export type AuthEnv =
  | { mode: 'oidc'; issuer: string; clientId: string; adminGroup: string }
  | { mode: 'local'; jwtSecret: string; adminPassword: string }

export interface Env {
  auth: AuthEnv
  dbPath: string
  port: number
  corsOrigins: string[]
}

/** Fail fast on missing or half-configured auth — the server must not boot into an ambiguous mode. */
export function loadEnv(): Env {
  return {
    auth: loadAuthEnv(),
    dbPath: join(process.env.DATA_DIR ?? './data', 'halo.sqlite'),
    port: Number(process.env.PORT ?? 8787),
    corsOrigins: [
      'http://localhost:5173',
      'https://halo.ditto.moe',
      ...(process.env.CORS_ORIGINS?.split(',').map((s) => s.trim()).filter(Boolean) ?? []),
    ],
  }
}

function loadAuthEnv(): AuthEnv {
  const mode = process.env.AUTH_MODE
  if (mode === 'oidc') return loadOidcEnv()
  if (mode === 'local') return loadLocalEnv()
  throw new Error(`AUTH_MODE must be "oidc" or "local" (got ${mode ? `"${mode}"` : 'nothing'}; see .env.example)`)
}

function loadOidcEnv(): AuthEnv {
  const issuer = process.env.OIDC_ISSUER
  const clientId = process.env.OIDC_CLIENT_ID
  const adminGroup = process.env.OIDC_ADMIN_GROUP
  if (!issuer || !clientId || !adminGroup) {
    throw new Error('AUTH_MODE=oidc requires OIDC_ISSUER, OIDC_CLIENT_ID and OIDC_ADMIN_GROUP (see .env.example)')
  }
  try {
    new URL(issuer)
  } catch {
    throw new Error(`OIDC_ISSUER is not a valid URL: ${issuer}`)
  }
  return {
    mode: 'oidc',
    // jose compares `iss` exactly; Authentik issuers always end with a slash.
    issuer: issuer.endsWith('/') ? issuer : `${issuer}/`,
    clientId,
    adminGroup,
  }
}

function loadLocalEnv(): AuthEnv {
  const jwtSecret = process.env.JWT_SECRET
  const adminPassword = process.env.ADMIN_PASSWORD
  if (!jwtSecret || !adminPassword) {
    throw new Error('AUTH_MODE=local requires JWT_SECRET and ADMIN_PASSWORD (see .env.example)')
  }
  if (adminPassword === 'change-me' || jwtSecret === 'change-me-too') {
    throw new Error('ADMIN_PASSWORD / JWT_SECRET still have example values — set real ones')
  }
  if (jwtSecret.length < 32) {
    throw new Error('JWT_SECRET must be at least 32 characters — HS256 is only as strong as this secret')
  }
  return { mode: 'local', jwtSecret, adminPassword }
}
