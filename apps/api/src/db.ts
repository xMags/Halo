import { mkdirSync } from 'node:fs'
import { dirname } from 'node:path'
import Database from 'better-sqlite3'
import { drizzle } from 'drizzle-orm/better-sqlite3'
import * as schema from './schema'

export type Db = ReturnType<typeof createDb>

/**
 * Schema is applied idempotently at boot — at three tables, drizzle-kit
 * migration machinery isn't worth it yet. When the schema first changes,
 * switch to drizzle-kit migrations.
 */
const DDL = `
CREATE TABLE IF NOT EXISTS addons (
  transport_url TEXT PRIMARY KEY,
  manifest TEXT NOT NULL,
  position INTEGER NOT NULL,
  added_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS library_items (
  id TEXT PRIMARY KEY,
  type TEXT NOT NULL,
  name TEXT NOT NULL,
  poster TEXT,
  added_at INTEGER NOT NULL,
  removed_at INTEGER,
  updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS watch_states (
  video_id TEXT PRIMARY KEY,
  item_id TEXT NOT NULL,
  position_sec REAL NOT NULL,
  duration_sec REAL NOT NULL,
  watched INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS watch_states_item_id ON watch_states (item_id);
CREATE TABLE IF NOT EXISTS user_settings (
  id INTEGER PRIMARY KEY,
  value TEXT NOT NULL,
  updated_at INTEGER NOT NULL
);
`

export function createDb(path: string) {
  if (path !== ':memory:') mkdirSync(dirname(path), { recursive: true })
  const sqlite = new Database(path)
  sqlite.pragma('journal_mode = WAL')
  sqlite.exec(DDL)
  return drizzle(sqlite, { schema })
}
