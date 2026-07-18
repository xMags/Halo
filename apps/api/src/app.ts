import { and, eq, isNotNull, notInArray, sql } from 'drizzle-orm'
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
  nextVideo,
  transportBase,
  type AddonEntry,
  type LibraryItem,
  type Manifest,
  type MetaResponse,
  type Stream,
  type WatchState,
} from '@halo/core'
import { randomUUID } from 'node:crypto'
import {
  adminOnly,
  authMiddleware,
  hashPassword,
  issueLocalToken,
  LoginRateLimiter,
  OIDC_SCOPES,
  SESSION_ABSOLUTE_DAYS,
  verifyPassword,
  type AuthModeConfig,
  type AuthVariables,
} from './auth'
import type { Db } from './db'
import { globalAddons, libraryItems, userAddons, users, userSettings, watchStates } from './schema'
import { ProxyTargetError } from './proxyGuard'
import { safeFetch } from './safeFetch'

export interface AppConfig {
  db: Db
  auth: AuthModeConfig
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

// Client sends only transport URLs (array order = priority; the server derives
// positions from it). Manifests are always fetched and stored server-side, so a
// client can never inject a forged manifest.
const addonUrlsSchema = z
  .array(z.string().url())
  .max(50)
  .superRefine((urls, ctx) => {
    const seen = new Set<string>()
    for (const url of urls) {
      if (seen.has(url)) {
        ctx.addIssue({ code: z.ZodIssueCode.custom, message: `duplicate transportUrl: ${url}` })
      }
      seen.add(url)
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
      subtitleOutline: z.enum(['none', 'thin', 'normal', 'thick']).optional(),
      subtitleShadow: z.boolean().optional(),
      playbackRate: z.number().min(0.25).max(4).optional(),
      autoplayNextEpisode: z.boolean().optional(),
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
    name: z.string().min(1).max(512).optional(),
    poster: z.string().url().optional(),
    updatedAt: z.number().int().positive(),
  }),
)

const addonPatchSchema = z.object({ hideCatalogs: z.boolean() })

export function createApp(config: AppConfig) {
  const { db } = config
  const doSafeFetch = config.safeFetch ?? safeFetch
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

  // Public discovery for clients: how this deployment authenticates. In OIDC
  // mode the server is the single source of OAuth config, so the app binary
  // ships none of it — pointing the app at a server is all the setup there is.
  // In local mode the app knows to show a username/password form instead.
  app.get('/auth/config', (c) =>
    config.auth.mode === 'oidc'
      ? c.json({ mode: 'oidc', issuer: config.auth.issuer, clientId: config.auth.clientId, scopes: OIDC_SCOPES })
      : c.json({ mode: 'local' }),
  )

  const authed = new Hono<{ Variables: AuthVariables }>()
  authed.use('*', authMiddleware(config.auth, db))

  // The authenticated user, incl. admin status (computed per request in both
  // modes — OIDC groups claim / local is_admin column). Clients read this to
  // decide whether to show admin-only UI; the server still enforces adminOnly
  // on every mutation, so this is a display hint, never a trust boundary.
  authed.get('/auth/me', (c) => c.json(c.get('user')))

  // Local-accounts routes. Mounted only in local mode so an OIDC deployment
  // exposes no password surface at all.
  if (config.auth.mode === 'local') {
    const { jwtSecret } = config.auth
    const loginLimiter = new LoginRateLimiter()

    app.post('/auth/login', async (c) => {
      const body = loginSchema.safeParse(await c.req.json().catch(() => null))
      if (!body.success) return c.json({ error: 'username and password required' }, 400)
      const { username, password } = body.data
      if (loginLimiter.isBlocked(username)) {
        return c.json({ error: 'too many attempts, try again later' }, 429)
      }
      // Local rows only: after an OIDC→local mode switch, a leftover IdP row
      // could share the username, and usernames are only unique among local
      // accounts — matching a credential-less row would break login.
      const row = db
        .select()
        .from(users)
        .where(and(eq(users.username, username.toLowerCase()), isNotNull(users.passwordHash)))
        .get()
      // Same generic failure whether the user exists or the password is wrong —
      // and constant hashing work in every case.
      const ok = verifyPassword(password, row?.passwordHash ?? TIMING_DECOY) && !!row
      if (!ok) {
        loginLimiter.recordFailure(username)
        return c.json({ error: 'invalid credentials' }, 401)
      }
      loginLimiter.reset(username)
      return c.json(await issueLocalToken(jwtSecret, row!.id, Math.floor(Date.now() / 1000)))
    })

    // Sliding refresh: a still-valid token buys a fresh 30-day one, so active
    // devices never re-login. auth_time carries through unchanged, enforcing
    // the absolute cap — past it the session is definitively dead and the 401
    // tells the client to sign out (same semantics as OIDC invalid_grant).
    authed.post('/auth/refresh', async (c) => {
      const claims = c.get('localToken')!
      const ageSec = Math.floor(Date.now() / 1000) - claims.authTime
      if (ageSec > SESSION_ABSOLUTE_DAYS * 86400) return c.json({ error: 'session expired' }, 401)
      return c.json(await issueLocalToken(jwtSecret, claims.sub, claims.authTime))
    })

    authed.post('/auth/password', async (c) => {
      const body = passwordChangeSchema.safeParse(await c.req.json().catch(() => null))
      if (!body.success) return c.json({ error: body.error.flatten() }, 400)
      const user = c.get('user')
      const row = db.select().from(users).where(eq(users.id, user.id)).get()!
      if (!verifyPassword(body.data.current, row.passwordHash!)) {
        return c.json({ error: 'current password incorrect' }, 401)
      }
      db.update(users).set({ passwordHash: hashPassword(body.data.next) }).where(eq(users.id, user.id)).run()
      return c.json({ ok: true })
    })

    // User management covers local accounts only; leftover OIDC rows from a
    // mode switch are inert (no credentials) and invisible here.
    authed.get('/users', adminOnly, (c) => {
      const rows = db.select().from(users).where(isNotNull(users.passwordHash)).all()
      return c.json(rows.map((r) => ({ username: r.username, isAdmin: r.isAdmin ?? false, createdAt: r.createdAt })))
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
        return c.json({ error: 'username already exists' }, 409)
      }
      return c.json({ username, isAdmin: isAdmin ?? false, createdAt }, 201)
    })

    authed.delete('/users/:username', adminOnly, (c) => {
      const target = (c.req.param('username') ?? '').toLowerCase()
      if (target === c.get('user').username) {
        return c.json({ error: 'cannot delete your own account' }, 400)
      }
      const result = db.delete(users).where(and(eq(users.username, target), isNotNull(users.passwordHash))).run()
      if (result.changes === 0) return c.json({ error: 'user not found' }, 404)
      return c.json({ ok: true })
    })
  }

