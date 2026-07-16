import { randomBytes, scryptSync, timingSafeEqual } from 'node:crypto'
import type { Context, MiddlewareHandler } from 'hono'
import { sign, verify } from 'hono/jwt'
import { eq } from 'drizzle-orm'
import { createRemoteJWKSet, jwtVerify, type JWTPayload, type JWTVerifyGetKey } from 'jose'
import type { Db } from './db'
import { users } from './schema'

/**
 * OIDC resource-server config. Tokens are issued by the identity provider
 * (Authentik); this API only verifies them and never mints its own.
 */
export interface OidcConfig {
  /** Issuer URL, trailing slash included, e.g. https://authentik.example.com/application/o/halo/ */
  issuer: string
  /** OAuth client id — must match the token's `aud` so tokens minted for other ditto apps are rejected. */
  clientId: string
  /** IdP group UUID whose members are admins. UUIDs survive group renames; names don't. */
  adminGroupId: string
  /** Key source override for tests. Defaults to the issuer's remote JWKS. */
  getKey?: JWTVerifyGetKey
}

/** Local-accounts config. The API mints and verifies its own HS256 session JWTs. */
export interface LocalConfig {
  jwtSecret: string
}

/**
 * Which auth strategy a deployment runs. Exactly one — mixing issuers in a
 * single deployment would double the attack surface and split admin semantics
 * (stored is_admin vs computed-from-groups).
 */
export type AuthModeConfig = ({ mode: 'oidc' } & OidcConfig) | ({ mode: 'local' } & LocalConfig)

/** Scopes clients must request in OIDC mode; served to the app via GET /auth/config. */
export const OIDC_SCOPES = ['openid', 'profile', 'email', 'offline_access', 'groups']

/** Sliding session: each token lives this long, refreshable while still valid. */
export const SESSION_DAYS = 30
/** Hard ceiling from the original login; a stolen token can't be renewed forever. */
export const SESSION_ABSOLUTE_DAYS = 90

// scrypt parameters. Cost is fixed here but encoded into every hash so a future
// bump stays verifiable against old hashes.
const SCRYPT_N = 16384
const SCRYPT_R = 8
const SCRYPT_P = 1
const KEY_LEN = 32
const SALT_LEN = 16

/** Derives a `scrypt$N$r$p$salt$hash` string with a fresh random salt. */
export function hashPassword(password: string): string {
  const salt = randomBytes(SALT_LEN)
  const hash = scryptSync(password, salt, KEY_LEN, { N: SCRYPT_N, r: SCRYPT_R, p: SCRYPT_P })
  return `scrypt$${SCRYPT_N}$${SCRYPT_R}$${SCRYPT_P}$${salt.toString('base64')}$${hash.toString('base64')}`
}

/** Constant-time verify against a stored `scrypt$…` string. False on any parse error. */
export function verifyPassword(password: string, stored: string): boolean {
  const parts = stored.split('$')
  if (parts.length !== 6 || parts[0] !== 'scrypt') return false
  const N = Number(parts[1])
  const r = Number(parts[2])
  const p = Number(parts[3])
  if (!Number.isInteger(N) || !Number.isInteger(r) || !Number.isInteger(p)) return false
  let salt: Buffer
  let expected: Buffer
  try {
    salt = Buffer.from(parts[4]!, 'base64')
    expected = Buffer.from(parts[5]!, 'base64')
  } catch {
    return false
  }
  const derived = scryptSync(password, salt, expected.length, { N, r, p })
  return derived.length === expected.length && timingSafeEqual(derived, expected)
}

export interface IssuedToken {
  token: string
  /** Epoch ms when the token expires — clients schedule their refresh off this. */
  expiresAt: number
}

/**
 * Mints a local session JWT. `authTime` (epoch seconds) is the original login
 * moment; refreshes carry it forward unchanged so the absolute session cap
 * holds across any number of renewals.
 */
export async function issueLocalToken(jwtSecret: string, userId: string, authTime: number): Promise<IssuedToken> {
  const nowSec = Math.floor(Date.now() / 1000)
  const expSec = nowSec + SESSION_DAYS * 86400
  const token = await sign({ sub: userId, auth_time: authTime, iat: nowSec, exp: expSec }, jwtSecret)
  return { token, expiresAt: expSec * 1000 }
}

/** Claims of a verified local session token, for the refresh endpoint. */
export interface LocalTokenClaims {
  sub: string
  authTime: number
}

/** User attached to the request context after authentication. Never includes the hash. */
export interface AuthUser {
  id: string
  username: string
  isAdmin: boolean
  createdAt: number
}

export interface AuthVariables {
  user: AuthUser
  /** Set in local mode only; the refresh endpoint needs auth_time. */
  localToken?: LocalTokenClaims
}

/** Picks the verifier for the deployment's auth mode. */
export function authMiddleware(auth: AuthModeConfig, db: Db): MiddlewareHandler<{ Variables: AuthVariables }> {
  return auth.mode === 'oidc' ? oidcAuthMiddleware(auth, db) : localAuthMiddleware(auth, db)
}

/**
 * Verifies the bearer token against the IdP's JWKS (pinning issuer, audience
 * and RS256), then provisions/loads the user row keyed by the token's `sub`.
 * Admin status is computed from the `groups` claim on every request rather
 * than stored, so group changes in the IdP apply within the access-token TTL.
 */
