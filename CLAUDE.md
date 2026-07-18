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
| `apps/desktop` | Tauri v2 streaming-only client (Windows-first), React UI over mpv | typecheck + `cargo build` in `src-tauri` (needs `vendor/mpv/libmpv-2.dll`, see `vendor/README.md`) |

Dev: `pnpm dev` (api :8787, needs `apps/api/.env` from `.env.example`).
Mobile sim: `pnpm --filter @halo/mobile ios`. Device (Release, standalone JS):
`pnpm --filter @halo/mobile exec expo run:ios --configuration Release --device <udid>`.

## Architecture invariants

- **Desktop is a thin client over mpv, Stremio-style.** Streaming only — no
  downloads subsystem, ever. All resolution via the fat-server endpoints
  (`HaloClient` + tauri-plugin-http native fetch — no CORS allowlisting);
  playback via a generic mpv channel (`mpv_cmd`/`mpv_set`/`mpv_get`/
  `mpv_observe` + `mpv-prop`/`mpv-event` events), never a typed player API
  across the JS↔Rust boundary. Two Windows compositing invariants (each broke
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
    never stored. The mobile app signs in with PKCE in the system browser
    (`expo-auth-session`) and keeps rotating refresh tokens in SecureStore
    behind a single-flight refresh.
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

- **Expo Go doesn't work** — expo-libvlc-player is a native module;
  dev-client builds only (`expo prebuild` + `expo run:ios`).
- **VLC time units**: expo-libvlc-player events emit milliseconds;
  `PlayerVideo.tsx` divides by 1000 exactly once, at the event boundary.
  History: the old react-native-vlc-media-player bridge emitted ms while its
  typings said seconds — verify units on a real episode before trusting a new
  bridge's typings.
- **expo-libvlc-player runs patched** (`patches/`, applied by pnpm) — subtitle
  delay prop + Android SurfaceView rendering. Read `patches/README.md` before
  bumping the package version; the patch will not re-apply cleanly on its own.
- **Subtitle scale/font are creation-time VLC options** — changing them
  rebuilds the native player (`key` remount in `PlayerVideo.tsx`) with
  seek-back + slave-id re-learn. libvlc's Java/ObjC wrappers expose no runtime
  SPU text-scale, so a live prop is not an option without JNI work.
- **RN `Modal` defaults to portrait-only** — every sheet must keep the
  `supportedOrientations` prop or it yanks the landscape player to portrait.
- **VLCKit on the arm64 simulator lies**: playback timing/rendering there is
  not evidence. Subtitle sync was "broken" on sim and perfect on device. Test
  playback claims on a real iPhone.
- **pnpm blocks native postinstalls** unless listed in `pnpm-workspace.yaml`
  `onlyBuiltDependencies` (better-sqlite3, esbuild).
- **pnpm runs `node-linker=hoisted`** (`.npmrc`) — the default `.pnpm` virtual
  store doubles path depth, which overflows Windows' 250-char CMake object-path
  limit when Gradle compiles react-native-screens/worklets (ninja loops with
  "manifest still dirty"). Don't remove it unless Android-on-Windows builds are
  re-verified.
- **Android-on-Windows Gradle needs two env fixes** (release variant verified
  2026-07-17): build with **JDK 17** (`JAVA_HOME` default is JDK 25, whose
  native-access warning fails AGP's `configureCMake` tasks with the useless
  error "A restricted method in java.lang.System has been called"), and pin
  **SDK CMake 3.31** via `cmake.dir` in `android/local.properties` — 3.22's
  bundled ninja predates long-path support and release (`RelWithDebInfo`)
  object paths for gesture-handler's codegen exceed 260 chars even hoisted.
  `expo prebuild` deletes `local.properties`; re-create it after.
- **Android release builds block plain-HTTP by default** — `usesCleartextTraffic`
  (expo-build-properties, mirrors iOS `NSAllowsArbitraryLoads`) is required or
  self-hosted local-mode servers and HTTP stream URLs fail with "CLEARTEXT
  communication not permitted" (debug builds allow it, so the bug only shows
  in release).
- **OAuth on Android has two landmines** (`src/oidc.ts` works around both):
  promptAsync's browser-dismiss (AppState active) races the Linking event
  carrying the authorization code, so a successful login can report `dismiss` —
  we pre-register our own redirect listener + grace window. And
  expo-auth-session's fetch layer strips trailing slashes from URLs, which
  turns Django-based IdPs' `/token/` endpoint into an APPEND_SLASH 301 that
  downgrades the POST to a body-less GET — token/refresh/revoke are hand-rolled
  form POSTs on purpose. `app/oauth/callback.tsx` must exist or expo-router
  routes the redirect to "Unmatched Route".
- New native modules (config plugins) need `expo prebuild` + a rebuild — Metro
  hot reload alone shows "Unimplemented component".

## Conventions

- Conventional commits, atomic, self-contained messages (no external doc refs).
- Never push without explicit permission. Linear history, no merge commits.
- Explicit imports only; early returns; zod-validate every API request body —
  client input is untrusted even from our own app.
- UI matches `src/theme.ts` tokens (glassy-dark system, accent vs primary
  roles documented in that file).
