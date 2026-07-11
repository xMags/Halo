import { randomUUID } from 'node:crypto'
import { eq, sql } from 'drizzle-orm'
import { Hono } from 'hono'
import { cors } from 'hono/cors'
import { z } from 'zod'
import { transportBase, type AddonEntry, type LibraryItem, type Manifest, type WatchState } from '@halo/core'
import {
  adminOnly,
  authMiddleware,
  hashPassword,
  issueToken,
  LoginRateLimiter,
  verifyPassword,
  type AuthVariables,
} from './auth'
import type { Db } from './db'
import { globalAddons, libraryItems, userAddons, users, userSettings, watchStates } from './schema'
import { assertSafeProxyTarget, ProxyTargetError } from './proxyGuard'

export interface AppConfig {
  db: Db
  jwtSecret: string
  corsOrigins: string[]
  /**
   * SSRF-guarded fetch used to resolve addon manifests. Follows redirects with
   * per-hop revalidation. Injectable so tests can supply fake manifests without
   * real network or DNS. Defaults to the module `safeFetch`.
   */
  safeFetch?: (url: string) => Promise<Response>
}

// Constant scrypt work on unknown usernames too, so a missing user can't be
// distinguished from a wrong password by response timing.
const TIMING_DECOY = hashPassword('halo-timing-decoy')

const loginSchema = z.object({ username: z.string().min(1), password: z.string().min(1) })
const passwordChangeSchema = z.object({ current: z.string().min(1), next: z.string().min(8) })
const createUserSchema = z.object({
  username: z
    .string()
    .min(1)
    .max(32)
    .regex(/^[a-zA-Z0-9_-]+$/)
    .transform((s) => s.toLowerCase()),
  password: z.string().min(8),
  isAdmin: z.boolean().optional(),
})

// Loose on purpose: manifests come from third-party addons and only these
// fields are load-bearing for Halo; everything else passes through untouched.
const manifestSchema = z
  .object({
    id: z.string().min(1),
    version: z.string(),
    name: z.string().min(1),
    resources: z.array(z.union([z.string(), z.object({ name: z.string() }).passthrough()])),
    types: z.array(z.string()),
    catalogs: z.array(z.object({ type: z.string(), id: z.string() }).passthrough()),
  })
  .passthrough()

// Client sends only references; the server fetches and stores the manifest
// itself, so a client can never inject a forged manifest.
const addonRefsSchema = z
  .array(
    z.object({
      transportUrl: z.string().url(),
      position: z.number().int().min(0),
    }),
  )
  .max(50)
  .superRefine((refs, ctx) => {
    const seen = new Set<string>()
    for (const ref of refs) {
      if (seen.has(ref.transportUrl)) {
        ctx.addIssue({ code: z.ZodIssueCode.custom, message: `duplicate transportUrl: ${ref.transportUrl}` })
      }
      seen.add(ref.transportUrl)
    }
  })

const librarySchema = z.array(
  z.object({
    id: z.string().min(1),
    type: z.string().min(1),
    name: z.string().min(1),
    poster: z.string().url().optional(),
    addedAt: z.number().int().positive(),
    removedAt: z.number().int().positive().optional(),
    updatedAt: z.number().int().positive(),
  }),
)

// Known fields validated, unknown fields pass through so older servers don't
// strip newer clients' settings.
const settingsSchema = z.object({
  value: z
    .object({
      preferredAudioLang: z.string().max(8).optional(),
      preferredSubtitleLang: z.string().max(8).optional(),
    })
    .passthrough(),
  updatedAt: z.number().int().positive(),
})

const watchStatesSchema = z.array(
  z.object({
    videoId: z.string().min(1),
    itemId: z.string().min(1),
    positionSec: z.number().min(0),
    durationSec: z.number().min(0),
    watched: z.boolean(),
    updatedAt: z.number().int().positive(),
  }),
)

