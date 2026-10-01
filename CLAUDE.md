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
| `apps/mobile-native` | Compose Multiplatform client (iOS-first, Android kept compiling) over libmpv. Standalone Gradle project outside the pnpm workspace; its `README.md` is the guide | `.\gradlew.bat :composeApp:compileCommonMainKotlinMetadata` and `:composeApp:compileTestKotlinIosSimulatorArm64` (Apple framework and XCUITests need macOS) |
| `apps/desktop` | Tauri v2 client (Windows-first), React UI over mpv, device-local downloads. `src/` is grouped by feature (auth, home, search, browse, sources, player, downloads, settings); styles are `src/styles/` partials | typecheck + `pnpm --filter @halo/desktop test` + `cargo build` in `src-tauri` (needs `vendor/mpv/libmpv-2.dll`, see `vendor/README.md`) |

Dev: `pnpm dev` (api :8787, needs `apps/api/.env` from `.env.example`).
Client work against a disposable server: `pnpm --filter @halo/api dev:fixtures`
runs the real API on :18790 in local mode (`admin`/`fixture-pass`) over an
in-memory DB and canned addons, seeded with addons, a library and watch history
(`--passthrough` swaps in real addons over the network; `--media <file>` serves
one video with byte ranges beside the API and points every canned stream at it,
which is what makes the picker→player path exercisable). Addons are injected
through `createApp`'s `safeFetch` because the SSRF guard rejects loopback with
no override — so a fake addon cannot simply be hosted on the dev machine.

## Architecture invariants

- **Desktop is a thin client over mpv, Stremio-style.** All resolution via
  the fat-server endpoints
  (`HaloClient` + tauri-plugin-http native fetch — no CORS allowlisting);
  playback via a generic mpv channel (`mpv_cmd`/`mpv_set`/`mpv_get`/
  `mpv_observe` + `mpv-prop`/`mpv-event` events), never a typed player API
  across the JS↔Rust boundary. Downloads are the one thing it does locally
  (see the downloads invariant below). Two Windows compositing invariants (each broke
  video invisibly when violated): mpv's `wid` must be the top-level window
  HWND, not an intermediate child; and the `transparent: true` window config
  requires the `DwmEnableBlurBehindWindow(fEnable: FALSE)` counter-call in
  setup. Non-player screens keep an opaque HTML background — mpv paints the
  whole window behind the webview.
- **Sync is last-write-wins by `updatedAt` everywhere** (watch-state, library,
  settings). Clients send their timestamp; server upserts only strictly-newer
  (`setWhere: excluded.updated_at > …`). Library removals are tombstones
  (`removedAt`), never hard deletes — they must survive stale re-adds.
- **Auth is one of two deployment-exclusive modes (`AUTH_MODE=oidc|local`),
  never both at once.** Clients discover the mode via public `GET /auth/config`
  and branch there; only a definitive rejection (OIDC `invalid_grant` / local
  refresh 401) signs the device out — network failures must not. Every sync
  table is keyed by user id (FK, `ON DELETE CASCADE`). Addons split into
  admin-managed `global_addons` and per-user `user_addons`.
  - **oidc** (the ditto deployment): the API is a pure resource server. It
    verifies RS256 access tokens against `OIDC_ISSUER`'s JWKS (`jose`, `iss` +
    `aud` pinned so tokens minted for other ditto apps are rejected) and never
    mints tokens of its own. Users are JIT-provisioned on first verified
    request with `users.id` = IdP `sub`; admin = the `OIDC_ADMIN_GROUP` UUID
    appearing in the token's `groups` claim (a custom Authentik scope mapping
    emits group UUIDs — names would break on rename), computed per request,
    never stored. The native mobile app signs in with PKCE in the system
    browser (`ASWebAuthenticationSession` on iOS, a Custom Tab on Android) and
    keeps rotating refresh tokens in platform secure storage (Keychain; an
    Android Keystore-backed store) behind a single-flight refresh.
  - **local** (self-host without an IdP): the API mints its own 30-day HS256
    session JWTs (scrypt password hashes, timing-decoy + rate-limited login,
    `ADMIN_PASSWORD` seeds the first admin, admin-managed user CRUD). Stored
    `is_admin`; `password_hash` is NULL on OIDC rows and usernames are unique
    only among local rows (partial index) — an IdP rename must never collide.
    `POST /auth/refresh` slides the session (a valid token buys a fresh one, up
    to a 90-day `auth_time` cap); revocation = deleting the user, which kills
    tokens on the next request's row lookup.
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
- **Downloads are device-local, on mobile and desktop.** One entry per
  videoId, grouped by `itemId` in UI, chosen subtitle downloaded alongside.
  - **mobile** (`apps/mobile-native`): platform-owned background transfers, a
    WorkManager foreground worker on Android and a background `URLSession` on
    iOS, so ordinary process death reconciles with the OS-owned work. Source
    URLs and headers live only in encrypted request records (Keychain items on
    iOS), never in the download index; its `README.md` has the details.
  - **desktop**: a Rust engine (`src-tauri/src/downloads.rs`) owns the queue,
    the index and the files; the webview only sends `downloads_*` commands and
    renders `download-changed` events. The index never holds a source URL or
    header: those live DPAPI-encrypted in a per-job request vault. Interrupted
    transfers re-queue on launch and resume by `Range` + `If-Range` only when
    the source gave a validator. Behaviour and UI mirror the native WinUI Halo
    Desktop's download engine and Downloads page.

## Workspace gotchas

- **pnpm blocks native postinstalls** unless listed in `pnpm-workspace.yaml`
  `onlyBuiltDependencies` (better-sqlite3, esbuild).
- **pnpm runs `nodeLinker: hoisted`** (`pnpm-workspace.yaml`) — the default
  `.pnpm` virtual store doubles path depth, which overflows Windows' 250-char
  CMake object-path limit when Gradle compiles react-native-screens/worklets
  (ninja loops with "manifest still dirty"). Don't remove it unless
  Android-on-Windows builds are re-verified.

## Conventions

- Conventional commits, atomic, self-contained messages (no external doc refs).
- Never push without explicit permission. Linear history, no merge commits.
- Explicit imports only; early returns; zod-validate every API request body —
  client input is untrusted even from our own app.
- Mobile UI matches the `HaloColors` tokens in `apps/mobile-native`'s
  `ui/HaloTheme.kt`. Desktop UI matches the
  native WinUI Halo Desktop, and its token set is applied in
  `apps/desktop/src/styles/tokens.css` (both palettes there, stamped by
  `data-theme`; `src/theme.ts` documents what the short token names mean and
  holds only the values that must reach JS). `src/styles/index.css` imports
  the partials in cascade order, so a new partial goes where its rules must
  win.
  Responsive metrics are container queries on the content surface, never
  window media queries — the design measures the content column.
