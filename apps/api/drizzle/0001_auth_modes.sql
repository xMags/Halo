-- Auth rework: OIDC (ids become IdP subjects) or local accounts, chosen per
-- deployment. Pre-rework rows used a different identity scheme and cannot be
-- carried over — deliberate fresh start; cascades wipe all user-owned rows.
DELETE FROM `users`;--> statement-breakpoint
PRAGMA foreign_keys=OFF;--> statement-breakpoint
CREATE TABLE `__new_users` (
	`id` text PRIMARY KEY NOT NULL,
	`username` text NOT NULL,
	`password_hash` text,
	`is_admin` integer,
	`created_at` integer NOT NULL
);
--> statement-breakpoint
INSERT INTO `__new_users`("id", "username", "password_hash", "is_admin", "created_at") SELECT "id", "username", "password_hash", "is_admin", "created_at" FROM `users`;--> statement-breakpoint
DROP TABLE `users`;--> statement-breakpoint
ALTER TABLE `__new_users` RENAME TO `users`;--> statement-breakpoint
PRAGMA foreign_keys=ON;--> statement-breakpoint
CREATE UNIQUE INDEX `users_local_username_unique` ON `users` (`username`) WHERE password_hash IS NOT NULL;