  authed.get('/addons', (c) => {
    const user = c.get('user')
    // Global transport URLs can embed the admin's secrets (debrid API keys),
    // so non-admins get them redacted; the opaque id addresses resolution.
    const global = db
      .select()
      .from(globalAddons)
      .orderBy(globalAddons.position)
      .all()
      .map(toAddonEntry)
      .map(
        (a): AddonEntry =>
          user.isAdmin ? a : { id: a.id, manifest: a.manifest, position: a.position, ...(a.hideCatalogs ? { hideCatalogs: true } : {}) },
      )
    const userList = db
      .select()
      .from(userAddons)
      .where(eq(userAddons.userId, user.id))
      .orderBy(userAddons.position)
      .all()
      .map(toAddonEntry)
    return c.json({ global, user: userList })
  })

  // Full-collection declaration of the caller's own addons, applied as a diff
  // keyed by transportUrl: kept entries retain their opaque id, manifest and
  // addedAt untouched (no manifest re-fetch — ids must stay stable so clients
  // can hold onto them), new URLs get their manifest fetched server-side
  // (SSRF-guarded, all-or-nothing: one bad manifest fails the request and
  // leaves the old list intact), and URLs absent from the payload are deleted.
  authed.put('/addons', async (c) => {
    const body = addonUrlsSchema.safeParse(await c.req.json().catch(() => null))
    if (!body.success) return c.json({ error: body.error.flatten() }, 400)
    const user = c.get('user')
    const urls = body.data
    const existing = new Set(
      db.select({ transportUrl: userAddons.transportUrl }).from(userAddons).where(eq(userAddons.userId, user.id)).all().map((r) => r.transportUrl),
    )
    const resolved = await resolveManifests(urls.filter((u) => !existing.has(u)), doSafeFetch)
    if ('error' in resolved) return c.json({ error: resolved.error }, 400)
    const fetched = new Map(resolved.entries.map((e) => [e.transportUrl, e.manifest]))
    const now = Date.now()
    const rows = db.transaction((tx) => {
      // notInArray with an empty list is invalid SQL — clearing the list is a
      // plain per-user delete.
      if (urls.length === 0) tx.delete(userAddons).where(eq(userAddons.userId, user.id)).run()
      else tx.delete(userAddons).where(and(eq(userAddons.userId, user.id), notInArray(userAddons.transportUrl, urls))).run()
      urls.forEach((transportUrl, position) => {
        const manifest = fetched.get(transportUrl)
        if (manifest) {
          // Upsert, not insert: a concurrent save may have installed the same
          // URL between the read above and this transaction — its id wins.
          tx.insert(userAddons)
            .values({ userId: user.id, id: randomUUID(), transportUrl, manifest, position, addedAt: now })
            .onConflictDoUpdate({ target: [userAddons.userId, userAddons.transportUrl], set: { manifest, position } })
            .run()
        } else {
          tx.update(userAddons)
            .set({ position })
            .where(and(eq(userAddons.userId, user.id), eq(userAddons.transportUrl, transportUrl)))
            .run()
        }
      })
      return tx.select().from(userAddons).where(eq(userAddons.userId, user.id)).orderBy(userAddons.position).all()
    })
    return c.json(rows.map(toAddonEntry))
  })

