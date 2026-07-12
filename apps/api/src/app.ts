import { randomUUID } from 'node:crypto'
import { eq, sql } from 'drizzle-orm'
import { Hono } from 'hono'
import { cors } from 'hono/cors'
import { z } from 'zod'
import {
  addonSupportsResource,
  getCatalog,
  getMeta,
  getStreams,
  getSubtitles,
  isPlayableStream,
  transportBase,
  type AddonEntry,
  type LibraryItem,
  type Manifest,
  type WatchState,
} from '@halo/core'
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
import { ProxyTargetError } from './proxyGuard'
import { safeFetch } from './safeFetch'

export interface AppConfig {
  db: Db
  jwtSecret: string
  corsOrigins: string[]
  /**
   * SSRF-guarded fetch used for all server-side addon traffic (manifest
   * resolution and the resolution endpoints). Injectable so tests can supply
   * fake responses without real network or DNS. Defaults to the module `safeFetch`.
   */
  safeFetch?: typeof fetch
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
      videoFitMode: z.enum(['cover', 'contain']).optional(),
      subtitleScalePercent: z.number().int().min(50).max(200).optional(),
      subtitleFontFamily: z.string().min(1).max(64).optional(),
      playbackRate: z.number().min(0.25).max(4).optional(),
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

  // Effective addon set for a user: globals (by position) then their own (by
  // position). This ordering is the resolution priority for meta/streams/subs.
  const effectiveAddons = (userId: string): AddonEntry[] => {
    const global = db.select().from(globalAddons).orderBy(globalAddons.position).all()
    const own = db.select().from(userAddons).where(eq(userAddons.userId, userId)).orderBy(userAddons.position).all()
    return [...global, ...own].map(toAddonEntry)
  }

  authed.get('/catalog', async (c) => {
    const addon = c.req.query('addon')
    const type = c.req.query('type')
    const id = c.req.query('id')
    if (!addon || !type || !id) return c.json({ error: 'addon, type and id are required' }, 400)
    const extra: Record<string, string> = {}
    for (const [key, value] of Object.entries(c.req.query())) {
      if (key === 'addon' || key === 'type' || key === 'id') continue
      extra[key] = value
    }
    const extraKeys = Object.keys(extra)
    if (extraKeys.length > 8) return c.json({ error: 'too many extra params' }, 400)
    if (extraKeys.some((k) => k.length > 64) || Object.values(extra).some((v) => v.length > 256)) {
      return c.json({ error: 'extra param too long' }, 400)
    }
    if (!effectiveAddons(c.get('user').id).some((a) => a.transportUrl === addon)) {
      return c.json({ error: 'addon not installed' }, 403)
    }
    try {
      const res = await getCatalog(addon, type, id, extra, { fetch: doSafeFetch, signal: AbortSignal.timeout(RESOLVE_TIMEOUT_MS) })
      return c.json(res)
    } catch {
      return c.json({ error: 'catalog fetch failed' }, 502)
    }
  })

  authed.get('/meta', async (c) => {
    const type = c.req.query('type')
    const id = c.req.query('id')
    if (!type || !id) return c.json({ error: 'type and id are required' }, 400)
    for (const addon of effectiveAddons(c.get('user').id)) {
      if (!addonSupportsResource(addon.manifest, 'meta', type, id)) continue
      try {
        const res = await getMeta(addon.transportUrl, type, id, { fetch: doSafeFetch, signal: AbortSignal.timeout(RESOLVE_TIMEOUT_MS) })
        return c.json(res)
      } catch {
        // Try the next addon that can describe this id.
      }
    }
    return c.json({ error: 'no metadata found' }, 404)
  })

  authed.get('/streams', async (c) => {
    const type = c.req.query('type')
    const videoId = c.req.query('videoId')
    if (!type || !videoId) return c.json({ error: 'type and videoId are required' }, 400)
    const capable = effectiveAddons(c.get('user').id).filter((a) => addonSupportsResource(a.manifest, 'stream', type, videoId))
    const settled = await Promise.allSettled(
      capable.map(async (a) => {
        const res = await getStreams(a.transportUrl, type, videoId, { fetch: doSafeFetch, signal: AbortSignal.timeout(RESOLVE_TIMEOUT_MS) })
        return res.streams.filter(isPlayableStream)
      }),
    )
    const results: Array<{ addon: { name: string; transportUrl: string }; streams: unknown[] }> = []
    const errors: Array<{ transportUrl: string; message: string }> = []
    settled.forEach((r, i) => {
      const a = capable[i]!
      if (r.status === 'fulfilled') {
        if (r.value.length > 0) results.push({ addon: { name: a.manifest.name, transportUrl: a.transportUrl }, streams: r.value })
      } else {
        errors.push({ transportUrl: a.transportUrl, message: errorMessage(r.reason) })
      }
    })
    return c.json({ results, errors })
  })

  authed.get('/subtitles', async (c) => {
    const type = c.req.query('type')
    const videoId = c.req.query('videoId')
    if (!type || !videoId) return c.json({ error: 'type and videoId are required' }, 400)
    const videoHash = c.req.query('videoHash')
    if (videoHash !== undefined && !/^[0-9a-fA-F]{16}$/.test(videoHash)) {
      return c.json({ error: 'videoHash must be 16 hex chars' }, 400)
    }
    let videoSize: number | undefined
    const rawSize = c.req.query('videoSize')
    if (rawSize !== undefined) {
      const n = Number(rawSize)
      if (!Number.isInteger(n) || n <= 0) return c.json({ error: 'videoSize must be a positive integer' }, 400)
      videoSize = n
    }
    const filename = c.req.query('filename')
    const capable = effectiveAddons(c.get('user').id).filter((a) => addonSupportsResource(a.manifest, 'subtitles', type, videoId))
    const settled = await Promise.allSettled(
      capable.map(async (a) => {
        const res = await getSubtitles(
          a.transportUrl,
          type,
          videoId,
          { videoHash, videoSize, filename },
          { fetch: doSafeFetch, signal: AbortSignal.timeout(RESOLVE_TIMEOUT_MS) },
        )
        return res.subtitles
      }),
    )
    const results: Array<{ addon: { name: string; transportUrl: string }; subtitles: unknown[] }> = []
    const errors: Array<{ transportUrl: string; message: string }> = []
    settled.forEach((r, i) => {
      const a = capable[i]!
      if (r.status === 'fulfilled') {
        results.push({ addon: { name: a.manifest.name, transportUrl: a.transportUrl }, subtitles: r.value })
      } else {
        errors.push({ transportUrl: a.transportUrl, message: errorMessage(r.reason) })
      }
    })
    return c.json({ results, errors, hashMatched: videoHash !== undefined })
  })

  app.route('/', authed)
  return app
}

const RESOLVE_TIMEOUT_MS = 10_000

function errorMessage(reason: unknown): string {
  return reason instanceof Error ? reason.message : String(reason)
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

