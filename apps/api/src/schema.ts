import { index, integer, primaryKey, real, sqliteTable, text } from 'drizzle-orm/sqlite-core'
import type { Manifest } from '@halo/core'

export const users = sqliteTable('users', {
  id: text('id').primaryKey(),
  // Stored lowercased; the UNIQUE COLLATE NOCASE in the migration guards against
  // case-variant duplicates even if a future writer forgets to lowercase.
  username: text('username').notNull().unique(),
  passwordHash: text('password_hash').notNull(),
  isAdmin: integer('is_admin', { mode: 'boolean' }).notNull().default(false),
  createdAt: integer('created_at').notNull(),
})

/** Addons every user sees, ordered before their personal ones. Admin-managed. */
export const globalAddons = sqliteTable('global_addons', {
  transportUrl: text('transport_url').primaryKey(),
  manifest: text('manifest', { mode: 'json' }).$type<Manifest>().notNull(),
  position: integer('position').notNull(),
  addedAt: integer('added_at').notNull(),
})

export const userAddons = sqliteTable(
  'user_addons',
  {
    userId: text('user_id')
      .notNull()
      .references(() => users.id, { onDelete: 'cascade' }),
    transportUrl: text('transport_url').notNull(),
    manifest: text('manifest', { mode: 'json' }).$type<Manifest>().notNull(),
    position: integer('position').notNull(),
    addedAt: integer('added_at').notNull(),
  },
  (t) => [primaryKey({ columns: [t.userId, t.transportUrl] })],
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
