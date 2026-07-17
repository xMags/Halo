import { randomUUID } from 'node:crypto'
import { createLocalJWKSet, exportJWK, generateKeyPair, SignJWT } from 'jose'
import type { Manifest } from '@halo/core'
import { createApp } from '../src/app'
import { hashPassword } from '../src/auth'
import { ensureAdminUser } from '../src/bootstrap'
import { createDb, type Db } from '../src/db'
import { globalAddons, userAddons, users } from '../src/schema'

// Stand-in IdP: a local RSA keypair whose public half is served to the app as
// a JWKS, exactly like Authentik's — token verification runs the real code path.
export const ISSUER = 'https://auth.test/application/o/halo/'
export const CLIENT_ID = 'halo-test-client'
export const ADMIN_GROUP = '00000000-0000-4000-8000-00000000dead'

const { publicKey, privateKey } = await generateKeyPair('RS256')
const localJwks = createLocalJWKSet({ keys: [{ ...(await exportJWK(publicKey)), alg: 'RS256', use: 'sig' }] })

export type App = ReturnType<typeof createApp>

/** Fresh in-memory DB and a wired-up OIDC-mode app verifying against the test JWKS. */
export function makeApp(opts: { safeFetch?: typeof fetch } = {}): { app: App; db: Db } {
  const db = createDb(':memory:')
  const app = createApp({
    db,
    auth: { mode: 'oidc', issuer: ISSUER, clientId: CLIENT_ID, adminGroupId: ADMIN_GROUP, getKey: localJwks },
    corsOrigins: ['http://localhost:5173'],
    safeFetch: opts.safeFetch,
  })
  return { app, db }
}

export const LOCAL_JWT_SECRET = 'test-jwt-secret-at-least-32-characters-long'
export const ADMIN_PASSWORD = 'admin-test-password'

/** Fresh in-memory DB and a local-mode app, admin user already seeded. */
export function makeLocalApp(opts: { safeFetch?: typeof fetch } = {}): { app: App; db: Db } {
  const db = createDb(':memory:')
  const app = createApp({
    db,
    auth: { mode: 'local', jwtSecret: LOCAL_JWT_SECRET },
    corsOrigins: ['http://localhost:5173'],
    safeFetch: opts.safeFetch,
  })
  ensureAdminUser(db, ADMIN_PASSWORD)
  return { app, db }
}

/** Raw POST /auth/login, for asserting on failures. */
export async function login(app: App, username: string, password: string): Promise<Response> {
  return app.request('/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  })
}

/** Logs in and returns the session token; throws on failure. */
export async function loginToken(app: App, username: string, password: string): Promise<string> {
  const res = await login(app, username, password)
  if (res.status !== 200) throw new Error(`login failed: ${res.status}`)
  const body = (await res.json()) as { token: string }
  return body.token
}

/** Inserts a local user directly, skipping the admin route. */
export function seedUser(db: Db, username: string, password: string, isAdmin = false): void {
  db.insert(users)
    .values({
      id: `${username}-local-id`,
      username: username.toLowerCase(),
      passwordHash: hashPassword(password),
      isAdmin,
      createdAt: Date.now(),
    })
    .run()
}

export interface TokenOptions {
  sub?: string
  username?: string
  groups?: string[]
  issuer?: string
  audience?: string
  /** e.g. '1h' or an absolute epoch-seconds value; defaults to 1h from now. */
  expiresAt?: string | number
}

/** Mints a signed access token the way the IdP would. */
export async function mintToken(opts: TokenOptions = {}): Promise<string> {
  return new SignJWT({
    preferred_username: opts.username ?? 'admin',
    groups: opts.groups ?? [ADMIN_GROUP],
  })
    .setProtectedHeader({ alg: 'RS256' })
    .setSubject(opts.sub ?? 'admin-sub')
    .setIssuer(opts.issuer ?? ISSUER)
    .setAudience(opts.audience ?? CLIENT_ID)
    .setIssuedAt()
    .setExpirationTime(opts.expiresAt ?? '1h')
    .sign(privateKey)
}

/** Token for the standing test admin (member of ADMIN_GROUP). */
export function adminToken(): Promise<string> {
  return mintToken()
}

/** Token for a regular (non-admin) user derived from the name. */
export function userToken(name: string): Promise<string> {
  return mintToken({ sub: `${name}-sub`, username: name, groups: [] })
}

function urlOf(input: Parameters<typeof fetch>[0]): string {
  return typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
}

/**
 * A stand-in for the SSRF-guarded manifest fetch: serves `manifests` keyed by
 * transport base, 404s anything else. No network, no DNS.
 */
export function mockSafeFetch(manifests: Record<string, unknown>): typeof fetch {
  return (async (input) => {
    const base = urlOf(input).replace(/\/manifest\.json$/, '')
    const manifest = manifests[base]
    if (!manifest) return new Response('not found', { status: 404 })
    return new Response(JSON.stringify(manifest), {
      status: 200,
      headers: { 'content-type': 'application/json' },
    })
  }) as typeof fetch
}

/**
 * Serves Stremio addon resources (catalog/meta/stream/subtitles) for the given
 * transport bases. `routes[base]` maps a resource path (e.g. `stream/movie/tt1`)
 * to its JSON body, or throws for a base to simulate a failing addon.
 */
export function mockResolveFetch(routes: Record<string, Record<string, unknown> | 'fail'>): typeof fetch {
  return (async (input) => {
    const url = urlOf(input)
    for (const [base, table] of Object.entries(routes)) {
      if (!url.startsWith(`${base}/`)) continue
      if (table === 'fail') throw new Error(`addon ${base} is down`)
      // `${base}/${resource}/${type}/${id}.json` or `.../${extra}.json`
      const path = url.slice(base.length + 1).replace(/\.json$/, '')
      const body = table[path]
      if (body === undefined) return new Response('not found', { status: 404 })
      return new Response(JSON.stringify(body), { status: 200, headers: { 'content-type': 'application/json' } })
    }
    return new Response('not found', { status: 404 })
  }) as typeof fetch
}

/**
 * Installs an addon for a user directly, skipping the server manifest fetch.
 * Seeds the user row itself (same `${name}-sub` id that `userToken`/`adminToken`
 * mint) since JIT provisioning only runs on the first authenticated request.
 * Returns the entry's opaque id — resolution endpoints are addressed by it.
 */
export function installUserAddon(db: Db, username: string, transportUrl: string, manifest: unknown, position = 0): string {
  const userId = `${username}-sub`
  const id = randomUUID()
  db.insert(users)
    .values({ id: userId, username: username.toLowerCase(), createdAt: Date.now() })
    .onConflictDoNothing()
    .run()
  db.insert(userAddons)
    .values({ userId, id, transportUrl, manifest: manifest as Manifest, position, addedAt: Date.now() })
    .run()
  return id
}

/** Installs a global addon directly, skipping the admin route + manifest fetch. Returns the opaque id. */
export function installGlobalAddon(db: Db, transportUrl: string, manifest: unknown, position = 0): string {
  const id = randomUUID()
  db.insert(globalAddons)
    .values({ id, transportUrl, manifest: manifest as Manifest, position, addedAt: Date.now() })
    .run()
  return id
}

export function authed(token: string, body?: unknown, method: 'GET' | 'PUT' | 'POST' | 'DELETE' = body === undefined ? 'GET' : 'PUT'): RequestInit {
  return {
    method,
    headers: {
      Authorization: `Bearer ${token}`,
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  }
}
