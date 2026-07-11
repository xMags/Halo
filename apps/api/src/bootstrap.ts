import { randomUUID } from 'node:crypto'
import { hashPassword } from './auth'
import type { Db } from './db'
import { users } from './schema'

/**
 * First-boot provisioning: seed the admin user from ADMIN_PASSWORD when the
 * users table is empty. Runs at startup (needs env), never in migrations. After
 * this the admin is a normal row and ADMIN_PASSWORD is never consulted for login.
 */
export function ensureAdminUser(db: Db, adminPassword: string): void {
  const existing = db.select({ id: users.id }).from(users).limit(1).get()
  if (existing) return
  db.insert(users)
    .values({
      id: randomUUID(),
      username: 'admin',
      passwordHash: hashPassword(adminPassword),
      isAdmin: true,
      createdAt: Date.now(),
    })
    .run()
}
