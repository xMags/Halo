import { randomUUID } from 'node:crypto'
import { isNotNull } from 'drizzle-orm'
import { hashPassword } from './auth'
import type { Db } from './db'
import { users } from './schema'

/**
 * Local-mode first-boot provisioning: seed the admin user from ADMIN_PASSWORD
 * when no local account exists yet. Checks for local accounts specifically
 * (not an empty table) so a deployment switched from OIDC — whose rows have no
 * credentials — still gets a working admin. Runs at startup (needs env), never
 * in migrations. After this the admin is a normal row and ADMIN_PASSWORD is
 * never consulted for login.
 */
export function ensureAdminUser(db: Db, adminPassword: string): void {
  const existing = db.select({ id: users.id }).from(users).where(isNotNull(users.passwordHash)).limit(1).get()
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