export function createApp(config: AppConfig) {
  const { db } = config
  const doSafeFetch = config.safeFetch ?? safeFetch
  const loginLimiter = new LoginRateLimiter()
  const app = new Hono()

  app.use(
    '*',
    cors({
      origin: config.corsOrigins,
      allowHeaders: ['Authorization', 'Content-Type'],
      allowMethods: ['GET', 'PUT', 'POST', 'DELETE', 'OPTIONS'],
    }),
  )

  app.get('/health', (c) => c.json({ ok: true }))

  app.post('/auth/login', async (c) => {
    const body = loginSchema.safeParse(await c.req.json().catch(() => null))
    if (!body.success) return c.json({ error: 'username and password required' }, 400)
    const { username, password } = body.data
    if (loginLimiter.isBlocked(username)) {
      return c.json({ error: 'too many attempts, try again later' }, 429)
    }
    const row = db.select().from(users).where(eq(users.username, username.toLowerCase())).get()
    // Same generic failure whether the user exists or the password is wrong.
    const ok = verifyPassword(password, row?.passwordHash ?? TIMING_DECOY) && !!row
    if (!ok) {
      loginLimiter.recordFailure(username)
      return c.json({ error: 'invalid credentials' }, 401)
    }
    loginLimiter.reset(username)
    return c.json({ token: await issueToken(config.jwtSecret, row!.id) })
  })

  const authed = new Hono<{ Variables: AuthVariables }>()
  authed.use('*', authMiddleware(config.jwtSecret, db))

  authed.post('/auth/password', async (c) => {
    const body = passwordChangeSchema.safeParse(await c.req.json().catch(() => null))
    if (!body.success) return c.json({ error: body.error.flatten() }, 400)
    const user = c.get('user')
    const row = db.select().from(users).where(eq(users.id, user.id)).get()!
    if (!verifyPassword(body.data.current, row.passwordHash)) {
      return c.json({ error: 'current password incorrect' }, 401)
    }
    db.update(users).set({ passwordHash: hashPassword(body.data.next) }).where(eq(users.id, user.id)).run()
    return c.json({ ok: true })
  })

  authed.get('/users', adminOnly, (c) => {
    const rows = db.select().from(users).all()
    return c.json(rows.map((r) => ({ username: r.username, isAdmin: r.isAdmin, createdAt: r.createdAt })))
  })

  authed.post('/users', adminOnly, async (c) => {
    const body = createUserSchema.safeParse(await c.req.json().catch(() => null))
    if (!body.success) return c.json({ error: body.error.flatten() }, 400)
    const { username, password, isAdmin } = body.data
    const createdAt = Date.now()
    try {
      db.insert(users)
        .values({ id: randomUUID(), username, passwordHash: hashPassword(password), isAdmin: isAdmin ?? false, createdAt })
        .run()
    } catch {
      // UNIQUE violation — also the race where two creates land at once.
      return c.json({ error: 'username taken' }, 409)
    }
    return c.json({ username, isAdmin: isAdmin ?? false, createdAt }, 201)
  })

  authed.delete('/users/:username', adminOnly, (c) => {
    const target = (c.req.param('username') ?? '').toLowerCase()
    if (target === c.get('user').username) {
      return c.json({ error: 'cannot delete your own account' }, 400)
    }
    const row = db.select().from(users).where(eq(users.username, target)).get()
    if (!row) return c.json({ error: 'not found' }, 404)
    db.delete(users).where(eq(users.id, row.id)).run() // FK cascade removes their data
    return c.json({ ok: true })
  })

  authed.get('/addons', (c) => {
    const user = c.get('user')
    const global = db.select().from(globalAddons).orderBy(globalAddons.position).all().map(toAddonEntry)
    const userList = db
      .select()
      .from(userAddons)
      .where(eq(userAddons.userId, user.id))
      .orderBy(userAddons.position)
      .all()
      .map(toAddonEntry)
    return c.json({ global, user: userList })
  })

  // Full-collection replace of the caller's own addons. The server fetches each
  // manifest itself (SSRF-guarded) so the stored manifest is trusted; a bad or
  // unreachable manifest fails the whole request, leaving the old list intact.
  authed.put('/addons', async (c) => {
    const body = addonRefsSchema.safeParse(await c.req.json().catch(() => null))
    if (!body.success) return c.json({ error: body.error.flatten() }, 400)
    const resolved = await resolveManifests(body.data, doSafeFetch)
    if ('error' in resolved) return c.json({ error: resolved.error }, 400)
    const user = c.get('user')
    const now = Date.now()
    db.transaction((tx) => {
      tx.delete(userAddons).where(eq(userAddons.userId, user.id)).run()
      for (const r of resolved.entries) {
        tx.insert(userAddons)
          .values({ userId: user.id, transportUrl: r.transportUrl, manifest: r.manifest, position: r.position, addedAt: now })
          .run()
      }
    })
    return c.json(resolved.entries)
  })

  // Same contract, admin-only, replaces the addons every user sees.
  authed.put('/addons/global', adminOnly, async (c) => {
    const body = addonRefsSchema.safeParse(await c.req.json().catch(() => null))
    if (!body.success) return c.json({ error: body.error.flatten() }, 400)
    const resolved = await resolveManifests(body.data, doSafeFetch)
    if ('error' in resolved) return c.json({ error: resolved.error }, 400)
    const now = Date.now()
    db.transaction((tx) => {
      tx.delete(globalAddons).run()
      for (const r of resolved.entries) {
        tx.insert(globalAddons)
          .values({ transportUrl: r.transportUrl, manifest: r.manifest, position: r.position, addedAt: now })
          .run()
      }
    })
    return c.json(resolved.entries)
  })

  authed.get('/library', (c) => {
    const user = c.get('user')
    // Tombstones (removedAt set) are included so other devices sync removals.
    const rows = db.select().from(libraryItems).where(eq(libraryItems.userId, user.id)).all()
    return c.json(rows.map(rowToLibraryItem))
  })

  authed.put('/library', async (c) => {
    const body = librarySchema.safeParse(await c.req.json().catch(() => null))
    if (!body.success) return c.json({ error: body.error.flatten() }, 400)
    const user = c.get('user')
    db.transaction((tx) => {
      for (const item of body.data) {
        tx.insert(libraryItems)
          .values({
            userId: user.id,
            id: item.id,
            type: item.type,
            name: item.name,
            poster: item.poster ?? null,
            addedAt: item.addedAt,
            removedAt: item.removedAt ?? null,
            updatedAt: item.updatedAt,
          })
          .onConflictDoUpdate({
            target: [libraryItems.userId, libraryItems.id],
            set: {
              name: sql`excluded.name`,
              poster: sql`excluded.poster`,
              addedAt: sql`excluded.added_at`,
              removedAt: sql`excluded.removed_at`,
              updatedAt: sql`excluded.updated_at`,
            },
            // LWW: strictly-newer wins; ties keep the server row (deterministic).
            setWhere: sql`excluded.updated_at > ${libraryItems.updatedAt}`,
          })
          .run()
      }
    })
    return c.json(db.select().from(libraryItems).where(eq(libraryItems.userId, user.id)).all().map(rowToLibraryItem))
  })

  authed.get('/watch-state', (c) => {
    const user = c.get('user')
    const rows = db.select().from(watchStates).where(eq(watchStates.userId, user.id)).all()
    return c.json(rows.map(rowToWatchState))
  })

  authed.put('/watch-state', async (c) => {
    const body = watchStatesSchema.safeParse(await c.req.json().catch(() => null))
    if (!body.success) return c.json({ error: body.error.flatten() }, 400)
    const user = c.get('user')
    db.transaction((tx) => {
      for (const state of body.data) {
        tx.insert(watchStates)
          .values({ ...state, userId: user.id })
          .onConflictDoUpdate({
            target: [watchStates.userId, watchStates.videoId],
            set: {
              itemId: sql`excluded.item_id`,
              positionSec: sql`excluded.position_sec`,
              durationSec: sql`excluded.duration_sec`,
              watched: sql`excluded.watched`,
              updatedAt: sql`excluded.updated_at`,
            },
            setWhere: sql`excluded.updated_at > ${watchStates.updatedAt}`,
          })
          .run()
      }
    })
    return c.json(db.select().from(watchStates).where(eq(watchStates.userId, user.id)).all().map(rowToWatchState))
  })

  authed.get('/settings', (c) => {
    const user = c.get('user')
    const row = db.select().from(userSettings).where(eq(userSettings.userId, user.id)).get()
    return c.json(row ? { value: row.value, updatedAt: row.updatedAt } : { value: {}, updatedAt: 0 })
  })

  authed.put('/settings', async (c) => {
    const body = settingsSchema.safeParse(await c.req.json().catch(() => null))
    if (!body.success) return c.json({ error: body.error.flatten() }, 400)
    const user = c.get('user')
    db.insert(userSettings)
      .values({ userId: user.id, value: body.data.value, updatedAt: body.data.updatedAt })
      .onConflictDoUpdate({
        target: userSettings.userId,
        set: { value: sql`excluded.value`, updatedAt: sql`excluded.updated_at` },
        setWhere: sql`excluded.updated_at > ${userSettings.updatedAt}`,
      })
      .run()
    const row = db.select().from(userSettings).where(eq(userSettings.userId, user.id)).get()!
    return c.json({ value: row.value, updatedAt: row.updatedAt })
  })

  authed.get('/addon-proxy', async (c) => {
    const target = c.req.query('url')
    if (!target) return c.json({ error: 'url query param required' }, 400)
    try {
      return await safeFetch(target)
    } catch (err) {
      if (err instanceof ProxyTargetError) return c.json({ error: err.message }, 400)
      return c.json({ error: 'upstream fetch failed' }, 502)
    }
  })

  app.route('/', authed)
  return app
}

