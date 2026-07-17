import { sql } from 'drizzle-orm'
import { index, integer, primaryKey, real, sqliteTable, text, uniqueIndex } from 'drizzle-orm/sqlite-core'
import type { Manifest } from '@halo/core'

/**
 * One table serves both auth modes. OIDC rows: id = IdP subject (JIT-provisioned
 * on the first verified request), credential columns NULL. Local rows: id =
 * random UUID, password_hash/is_admin set by the admin user routes.
 */
export const users = sqliteTable(
  'users',
  {
    id: text('id').primaryKey(),
    // OIDC: display-only (from preferred_username), refreshed on rename, and
    // deliberately NOT globally unique — an IdP-side rename must never collide
    // with another row and break auth. Local: the login identifier, stored
    // lowercased; uniqueness is enforced by the partial index below.
    username: text('username').notNull(),
    // NULL for OIDC users — passwords live in the IdP there.
    passwordHash: text('password_hash'),
    // NULL for OIDC users — their admin status is computed per request from the
    // token's groups claim and never stored.
    isAdmin: integer('is_admin', { mode: 'boolean' }),
    createdAt: integer('created_at').notNull(),
  },
  (t) => [
    // Unique only among local accounts, so local login can't be ambiguous while
    // OIDC renames stay collision-free.
    uniqueIndex('users_local_username_unique')
      .on(t.username)
      .where(sql`password_hash IS NOT NULL`),
  ],
)

/** Addons every user sees, ordered before their personal ones. Admin-managed. */
export const globalAddons = sqliteTable(
  'global_addons',
  {
    transportUrl: text('transport_url').primaryKey(),
    // Opaque client-facing id: resolution endpoints are addressed by it so
    // transport URLs (which can embed secrets like debrid API keys) never have
    // to reach non-admin clients. Regenerated on every list replace.
    id: text('id').notNull(),
    manifest: text('manifest', { mode: 'json' }).$type<Manifest>().notNull(),
    position: integer('position').notNull(),
    addedAt: integer('added_at').notNull(),
  },
  (t) => [uniqueIndex('global_addons_id_unique').on(t.id)],
)

export const userAddons = sqliteTable(
  'user_addons',
  {
    userId: text('user_id')
      .notNull()
      .references(() => users.id, { onDelete: 'cascade' }),
    transportUrl: text('transport_url').notNull(),
    /** Opaque client-facing id, same role as global_addons.id. */
    id: text('id').notNull(),
    manifest: text('manifest', { mode: 'json' }).$type<Manifest>().notNull(),
    position: integer('position').notNull(),
    addedAt: integer('added_at').notNull(),
  },
  (t) => [primaryKey({ columns: [t.userId, t.transportUrl] }), uniqueIndex('user_addons_id_unique').on(t.id)],
)

export const libraryItems = sqliteTable(
  'library_items',
  {
    userId: text('user_id')
      .notNull()
      .references(() => users.id, { onDelete: 'cascade' }),
    id: text('id').notNull(),
    type: text('type').notNull(),
    name: text('name').notNull(),
    poster: text('poster'),
    addedAt: integer('added_at').notNull(),
    removedAt: integer('removed_at'),
    updatedAt: integer('updated_at').notNull(),
  },
  (t) => [primaryKey({ columns: [t.userId, t.id] })],
)

export const watchStates = sqliteTable(
  'watch_states',
  {
    userId: text('user_id')
      .notNull()
      .references(() => users.id, { onDelete: 'cascade' }),
    videoId: text('video_id').notNull(),
    itemId: text('item_id').notNull(),
    positionSec: real('position_sec').notNull(),
    durationSec: real('duration_sec').notNull(),
    watched: integer('watched', { mode: 'boolean' }).notNull(),
    updatedAt: integer('updated_at').notNull(),
  },
  (t) => [
    primaryKey({ columns: [t.userId, t.videoId] }),
    index('watch_states_user_item').on(t.userId, t.itemId),
  ],
)

/** Per-user settings blob; LWW like everything else. */
export const userSettings = sqliteTable('user_settings', {
  userId: text('user_id')
    .primaryKey()
    .references(() => users.id, { onDelete: 'cascade' }),
  value: text('value', { mode: 'json' }).$type<Record<string, unknown>>().notNull(),
  updatedAt: integer('updated_at').notNull(),
})
