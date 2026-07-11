import { mkdirSync } from 'node:fs'
import { dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import Database from 'better-sqlite3'
import { drizzle } from 'drizzle-orm/better-sqlite3'
import { migrate } from 'drizzle-orm/better-sqlite3/migrator'
import * as schema from './schema'

export type Db = ReturnType<typeof createDb>

// Resolved relative to this source file so migrations are found regardless of
// the process cwd (tsx dev, vitest, pm2). Points at apps/api/drizzle.
const MIGRATIONS_FOLDER = fileURLToPath(new URL('../drizzle', import.meta.url))

export function createDb(path: string) {
  if (path !== ':memory:') mkdirSync(dirname(path), { recursive: true })
  const sqlite = new Database(path)
  sqlite.pragma('journal_mode = WAL')
  // Required for the ON DELETE CASCADE on user-owned rows; off by default.
  sqlite.pragma('foreign_keys = ON')
  const db = drizzle(sqlite, { schema })
  migrate(db, { migrationsFolder: MIGRATIONS_FOLDER })
  return db
}
