-- Hand-edited from drizzle-kit output: SQLite cannot ADD COLUMN NOT NULL
-- without a constant default, so the columns are added nullable and backfilled
-- with random hex; the app writes an id on every insert.
ALTER TABLE `global_addons` ADD `id` text;--> statement-breakpoint
UPDATE `global_addons` SET `id` = lower(hex(randomblob(16)));--> statement-breakpoint
CREATE UNIQUE INDEX `global_addons_id_unique` ON `global_addons` (`id`);--> statement-breakpoint
ALTER TABLE `user_addons` ADD `id` text;--> statement-breakpoint
UPDATE `user_addons` SET `id` = lower(hex(randomblob(16)));--> statement-breakpoint
CREATE UNIQUE INDEX `user_addons_id_unique` ON `user_addons` (`id`);
