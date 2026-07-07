import { eq, sql } from 'drizzle-orm'
import { Hono } from 'hono'
import { cors } from 'hono/cors'
import { z } from 'zod'
import type { AddonEntry, LibraryItem, Manifest, WatchState } from '@halo/core'
import { authMiddleware, issueToken, passwordMatches } from './auth'
import type { Db } from './db'
import { addons, libraryItems, watchStates } from './schema'
import { assertSafeProxyTarget, ProxyTargetError } from './proxyGuard'

export interface AppConfig {
  db: Db
  adminPassword: string
  jwtSecret: string
  corsOrigins: string[]
}

const loginSchema = z.object({ password: z.string().min(1) })

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

const addonsSchema = z.array(
  z.object({
    transportUrl: z.string().url(),
    manifest: manifestSchema,
    position: z.number().int().min(0),
  }),
)

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
  const app = new Hono()

  app.use(
    '*',
    cors({
      origin: config.corsOrigins,
      allowHeaders: ['Authorization', 'Content-Type'],
      allowMethods: ['GET', 'PUT', 'POST', 'OPTIONS'],
    }),
  )

  app.get('/health', (c) => c.json({ ok: true }))

  app.post('/auth/login', async (c) => {
    const body = loginSchema.safeParse(await c.req.json().catch(() => null))
    if (!body.success) return c.json({ error: 'password required' }, 400)
    if (!passwordMatches(body.data.password, config.adminPassword)) {
      return c.json({ error: 'invalid password' }, 401)
    }
    return c.json({ token: await issueToken(config.jwtSecret) })
  })

  const authed = new Hono()
  authed.use('*', authMiddleware(config.jwtSecret))

  authed.get('/addons', (c) => {
    const rows = db.select().from(addons).orderBy(addons.position).all()
    const entries: AddonEntry[] = rows.map((r) => ({
      transportUrl: r.transportUrl,
      manifest: r.manifest,
      position: r.position,
    }))
    return c.json(entries)
  })

  // Full-collection replace: the addon list is small, ordered, and edited as a
  // whole (add/remove/reorder), so replace semantics beat per-row merging.
  authed.put('/addons', async (c) => {
    const body = addonsSchema.safeParse(await c.req.json().catch(() => null))
    if (!body.success) return c.json({ error: body.error.flatten() }, 400)
    const now = Date.now()
    db.transaction((tx) => {
      tx.delete(addons).run()
      for (const entry of body.data) {
        tx.insert(addons)
          .values({
            transportUrl: entry.transportUrl,
            // zod validates only Halo's load-bearing fields; the full manifest
            // shape is the addon's business.
            manifest: entry.manifest as Manifest,
            position: entry.position,
            addedAt: now,
          })
          .run()
      }
    })
    return c.json(body.data)
  })

  authed.get('/library', (c) => {
    // Tombstones (removedAt set) are included so other devices sync removals.
    const rows = db.select().from(libraryItems).all()
    const items: LibraryItem[] = rows.map((r) => ({
      id: r.id,
      type: r.type,
      name: r.name,
      poster: r.poster ?? undefined,
      addedAt: r.addedAt,
      removedAt: r.removedAt ?? undefined,
      updatedAt: r.updatedAt,
    }))
    return c.json(items)
  })

  authed.put('/library', async (c) => {
    const body = librarySchema.safeParse(await c.req.json().catch(() => null))
    if (!body.success) return c.json({ error: body.error.flatten() }, 400)
    db.transaction((tx) => {
      for (const item of body.data) {
        tx.insert(libraryItems)
          .values({
            id: item.id,
            type: item.type,
            name: item.name,
            poster: item.poster ?? null,
            addedAt: item.addedAt,
            removedAt: item.removedAt ?? null,
            updatedAt: item.updatedAt,
          })
          .onConflictDoUpdate({
            target: libraryItems.id,
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
    return c.json(db.select().from(libraryItems).all().map(rowToLibraryItem))
  })

  authed.get('/watch-state', (c) => {
    const rows = db.select().from(watchStates).all()
    return c.json(rows.map(rowToWatchState))
  })

  authed.put('/watch-state', async (c) => {
    const body = watchStatesSchema.safeParse(await c.req.json().catch(() => null))
    if (!body.success) return c.json({ error: body.error.flatten() }, 400)
    db.transaction((tx) => {
      for (const state of body.data) {
        tx.insert(watchStates)
          .values(state)
          .onConflictDoUpdate({
            target: watchStates.videoId,
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
    return c.json(db.select().from(watchStates).all().map(rowToWatchState))
  })

  authed.get('/addon-proxy', async (c) => {
    const target = c.req.query('url')
    if (!target) return c.json({ error: 'url query param required' }, 400)
    try {
      return await proxyFetch(target)
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

const MAX_REDIRECTS = 5

// Redirects are followed manually so every hop passes the SSRF guard —
// otherwise a public host could 302 into the internal network.
async function proxyFetch(target: string): Promise<Response> {
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
