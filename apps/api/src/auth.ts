import { createHash, timingSafeEqual } from 'node:crypto'
import type { MiddlewareHandler } from 'hono'
import { jwt, sign } from 'hono/jwt'

const SESSION_DAYS = 30

/** Length-independent constant-time comparison via digest normalization. */
export function passwordMatches(candidate: string, expected: string): boolean {
  const a = createHash('sha256').update(candidate).digest()
  const b = createHash('sha256').update(expected).digest()
  return timingSafeEqual(a, b)
}

export function issueToken(jwtSecret: string): Promise<string> {
  const nowSec = Math.floor(Date.now() / 1000)
  return sign({ sub: 'halo', iat: nowSec, exp: nowSec + SESSION_DAYS * 86400 }, jwtSecret)
}

export function authMiddleware(jwtSecret: string): MiddlewareHandler {
  return jwt({ secret: jwtSecret, alg: 'HS256' })
}
