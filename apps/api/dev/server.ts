import { serve } from '@hono/node-server'
import { eq } from 'drizzle-orm'
import { safeFetch } from '../src/safeFetch'
import { createApp } from '../src/app'
import { ensureAdminUser } from '../src/bootstrap'
import { createDb, type Db } from '../src/db'
import { globalAddons, libraryItems, userAddons, users, watchStates } from '../src/schema'
import { FIXTURE_ADDONS, FIXTURE_TITLES, fixtureAddonFetch } from './fixtureAddons'

/**
 * Development server: the real API, wired to canned addons.
 *
 * This exists so client work runs against the actual server rather than a
 * stand-in. Opaque addon ids, hidden-catalog stripping, last-write-wins merges
 * and tombstones are all server behaviour, so a mock of the API would prove
 * only that the client agrees with the mock. Everything here replaces just two
 * things: where addon responses come from, and where the database lives.
 *
 *   pnpm --filter @halo/api dev:fixtures [--port 18790] [--db ./data/dev.sqlite]
 *
 * Sign in as `admin` / `fixture-pass` in local mode. Storage is in memory by
 * default, so every restart is an identical clean slate — which is what makes
 * assertions about the seeded library and history stable.
 */

const DEFAULT_PORT = 18790
const ADMIN_PASSWORD = 'fixture-pass'

// Never a real secret: this server holds no real data and its tokens are only
// ever accepted by itself.
const JWT_SECRET = 'halo-fixture-development-secret-not-for-any-deployment'

function main(): void {
  const options = parseArgs(process.argv.slice(2))
  const db = createDb(options.dbPath)
  const app = createApp({
    db,
    auth: { mode: 'local', jwtSecret: JWT_SECRET },
    corsOrigins: ['http://localhost:5173'],
    // Passthrough reaches real addons over the network for manual work; the
    // guard is the real one either way, which is why a fixture addon cannot
    // simply be hosted on this machine.
    safeFetch: options.passthrough ? safeFetch : fixtureAddonFetch(),
  })

  ensureAdminUser(db, ADMIN_PASSWORD)
  const adminId = db.select({ id: users.id }).from(users).where(eq(users.username, 'admin')).get()?.id
  if (!adminId) throw new Error('admin user missing after bootstrap')
  if (!options.passthrough) seedAddons(db, adminId)
  seedContent(db, adminId)

  serve({ fetch: app.fetch, port: options.port }, (info) => {
    console.log(`halo fixture api on :${info.port}`)
    console.log(`  sign in     admin / ${ADMIN_PASSWORD}`)
    console.log(`  storage     ${options.dbPath === ':memory:' ? 'in memory (resets on restart)' : options.dbPath}`)
    console.log(`  addons      ${options.passthrough ? 'real, over the network' : 'canned'}`)
    console.log(`  android     adb reverse tcp:${info.port} tcp:${info.port}`)
  })
}

interface Options {
  port: number
  dbPath: string
  passthrough: boolean
}

function parseArgs(argv: string[]): Options {
  const options: Options = { port: DEFAULT_PORT, dbPath: ':memory:', passthrough: false }
  for (let index = 0; index < argv.length; index += 1) {
    const flag = argv[index]
    if (flag === '--port') options.port = Number(argv[++index])
    else if (flag === '--db') options.dbPath = argv[++index] ?? ':memory:'
    else if (flag === '--passthrough') options.passthrough = true
    else throw new Error(`unknown argument ${flag} (expected --port, --db or --passthrough)`)
  }
  if (!Number.isInteger(options.port) || options.port <= 0) throw new Error('--port must be a positive integer')
  return options
}

/**
 * Installs the canned addons directly rather than through `PUT /addons`, so the
 * opaque ids are fixed strings that a test can name. The route's own
 * manifest-fetching path stays exercised by adding an addon from the settings
 * screen, which the fixture fetch answers like any other.
 *
 * One is global and one hides its catalogs, so both splits and the
 * hidden-catalog flag are present from the first request.
 */
