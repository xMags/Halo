CREATE TABLE `global_addons` (
	`transport_url` text PRIMARY KEY NOT NULL,
	`manifest` text NOT NULL,
	`position` integer NOT NULL,
	`added_at` integer NOT NULL
);
--> statement-breakpoint
CREATE TABLE `library_items` (
	`user_id` text NOT NULL,
	`id` text NOT NULL,
	`type` text NOT NULL,
	`name` text NOT NULL,
	`poster` text,
	`added_at` integer NOT NULL,
	`removed_at` integer,
	`updated_at` integer NOT NULL,
	PRIMARY KEY(`user_id`, `id`),
	FOREIGN KEY (`user_id`) REFERENCES `users`(`id`) ON UPDATE no action ON DELETE cascade
);
--> statement-breakpoint
CREATE TABLE `user_addons` (
	`user_id` text NOT NULL,
	`transport_url` text NOT NULL,
	`manifest` text NOT NULL,
	`position` integer NOT NULL,
	`added_at` integer NOT NULL,
	PRIMARY KEY(`user_id`, `transport_url`),
	FOREIGN KEY (`user_id`) REFERENCES `users`(`id`) ON UPDATE no action ON DELETE cascade
);
--> statement-breakpoint
CREATE TABLE `user_settings` (
	`user_id` text PRIMARY KEY NOT NULL,
	`value` text NOT NULL,
	`updated_at` integer NOT NULL,
	FOREIGN KEY (`user_id`) REFERENCES `users`(`id`) ON UPDATE no action ON DELETE cascade
);
--> statement-breakpoint
CREATE TABLE `users` (
	`id` text PRIMARY KEY NOT NULL,
	`username` text NOT NULL,
	`password_hash` text NOT NULL,
	`is_admin` integer DEFAULT false NOT NULL,
	`created_at` integer NOT NULL
);
--> statement-breakpoint
CREATE UNIQUE INDEX `users_username_unique` ON `users` (`username` COLLATE NOCASE);--> statement-breakpoint
CREATE TABLE `watch_states` (
	`user_id` text NOT NULL,
	`video_id` text NOT NULL,
	`item_id` text NOT NULL,
	`position_sec` real NOT NULL,
	`duration_sec` real NOT NULL,
	`watched` integer NOT NULL,
	`updated_at` integer NOT NULL,
	PRIMARY KEY(`user_id`, `video_id`),
	FOREIGN KEY (`user_id`) REFERENCES `users`(`id`) ON UPDATE no action ON DELETE cascade
);
--> statement-breakpoint
CREATE INDEX `watch_states_user_item` ON `watch_states` (`user_id`,`item_id`);