function oidcAuthMiddleware(oidc: OidcConfig, db: Db): MiddlewareHandler<{ Variables: AuthVariables }> {
  // jose caches the JWKS and re-fetches on unknown kid, so an IdP signing-key
  // rotation needs no API restart.
  const getKey = oidc.getKey ?? createRemoteJWKSet(new URL('jwks/', oidc.issuer))
  return async (c, next) => {
    const token = bearerToken(c)
    if (!token) return c.json({ error: 'unauthorized' }, 401)
    let claims: JWTPayload
    try {
      const { payload } = await jwtVerify(token, getKey, {
        issuer: oidc.issuer,
        audience: oidc.clientId,
        algorithms: ['RS256'],
        clockTolerance: 5,
      })
      claims = payload
    } catch {
      return c.json({ error: 'unauthorized' }, 401)
    }
    if (typeof claims.sub !== 'string' || claims.sub.length === 0) return c.json({ error: 'unauthorized' }, 401)

    const username =
      typeof claims.preferred_username === 'string' && claims.preferred_username.length > 0
        ? claims.preferred_username.toLowerCase()
        : claims.sub
    const groups = Array.isArray(claims.groups) ? claims.groups.filter((g): g is string => typeof g === 'string') : []

    const row = upsertUser(db, claims.sub, username)
    c.set('user', { ...row, isAdmin: groups.includes(oidc.adminGroupId) })
    await next()
  }
}

/**
 * Verifies the bearer JWT this API minted, then loads the user row named by
 * `sub`. A token whose user no longer exists is rejected, so deleting a user
 * immediately kills their sessions — that lookup IS the revocation mechanism.
 */
function localAuthMiddleware(local: LocalConfig, db: Db): MiddlewareHandler<{ Variables: AuthVariables }> {
  return async (c, next) => {
    const token = bearerToken(c)
    if (!token) return c.json({ error: 'unauthorized' }, 401)
    let payload: { sub?: unknown; auth_time?: unknown }
    try {
      payload = (await verify(token, local.jwtSecret, 'HS256')) as { sub?: unknown; auth_time?: unknown }
    } catch {
      return c.json({ error: 'unauthorized' }, 401)
    }
    if (typeof payload.sub !== 'string' || typeof payload.auth_time !== 'number') {
      return c.json({ error: 'unauthorized' }, 401)
    }
    const row = db.select().from(users).where(eq(users.id, payload.sub)).get()
    if (!row) return c.json({ error: 'unauthorized' }, 401)
    c.set('user', { id: row.id, username: row.username, isAdmin: row.isAdmin ?? false, createdAt: row.createdAt })
    c.set('localToken', { sub: payload.sub, authTime: payload.auth_time })
    await next()
  }
}

function bearerToken(c: Context): string | null {
  const header = c.req.header('Authorization')
  return header?.startsWith('Bearer ') ? header.slice('Bearer '.length) : null
}

/**
 * JIT provisioning for OIDC: the verified token IS the registration. Rows are
 * keyed by the IdP subject; username is display-only and refreshed on rename.
 */
function upsertUser(db: Db, id: string, username: string): Omit<AuthUser, 'isAdmin'> {
  const row = db.select().from(users).where(eq(users.id, id)).get()
  if (row) {
    if (row.username !== username) db.update(users).set({ username }).where(eq(users.id, id)).run()
    return { id: row.id, username, createdAt: row.createdAt }
  }
  const createdAt = Date.now()
  db.insert(users).values({ id, username, createdAt }).run()
  return { id, username, createdAt }
}

/** Gate for admin-only routes. Runs after authMiddleware. */
export async function adminOnly(c: Context<{ Variables: AuthVariables }>, next: () => Promise<void>): Promise<Response | void> {
  if (!c.get('user').isAdmin) return c.json({ error: 'forbidden' }, 403)
  await next()
}

/**
 * Fixed-window failed-login counter keyed by lowercased username. In-memory and
 * process-local — acceptable for the single-process deployment. Scoped per app
 * instance rather than module-global so tests don't bleed state into each other.
 */
export class LoginRateLimiter {
  private readonly attempts = new Map<string, { count: number; windowStart: number }>()

  constructor(
    private readonly maxAttempts = 10,
    private readonly windowMs = 15 * 60_000,
  ) {}

  private prune(key: string, now: number): { count: number; windowStart: number } | undefined {
    const entry = this.attempts.get(key)
    if (!entry) return undefined
    if (now - entry.windowStart >= this.windowMs) {
      this.attempts.delete(key)
      return undefined
    }
    return entry
  }

  isBlocked(username: string): boolean {
    const entry = this.prune(username.toLowerCase(), Date.now())
    return !!entry && entry.count >= this.maxAttempts
  }

  recordFailure(username: string): void {
    const key = username.toLowerCase()
    const now = Date.now()
    const entry = this.prune(key, now)
    if (!entry) {
      this.attempts.set(key, { count: 1, windowStart: now })
      return
    }
    entry.count += 1
  }

  reset(username: string): void {
    this.attempts.delete(username.toLowerCase())
  }
}
