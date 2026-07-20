# Compose mobile controlled fixtures

This directory contains a Python 3 standard-library server for exercising the
native authentication and media transport gates without a production identity
provider or production media service.

## Run

From the repository root:

```powershell
python apps/mobile-native/fixtures/fixture_server.py
```

The default bind address is `127.0.0.1:18787`, OIDC mode is enabled, and media
is read from the ignored `apps/mobile-native/fixtures/media/` directory.
All three are configurable:

```powershell
python apps/mobile-native/fixtures/fixture_server.py `
  --host 192.168.1.20 `
  --port 18787 `
  --media-dir C:\path\to\media `
  --auth-mode oidc
```

For a physical device, bind the server to the host machine's LAN address (not
`127.0.0.1` or `0.0.0.0`) so the advertised issuer and endpoints contain an
address the device can reach. The custom redirect URI is fixed to
`halo://oauth/callback`.

Local-mode discovery can be exercised with `--auth-mode local`. It returns the
exact Halo `{ "mode": "local" }` wire response. This fixture deliberately does
not implement a password login endpoint: the current gate needs mode discovery
and OIDC proof, and adding even fake password semantics would create an
unnecessary surface that could be mistaken for reusable auth code.

## Endpoints

- `GET /health` returns a minimal liveness response.
- `GET /status` returns configuration, pending transaction count, and the
  structured in-memory request log.
- `GET /auth/config` returns Halo's exact local or OIDC auth-mode union.
- `GET /.well-known/openid-configuration` advertises `/authorize/` and
  `/token/` with their required trailing slashes.
- `GET /authorize/` validates the authorization-code PKCE request and redirects
  immediately to `halo://oauth/callback?code=...&state=...`.
- `POST /token/` accepts `application/x-www-form-urlencoded`, validates the
  one-time authorization code and S256 verifier, and returns a deliberately
  non-production access-token proof.
- `GET|HEAD /media/<relative-path>` serves regular files beneath the configured
  media root, including single byte ranges.

`/authorize` and `/token` without trailing slashes intentionally return 404.
This catches client libraries that strip the slash and trigger the same class
of failure as Django/Authentik's APPEND_SLASH behavior.

## Negative modes

Select a mode globally for a native-app run:

```powershell
python apps/mobile-native/fixtures/fixture_server.py --negative-mode state_mismatch
```

Or select it for one request with `fixture_mode=<mode>`. Supported modes are:

- `normal`
- `state_mismatch`
- `missing_code`
- `malformed_discovery`
- `malformed_token_json`
- `http_error` (deterministic HTTP 503 on the selected route)

A global HTTP error can target `health`, `status`, `auth_config`, `discovery`,
`authorize`, `token`, or `media`, allowing a native client to complete the
preceding steps before reaching the intended failure:

```powershell
python apps/mobile-native/fixtures/fixture_server.py `
  --negative-mode http_error `
  --http-error-route token
```

The default HTTP-error target is `any`. A query-selected `fixture_mode=http_error`
always applies to that request regardless of the global route target.
Authorization and token request validation still runs before the applicable
negative response. For example, `malformed_token_json` does not turn an invalid
verifier into a successful exchange.

### How the native OIDC client selects a mode

`OidcAuthHost` (in `iosApp/`) reads two launch-environment variables and appends
`fixture_mode` per-request, so the server can stay in its default mode and one
app launch drives one case:

- `HALO_OIDC_FIXTURE_MODE=<mode>` — for every mode except `http_error`, appended
  to discovery, authorize, and token; each route acts only on the mode meant for
  it and ignores the rest.
- `HALO_OIDC_FIXTURE_HTTP_ROUTE=discovery|token` — with `HALO_OIDC_FIXTURE_MODE=http_error`,
  targets the 503 at a single route (appending to all would just 503 discovery
  first). `authorize` is not targeted: a 503 there strands the browser on an error
  page instead of producing a clean client error.
- `HALO_OIDC_EPHEMERAL=0` — makes the browser session non-ephemeral so iOS shows
  the "Sign In" consent (an ephemeral session suppresses it and the fixture
  auto-redirects, leaving nothing to dismiss). Only the user-cancel test needs it.

All three are absent in normal/production runs, so real requests go out clean.

## Media fixtures

The `media/` directory is ignored. Put local test files there when needed; do
not commit licensed media or large 4K samples.

The server supports:

- full GET and HEAD responses;
- `Accept-Ranges: bytes`;
- bounded, open-ended, and suffix single ranges with HTTP 206;
- `Content-Range` and exact `Content-Length`;
- HTTP 416 with `Content-Range: bytes */<size>` for malformed or unsatisfiable
  ranges.

Resolved paths must remain beneath the media root, and only regular files are
served. Traversal attempts are rejected even when `..` or path separators are
percent-encoded.

## Test

From the repository root:

```powershell
python -m unittest discover -s apps/mobile-native/fixtures/tests -v
```

The tests start real loopback servers on ephemeral ports and exercise the HTTP
surface, redirects, PKCE exchange, negative cases, range behavior, and request
log redaction.

## Security boundaries

This is test infrastructure, not an identity provider or media server:

- It binds to loopback by default. Binding to a LAN address exposes it to that
  network and should be temporary.
- It uses no TLS, user database, cookies, client secrets, refresh tokens,
  signing keys, or real credentials.
- Authorization transactions live only in memory, expire quickly, and are
  consumed after a successful exchange.
- Returned access-token strings are fixture proofs with no signature and no
  authority anywhere.
- Request logs redact states, authorization codes, PKCE challenges/verifiers,
  passwords, secrets, and tokens. Logs are in memory plus JSON lines on stdout.
- Media containment is a fixture safety boundary, not a replacement for the
  production API's SSRF, authorization, or storage controls.
