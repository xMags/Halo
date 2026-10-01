import { isIP } from 'node:net'
import { join } from 'node:path'
import type { AddonEgress } from './safeFetch'

export type AuthEnv =
  | { mode: 'oidc'; issuer: string; clientId: string; adminGroup: string }
  | { mode: 'local'; jwtSecret: string; adminPassword: string }

export interface Env {
  auth: AuthEnv
  dbPath: string
  port: number
  corsOrigins: string[]
  /** Absent unless both ADDON_EGRESS_* settings are given. */
  addonEgress?: AddonEgress
}

/** Fail fast on missing or half-configured auth — the server must not boot into an ambiguous mode. */
export function loadEnv(): Env {
  const addonEgress = loadAddonEgress()
  return {
    auth: loadAuthEnv(),
    dbPath: join(process.env.DATA_DIR ?? './data', 'halo.sqlite'),
    port: Number(process.env.PORT ?? 8787),
    corsOrigins: [
      'http://localhost:5173',
      'https://halo.ditto.moe',
      ...(process.env.CORS_ORIGINS?.split(',').map((s) => s.trim()).filter(Boolean) ?? []),
    ],
    ...(addonEgress ? { addonEgress } : {}),
  }
}

const HOSTNAME = /^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$/

/** A dotted, lowercase DNS name; never an IP literal. */
export function isHostname(value: string): boolean {
  return isIP(value) === 0 && HOSTNAME.test(value)
}

/**
 * The optional egress route for addon hosts that block this server's address.
 * Both settings or neither: a proxy with no hosts, or hosts with no proxy, is
 * a typo that would otherwise boot silently into the direct route.
 */
function loadAddonEgress(): AddonEgress | undefined {
  const rawProxy = process.env.ADDON_EGRESS_PROXY?.trim()
  const hosts = (process.env.ADDON_EGRESS_HOSTS ?? '')
    .split(',')
    .map((host) => host.trim().toLowerCase())
    .filter(Boolean)
  if (!rawProxy && hosts.length === 0) return undefined
  if (!rawProxy || hosts.length === 0) {
    throw new Error('ADDON_EGRESS_PROXY and ADDON_EGRESS_HOSTS go together: set both or neither (see .env.example)')
  }

  let url: URL
  try {
    url = new URL(rawProxy)
  } catch {
    throw new Error(`ADDON_EGRESS_PROXY is not a valid URL: ${rawProxy}`)
  }
  // Plain http on purpose: the proxy only tunnels, so TLS stays end to end.
  if (url.protocol !== 'http:' || !url.port) {
    throw new Error('ADDON_EGRESS_PROXY must be http://host:port')
  }
  if (url.username || url.password || url.pathname !== '/' || url.search || url.hash) {
    throw new Error('ADDON_EGRESS_PROXY must be just http://host:port, with no credentials, path or query')
  }

  const invalid = hosts.find((host) => !isHostname(host))
  if (invalid) throw new Error(`ADDON_EGRESS_HOSTS must list hostnames only (got "${invalid}")`)
  return { proxyUrl: url.origin, hosts: new Set(hosts) }
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
