# Halo — agent guide

Open media center: Stremio addon protocol, iOS-first, device-local downloads
for offline watching (the feature Stremio lacks). Single user, self-hosted API
(eventually halo.ditto.moe). Server never stores media; torrents are out of
scope — streams come from debrid/HTTP addons as direct URLs.

## Layout & commands

pnpm workspace monorepo, Node 22, TS strict everywhere. `packages/core` ships
raw TS source (`main: src/index.ts`) — Metro/tsx consume it directly, there is
no build orchestration on purpose.

| Path | What | Verify with |
| --- | --- | --- |
| `packages/core` | Addon protocol client, subtitle utils (OpenSubtitles hash, srt→vtt, languages), typed API client | `pnpm --filter @halo/core typecheck` |
| `apps/api` | Hono + Drizzle/better-sqlite3 sync backend | `pnpm --filter @halo/api test` (vitest), then curl |
| `apps/mobile` | Expo (dev-client) iOS app, expo-router, VLC player | typecheck + `pnpm --filter @halo/mobile exec expo export --platform ios` |

Dev: `pnpm dev` (api :8787, needs `apps/api/.env` from `.env.example`).
Mobile sim: `pnpm --filter @halo/mobile ios`. Device (Release, standalone JS):
`pnpm --filter @halo/mobile exec expo run:ios --configuration Release --device <udid>`.

## Architecture invariants

- **Sync is last-write-wins by `updatedAt` everywhere** (watch-state, library,
  settings). Clients send their timestamp; server upserts only strictly-newer
  (`setWhere: excluded.updated_at > …`). Library removals are tombstones
  (`removedAt`), never hard deletes — they must survive stale re-adds.
- **Auth is multi-user**: a `users` table, passwords hashed with `node:crypto`
  scrypt (params encoded per hash), JWT (`hono/jwt`, HS256) carrying the user id
  as `sub`. On an empty DB the first boot seeds an `admin` user from
  `ADMIN_PASSWORD`; after that it is never consulted for login. Every sync table
  is keyed by user id (FK, `ON DELETE CASCADE`). Addons split into
  admin-managed `global_addons` and per-user `user_addons`.
- **Server-side addon fetches are SSRF-guarded, not origin-allowlisted**: the
  `/addon-proxy`, manifest resolution, and the catalog/meta/stream/subtitle
  endpoints all fetch arbitrary public CDNs, so the guard is auth + URL/protocol
  pre-check + private/reserved-IP rejection + per-hop redirect re-validation,
  with a connect-time DNS lookup hook (`safeFetch.ts`) that re-checks the
  resolved address to close the rebinding TOCTOU (`proxyGuard.ts` holds the
  blocklist). Don't "simplify" it to an allowlist; don't remove the redirect
  loop or the lookup hook.
- **SQLite schema is evolved via drizzle-kit migrations run at boot**
  (`db.ts` `migrate()` over `apps/api/drizzle`, foreign keys enabled). Add a new
  migration for schema changes; the `:memory:` test DBs run them too.
- **Subtitle quality = hash matching.** The OpenSubtitles hash (size + first/
  last 64 KiB) is computed via HTTP range requests for streams and from disk
  for downloads, then passed as `videoHash`/`videoSize` extras. This is the
  root fix for "inaccurate subs" — never regress to bare id search.
- **ASS/SRT files are handed to VLC untouched.** libVLC renders both natively
  (ASS with full styling). `srtToVtt` in core is for future non-VLC clients
  only — it must stay off the mobile playback path.
- **Downloads are device-local** (`expo-file-system/legacy` resumable — the
  legacy import is deliberate: the new FS API has no resume/progress). One
  entry per videoId, grouped by `itemId` in UI, chosen subtitle downloaded
  alongside. Known limit: in-flight downloads don't survive app kill; entries
  are re-marked paused on cold start. True background URLSession downloads are
  a flagged follow-up.

## Mobile gotchas (each cost a debugging session)

- **Expo Go doesn't work** — react-native-vlc-media-player is a native module;
  dev-client builds only (`expo prebuild` + `expo run:ios`).
- **VLC time units**: the bridge's typings say seconds, implementations emit
  ms. `normalizeSeconds()` in `player.tsx` (>50 000 → ms) — verified against a
  real 47-min episode. Don't remove it.
- **RN `Modal` defaults to portrait-only** — every sheet must keep the
  `supportedOrientations` prop or it yanks the landscape player to portrait.
- **VLCKit on the arm64 simulator lies**: playback timing/rendering there is
  not evidence. Subtitle sync was "broken" on sim and perfect on device. Test
  playback claims on a real iPhone.
- **pnpm blocks native postinstalls** unless listed in root `package.json`
  `pnpm.onlyBuiltDependencies` (better-sqlite3, esbuild).
- New native modules (config plugins) need `expo prebuild` + a rebuild — Metro
  hot reload alone shows "Unimplemented component".

## Conventions

- Conventional commits, atomic, self-contained messages (no external doc refs).
- Never push without explicit permission. Linear history, no merge commits.
- Explicit imports only; early returns; zod-validate every API request body —
  client input is untrusted even from our own app.
- UI matches `src/theme.ts` tokens (glassy-dark system, accent vs primary
  roles documented in that file).