  // Per-addon knobs live outside the declarative URL-list PUT so the list
  // contract stays a plain string[]. Currently just catalog visibility.
  authed.patch('/addons/global/:id', adminOnly, async (c) => {
    const body = addonPatchSchema.safeParse(await c.req.json().catch(() => null))
    if (!body.success) return c.json({ error: body.error.flatten() }, 400)
    const result = db.update(globalAddons).set({ hideCatalogs: body.data.hideCatalogs }).where(eq(globalAddons.id, c.req.param('id'))).run()
    if (result.changes === 0) return c.json({ error: 'addon not found' }, 404)
    return c.json({ ok: true })
  })

  authed.patch('/addons/:id', async (c) => {
    const body = addonPatchSchema.safeParse(await c.req.json().catch(() => null))
    if (!body.success) return c.json({ error: body.error.flatten() }, 400)
    const user = c.get('user')
    const result = db
      .update(userAddons)
      .set({ hideCatalogs: body.data.hideCatalogs })
      .where(and(eq(userAddons.userId, user.id), eq(userAddons.id, c.req.param('id'))))
      .run()
    if (result.changes === 0) return c.json({ error: 'addon not found' }, 404)
    return c.json({ ok: true })
  })

  // Same diff contract, admin-only, declares the addons every user sees.
  authed.put('/addons/global', adminOnly, async (c) => {
    const body = addonUrlsSchema.safeParse(await c.req.json().catch(() => null))
    if (!body.success) return c.json({ error: body.error.flatten() }, 400)
    const urls = body.data
    const existing = new Set(db.select({ transportUrl: globalAddons.transportUrl }).from(globalAddons).all().map((r) => r.transportUrl))
    const resolved = await resolveManifests(urls.filter((u) => !existing.has(u)), doSafeFetch)
    if ('error' in resolved) return c.json({ error: resolved.error }, 400)
    const fetched = new Map(resolved.entries.map((e) => [e.transportUrl, e.manifest]))
    const now = Date.now()
    const rows = db.transaction((tx) => {
      if (urls.length === 0) tx.delete(globalAddons).run()
      else tx.delete(globalAddons).where(notInArray(globalAddons.transportUrl, urls)).run()
      urls.forEach((transportUrl, position) => {
        const manifest = fetched.get(transportUrl)
        if (manifest) {
          tx.insert(globalAddons)
            .values({ id: randomUUID(), transportUrl, manifest, position, addedAt: now })
            .onConflictDoUpdate({ target: globalAddons.transportUrl, set: { manifest, position } })
            .run()
        } else {
          tx.update(globalAddons).set({ position }).where(eq(globalAddons.transportUrl, transportUrl)).run()
        }
      })
      return tx.select().from(globalAddons).orderBy(globalAddons.position).all()
    })
    return c.json(rows.map(toAddonEntry))
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
              name: sql`excluded.name`,
              poster: sql`excluded.poster`,
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
  const effectiveAddons = (userId: string): EffectiveAddon[] => {
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
    // `addon` is the opaque entry id — the transport URL never round-trips
    // through clients (global URLs can embed secrets).
    const entry = effectiveAddons(c.get('user').id).find((a) => a.id === addon)
    if (!entry) return c.json({ error: 'addon not installed' }, 403)
    try {
      const res = await getCatalog(entry.transportUrl, type, id, extra, { fetch: doSafeFetch, signal: AbortSignal.timeout(RESOLVE_TIMEOUT_MS) })
      return c.json(res)
    } catch {
      return c.json({ error: 'catalog fetch failed' }, 502)
    }
  })

  /** First effective addon that can describe this type/id wins; null if none. */
  const resolveMeta = async (addons: EffectiveAddon[], type: string, id: string): Promise<MetaResponse | null> => {
    for (const addon of addons) {
      if (!addonSupportsResource(addon.manifest, 'meta', type, id)) continue
      try {
        return await getMeta(addon.transportUrl, type, id, { fetch: doSafeFetch, signal: AbortSignal.timeout(RESOLVE_TIMEOUT_MS) })
      } catch {
        // Try the next addon that can describe this id.
      }
    }
    return null
  }

  authed.get('/meta', async (c) => {
    const type = c.req.query('type')
    const id = c.req.query('id')
    if (!type || !id) return c.json({ error: 'type and id are required' }, 400)
    const res = await resolveMeta(effectiveAddons(c.get('user').id), type, id)
    if (!res) return c.json({ error: 'no metadata found' }, 404)
    return c.json(res)
  })

  // Binge continuation: the episode after `videoId` in `metaId`'s ordering,
  // plus — when the addon that served the current stream still exists — that
  // addon's stream for the next episode with the same bingeGroup. Matching is
  // Stremio's rule exactly: same addon, exact group equality, no fuzzy tier.
  // `stream: null` means "fall back to the stream picker".
  authed.get('/next-episode', async (c) => {
    const type = c.req.query('type')
    const metaId = c.req.query('metaId')
    const videoId = c.req.query('videoId')
    if (!type || !metaId || !videoId) return c.json({ error: 'type, metaId and videoId are required' }, 400)
    const addonId = c.req.query('addon')
    const bingeGroup = c.req.query('bingeGroup')
    if (bingeGroup !== undefined && bingeGroup.length > 512) return c.json({ error: 'bingeGroup too long' }, 400)

    const addons = effectiveAddons(c.get('user').id)
    const meta = await resolveMeta(addons, type, metaId)
    if (!meta) return c.json({ error: 'no metadata found' }, 404)
    const next = nextVideo(meta.meta.videos ?? [], videoId)
    if (!next) return c.json({ video: null, stream: null })

    // A missing addon id is benign — the addon was uninstalled mid-playback.
    // The next episode is still reported, just without a matched stream.
    const entry = addonId ? addons.find((a) => a.id === addonId) : undefined
    let stream: Stream | null = null
    if (entry && bingeGroup && addonSupportsResource(entry.manifest, 'stream', type, next.id)) {
      try {
        const res = await getStreams(entry.transportUrl, type, next.id, { fetch: doSafeFetch, signal: AbortSignal.timeout(RESOLVE_TIMEOUT_MS) })
        stream = res.streams.filter(isPlayableStream).find((s) => s.behaviorHints?.bingeGroup === bingeGroup) ?? null
      } catch {
        // Best-effort: an unreachable addon degrades to the picker, not a 5xx.
      }
    }
    return c.json({ video: next, stream })
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
    const results: Array<{ addon: { id: string; name: string }; streams: unknown[] }> = []
    const errors: Array<{ id: string; message: string }> = []
    settled.forEach((r, i) => {
      const a = capable[i]!
      if (r.status === 'fulfilled') {
        if (r.value.length > 0) results.push({ addon: { id: a.id, name: a.manifest.name }, streams: r.value })
      } else {
        errors.push({ id: a.id, message: errorMessage(r.reason) })
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
    const results: Array<{ addon: { id: string; name: string }; subtitles: unknown[] }> = []
    const errors: Array<{ id: string; message: string }> = []
    settled.forEach((r, i) => {
      const a = capable[i]!
      if (r.status === 'fulfilled') {
        results.push({ addon: { id: a.id, name: a.manifest.name }, subtitles: r.value })
      } else {
        errors.push({ id: a.id, message: errorMessage(r.reason) })
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
    name: r.name ?? undefined,
    poster: r.poster ?? undefined,
    updatedAt: r.updatedAt,
  }
}

/**
 * Server-side view of an addon entry: unlike the wire `AddonEntry` (where the
 * transport URL is redacted on global entries for non-admins), resolution
 * always needs the URL.
 */
type EffectiveAddon = AddonEntry & { transportUrl: string }

function toAddonEntry(r: {
  id: string
  transportUrl: string
  manifest: Manifest
  position: number
  hideCatalogs: boolean
}): EffectiveAddon {
  return {
    id: r.id,
    transportUrl: r.transportUrl,
    manifest: r.manifest,
    position: r.position,
    ...(r.hideCatalogs ? { hideCatalogs: true } : {}),
  }
}

interface ResolvedAddon {
  transportUrl: string
  manifest: Manifest
}

/**
 * Fetches and validates the manifest for every URL, all-or-nothing. Returns the
 * resolved entries or the first failing transportUrl. Fetches run concurrently.
 */
async function resolveManifests(
  urls: string[],
  doSafeFetch: (url: string) => Promise<Response>,
): Promise<{ entries: ResolvedAddon[] } | { error: string }> {
  const results = await Promise.all(
    urls.map(async (transportUrl) => {
      try {
        const res = await doSafeFetch(`${transportBase(transportUrl)}/manifest.json`)
        if (!res.ok) return { transportUrl, manifest: null }
        const parsed = manifestSchema.safeParse(await res.json())
        return { transportUrl, manifest: parsed.success ? (parsed.data as Manifest) : null }
      } catch {
        return { transportUrl, manifest: null }
      }
    }),
  )
  const failed = results.find((r) => r.manifest === null)
  if (failed) return { error: `could not fetch a valid manifest for ${failed.transportUrl}` }
  return { entries: results.map((r) => ({ transportUrl: r.transportUrl, manifest: r.manifest! })) }
}

