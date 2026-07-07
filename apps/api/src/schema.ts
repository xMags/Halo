import { integer, real, sqliteTable, text } from 'drizzle-orm/sqlite-core'
import type { Manifest } from '@halo/core'

export const addons = sqliteTable('addons', {
  transportUrl: text('transport_url').primaryKey(),
  manifest: text('manifest', { mode: 'json' }).$type<Manifest>().notNull(),
  position: integer('position').notNull(),
  addedAt: integer('added_at').notNull(),
})

export const libraryItems = sqliteTable('library_items', {
  id: text('id').primaryKey(),
  type: text('type').notNull(),
  name: text('name').notNull(),
  poster: text('poster'),
  addedAt: integer('added_at').notNull(),
  removedAt: integer('removed_at'),
  updatedAt: integer('updated_at').notNull(),
})

export const watchStates = sqliteTable('watch_states', {
  videoId: text('video_id').primaryKey(),
  itemId: text('item_id').notNull(),
  positionSec: real('position_sec').notNull(),
  durationSec: real('duration_sec').notNull(),
  watched: integer('watched', { mode: 'boolean' }).notNull(),
  updatedAt: integer('updated_at').notNull(),
})