function seedAddons(db: Db, userId: string): void {
  const now = Date.now()
  const [catalogs, streams, cloud] = FIXTURE_ADDONS
  db.insert(globalAddons)
    .values({
      transportUrl: `${catalogs!.base}/manifest.json`,
      id: catalogs!.entryId,
      manifest: catalogs!.manifest,
      position: 0,
      hideCatalogs: false,
      addedAt: now,
    })
    .onConflictDoNothing()
    .run()
  db.insert(userAddons)
    .values([
      {
        userId,
        transportUrl: `${streams!.base}/manifest.json`,
        id: streams!.entryId,
        manifest: streams!.manifest,
        position: 0,
        hideCatalogs: false,
        addedAt: now,
      },
      {
        userId,
        transportUrl: `${cloud!.base}/manifest.json`,
        id: cloud!.entryId,
        manifest: cloud!.manifest,
        position: 1,
        // Installed already hidden: its catalogs must reach clients stripped.
        hideCatalogs: true,
        addedAt: now,
      },
    ])
    .onConflictDoNothing()
    .run()
}

const MINUTE = 60_000

/**
 * A saved library and some playback history, so the personal shelves have
 * something in them before anything is watched.
 *
 * The progress values are chosen to land on either side of the rules the home
 * screen applies: one mid-way title to resume, one barely started, one past the
 * point where a title counts as finished, and one explicitly watched.
 */
function seedContent(db: Db, userId: string): void {
  const now = Date.now()
  const poster = FIXTURE_TITLES.poster
  db.insert(libraryItems)
    .values([
      libraryRow(userId, 'movie', 'tt0133093', 'The Matrix', now - 30 * MINUTE),
      libraryRow(userId, 'series', 'tt0903747', 'Breaking Bad', now - 20 * MINUTE),
      libraryRow(userId, 'movie', 'tt1375666', 'Inception', now - 10 * MINUTE),
      // A tombstone, which must stay out of every shelf and every grid.
      { ...libraryRow(userId, 'movie', 'tt0068646', 'The Godfather', now - 90 * MINUTE), removedAt: now - MINUTE },
    ])
    .onConflictDoNothing()
    .run()

  db.insert(watchStates)
    .values([
      {
        userId,
        videoId: 'tt0903747:1:3',
        itemId: 'series:tt0903747',
        positionSec: 1_320,
        durationSec: 2_940,
        watched: false,
        name: 'Breaking Bad',
        poster: poster('tt0903747'),
        updatedAt: now - 5 * MINUTE,
      },
      {
        videoId: 'tt0133093',
        userId,
        itemId: 'movie:tt0133093',
        positionSec: 2_100,
        durationSec: 8_160,
        watched: false,
        name: 'The Matrix',
        poster: poster('tt0133093'),
        updatedAt: now - 15 * MINUTE,
      },
      {
        userId,
        videoId: 'tt1375666',
        itemId: 'movie:tt1375666',
        positionSec: 8_880,
        durationSec: 8_880,
        watched: true,
        name: 'Inception',
        poster: poster('tt1375666'),
        updatedAt: now - 45 * MINUTE,
      },
      {
        userId,
        videoId: 'tt0944947:1:1',
        itemId: 'series:tt0944947',
        // Past the resumable ceiling without being marked watched: history, not
        // something to offer resuming.
        positionSec: 3_540,
        durationSec: 3_600,
        watched: false,
        name: 'Game of Thrones',
        poster: poster('tt0944947'),
        updatedAt: now - 60 * MINUTE,
      },
    ])
    .onConflictDoNothing()
    .run()
}

function libraryRow(userId: string, type: string, metaId: string, name: string, addedAt: number) {
  return {
    userId,
    id: `${type}:${metaId}`,
    type,
    name,
    poster: FIXTURE_TITLES.poster(metaId),
    addedAt,
    removedAt: null,
    updatedAt: addedAt,
  }
}

main()
