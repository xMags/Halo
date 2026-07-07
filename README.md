# Halo

An open media center — think Stremio, but with device-local downloads for offline
watching. Reuses the [Stremio addon protocol](https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/protocol.md),
so existing addons (Cinemeta, OpenSubtitles, debrid stream addons, …) work as-is.

Mobile-first: the iOS app is the current focus. Native desktop apps
(Windows/macOS) with an embedded player come later. API hosted (eventually) at
`halo.ditto.moe`.

## Layout

| Path            | What                                                                 |
| --------------- | -------------------------------------------------------------------- |
| `packages/core` | Shared TypeScript: Stremio addon client, subtitle utilities (OpenSubtitles hash, SRT→VTT), typed Halo API client |
| `apps/api`      | Node backend (Hono + SQLite). Watch-state, library & addon-collection sync, CORS/subtitle proxy. Never stores media. |
| `apps/mobile`   | iOS app (Expo / React Native). VLC-based player (MKV, multi-audio, subs), offline downloads. |

There is deliberately no browser web app: browser playback of MKV/HEVC debrid
streams is fractured (no Firefox MKV demuxing, hardware-dependent HEVC) — same
story as Stremio web. Desktop is instead planned as native Windows/macOS apps
(`apps/desktop`, future) with an embedded player (mpv or libVLC), which plays
everything. `packages/core` and the API are client-agnostic, so nothing here
blocks that.

## Development

```sh
pnpm install
cp apps/api/.env.example apps/api/.env   # set ADMIN_PASSWORD + JWT_SECRET
pnpm dev                                 # api on :8787
```

### iOS

The app uses libVLC (native module), so Expo Go does not work — build a dev client:

```sh
pnpm --filter @halo/mobile exec expo prebuild --platform ios
pnpm --filter @halo/mobile exec expo run:ios
```

## Notes / known constraints

- **Downloads are device-local only.** The server never stores media.
- Torrents are out of scope; streams come from debrid/HTTP addons (direct URLs).
- Foreground/backgrounded downloads work; downloads do not survive iOS killing
  the app (true background URLSession downloads are a flagged follow-up).
