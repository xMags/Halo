import { randomBytes, scryptSync, timingSafeEqual } from 'node:crypto'
import type { Context, MiddlewareHandler } from 'hono'
import { sign, verify } from 'hono/jwt'
import { eq } from 'drizzle-orm'
import type { Db } from './db'
import { users } from './schema'

const SESSION_DAYS = 30

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

export function issueToken(jwtSecret: string, userId: string): Promise<string> {
  const nowSec = Math.floor(Date.now() / 1000)
  return sign({ sub: userId, iat: nowSec, exp: nowSec + SESSION_DAYS * 86400 }, jwtSecret)
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
}

/**
 * Verifies the bearer JWT, then loads the user row named by `sub`. A token whose
 * user no longer exists is rejected, so deleting a user immediately kills their
 * sessions.
 */
export function authMiddleware(jwtSecret: string, db: Db): MiddlewareHandler<{ Variables: AuthVariables }> {
  return async (c, next) => {
    const header = c.req.header('Authorization')
    if (!header?.startsWith('Bearer ')) return c.json({ error: 'unauthorized' }, 401)
    const token = header.slice('Bearer '.length)
    let payload: { sub?: unknown }
    try {
      payload = (await verify(token, jwtSecret, 'HS256')) as { sub?: unknown }
    } catch {
      return c.json({ error: 'unauthorized' }, 401)
    }
    if (typeof payload.sub !== 'string') return c.json({ error: 'unauthorized' }, 401)
    const row = db.select().from(users).where(eq(users.id, payload.sub)).get()
    if (!row) return c.json({ error: 'unauthorized' }, 401)
    c.set('user', { id: row.id, username: row.username, isAdmin: row.isAdmin, createdAt: row.createdAt })
    await next()
  }
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