function rowToLibraryItem(r: typeof libraryItems.$inferSelect): LibraryItem {
  return {
    id: r.id,
    type: r.type,
    name: r.name,
    poster: r.poster ?? undefined,
    addedAt: r.addedAt,
    removedAt: r.removedAt ?? undefined,
    updatedAt: r.updatedAt,
  }
}

function rowToWatchState(r: typeof watchStates.$inferSelect): WatchState {
  return {
    videoId: r.videoId,
    itemId: r.itemId,
    positionSec: r.positionSec,
    durationSec: r.durationSec,
    watched: r.watched,
    updatedAt: r.updatedAt,
  }
}

function toAddonEntry(r: { transportUrl: string; manifest: Manifest; position: number }): AddonEntry {
  return { transportUrl: r.transportUrl, manifest: r.manifest, position: r.position }
}

interface ResolvedAddon {
  transportUrl: string
  position: number
  manifest: Manifest
}

/**
 * Fetches and validates the manifest for every ref, all-or-nothing. Returns the
 * resolved entries or the first failing transportUrl. Fetches run concurrently.
 */
async function resolveManifests(
  refs: Array<{ transportUrl: string; position: number }>,
  doSafeFetch: (url: string) => Promise<Response>,
): Promise<{ entries: ResolvedAddon[] } | { error: string }> {
  const results = await Promise.all(
    refs.map(async (ref) => {
      try {
        const res = await doSafeFetch(`${transportBase(ref.transportUrl)}/manifest.json`)
        if (!res.ok) return { ref, manifest: null }
        const parsed = manifestSchema.safeParse(await res.json())
        return { ref, manifest: parsed.success ? (parsed.data as Manifest) : null }
      } catch {
        return { ref, manifest: null }
      }
    }),
  )
  const failed = results.find((r) => r.manifest === null)
  if (failed) return { error: `could not fetch a valid manifest for ${failed.ref.transportUrl}` }
  return {
    entries: results.map((r) => ({ transportUrl: r.ref.transportUrl, position: r.ref.position, manifest: r.manifest! })),
  }
}

const MAX_REDIRECTS = 5

// Redirects are followed manually so every hop passes the SSRF guard —
// otherwise a public host could 302 into the internal network.
async function safeFetch(target: string): Promise<Response> {
  let url = await assertSafeProxyTarget(target)
  for (let hop = 0; hop <= MAX_REDIRECTS; hop++) {
    const upstream = await fetch(url, { redirect: 'manual' })
    if (upstream.status >= 300 && upstream.status < 400) {
      const location = upstream.headers.get('location')
      await upstream.body?.cancel()
      if (!location) throw new ProxyTargetError('redirect without location')
      url = await assertSafeProxyTarget(new URL(location, url).toString())
      continue
    }
    const headers = new Headers()
    for (const name of ['content-type', 'content-length', 'cache-control']) {
      const value = upstream.headers.get(name)
      if (value) headers.set(name, value)
    }
    return new Response(upstream.body, { status: upstream.status, headers })
  }
  throw new ProxyTargetError('too many redirects')
}
