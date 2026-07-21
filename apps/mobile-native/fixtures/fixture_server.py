#!/usr/bin/env python3
"""Controlled HTTP/OIDC/media fixture for the Compose mobile native gate."""

from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import mimetypes
import re
import secrets
import sys
import threading
import time
from dataclasses import dataclass
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Callable, Mapping
from urllib.parse import parse_qs, urlencode, unquote, urlsplit

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 18787
DEFAULT_CLIENT_ID = "halo-mobile-fixture"
REDIRECT_URI = "halo://oauth/callback"
OIDC_SCOPES = ["openid", "profile", "email", "offline_access", "groups"]
NEGATIVE_MODES = {
    "normal",
    "state_mismatch",
    "missing_code",
    "malformed_discovery",
    "malformed_token_json",
    "http_error",
}
HTTP_ERROR_ROUTES = {
    "any",
    "health",
    "status",
    "auth_config",
    "discovery",
    "authorize",
    "token",
    "media",
    "local_login",
    "local_refresh",
}
# The only credentials the local-mode login accepts. Fixed values keep every
# consumer (Python tests, XCUITest, manual runs) on the same known-good pair.
LOCAL_USERNAME = "fixture-user"
LOCAL_PASSWORD = "fixture-pass"
# Mirrors the API's 30-day session tokens; override with --local-token-ttl to
# force refresh-band behavior in tests.
DEFAULT_LOCAL_TOKEN_TTL_SECONDS = 30 * 24 * 60 * 60.0
REDACTED = "[REDACTED]"
SENSITIVE_FIELD_PARTS = ("authorization", "challenge", "code", "password", "secret", "state", "token", "verifier")
PKCE_CHALLENGE_RE = re.compile(r"^[A-Za-z0-9_-]{43}$")
RANGE_RE = re.compile(r"^bytes=(\d*)-(\d*)$")
MAX_FORM_BYTES = 64 * 1024


@dataclass(frozen=True)
class AuthorizationTransaction:
    client_id: str
    redirect_uri: str
    state: str
    code_challenge: str
    expires_at: float


def _redact_mapping(values: Mapping[str, object]) -> dict[str, object]:
    redacted: dict[str, object] = {}
    for key, value in values.items():
        if any(part in key.lower() for part in SENSITIVE_FIELD_PARTS):
            redacted[key] = REDACTED
        else:
            redacted[key] = value
    return redacted


def _single_value(values: Mapping[str, list[str]], name: str) -> str:
    candidates = values.get(name, [])
    if len(candidates) != 1 or not candidates[0]:
        raise ValueError(f"exactly one non-empty {name} is required")
    return candidates[0]


def _pkce_challenge(verifier: str) -> str:
    digest = hashlib.sha256(verifier.encode("ascii")).digest()
    return base64.urlsafe_b64encode(digest).rstrip(b"=").decode("ascii")


class FixtureHTTPServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(
        self,
        server_address: tuple[str, int],
        media_dir: Path,
        auth_mode: str,
        negative_mode: str,
        http_error_route: str,
        transaction_ttl_seconds: float,
        local_token_ttl_seconds: float,
        emit_logs: bool,
        clock: Callable[[], float],
    ) -> None:
        super().__init__(server_address, FixtureRequestHandler)
        self.media_dir = media_dir.resolve()
        self.auth_mode = auth_mode
        self.negative_mode = negative_mode
        self.http_error_route = http_error_route
        self.transaction_ttl_seconds = transaction_ttl_seconds
        self.local_token_ttl_seconds = local_token_ttl_seconds
        self.emit_logs = emit_logs
        self.clock = clock
        self.transactions: dict[str, AuthorizationTransaction] = {}
        self.transaction_lock = threading.Lock()
        self.request_log: list[dict[str, object]] = []
        self.request_log_lock = threading.Lock()
        # Local-mode session tokens: token -> expiry (server-clock seconds).
        # Tokens stay valid after a refresh, matching the API: refresh issues a
        # fresh token but does not revoke the one that authenticated it.
        self.local_tokens: dict[str, float] = {}
        self.local_token_lock = threading.Lock()
        self._local_token_counter = 0

    def issue_local_token(self) -> tuple[str, float]:
        """Mints a local session token; returns (token, expiry in seconds)."""
        with self.local_token_lock:
            self._remove_expired_local_tokens()
            self._local_token_counter += 1
            # The counter makes rotation observable: a refreshed token is
            # visibly distinct from the login token it replaced.
            token = f"fixture-local-token-{self._local_token_counter}-{secrets.token_urlsafe(12)}"
            expires_at = self.clock() + self.local_token_ttl_seconds
            self.local_tokens[token] = expires_at
            return token, expires_at

    def local_token_is_valid(self, token: str) -> bool:
        with self.local_token_lock:
            self._remove_expired_local_tokens()
            return token in self.local_tokens

    def _remove_expired_local_tokens(self) -> None:
        now = self.clock()
        expired = [token for token, expires_at in self.local_tokens.items() if expires_at <= now]
        for token in expired:
            del self.local_tokens[token]

    @property
    def base_url(self) -> str:
        host, port = self.server_address[:2]
        display_host = "127.0.0.1" if host in {"0.0.0.0", "::"} else str(host)
        return f"http://{display_host}:{port}"

    @property
    def issuer(self) -> str:
        return f"{self.base_url}/"

    def issue_code(self, transaction: AuthorizationTransaction) -> str:
        with self.transaction_lock:
            self._remove_expired_transactions()
            code = secrets.token_urlsafe(24)
            self.transactions[code] = transaction
            return code

    def redeem_code(
        self,
        code: str,
        client_id: str,
        redirect_uri: str,
        verifier: str,
    ) -> None:
        try:
            actual_challenge = _pkce_challenge(verifier)
        except (UnicodeEncodeError, ValueError) as error:
            raise ValueError("invalid code_verifier") from error

        with self.transaction_lock:
            self._remove_expired_transactions()
            transaction = self.transactions.get(code)
            if transaction is None:
                raise ValueError("unknown or expired authorization code")
            if transaction.client_id != client_id:
                raise ValueError("client_id does not match authorization request")
            if transaction.redirect_uri != redirect_uri:
                raise ValueError("redirect_uri does not match authorization request")
            if not hmac.compare_digest(transaction.code_challenge, actual_challenge):
                raise ValueError("PKCE verification failed")
            del self.transactions[code]

    def _remove_expired_transactions(self) -> None:
        now = self.clock()
        expired = [code for code, transaction in self.transactions.items() if transaction.expires_at <= now]
        for code in expired:
            del self.transactions[code]

    def add_request_log(self, entry: dict[str, object]) -> None:
        with self.request_log_lock:
            self.request_log.append(entry)
        if self.emit_logs:
            print(json.dumps({"event": "fixture_request", **entry}, sort_keys=True), flush=True)

    def status_snapshot(self) -> dict[str, object]:
        with self.transaction_lock:
            self._remove_expired_transactions()
            transaction_count = len(self.transactions)
        with self.local_token_lock:
            self._remove_expired_local_tokens()
            local_token_count = len(self.local_tokens)
        with self.request_log_lock:
            requests = list(self.request_log)
        return {
            "ok": True,
            "activeLocalTokens": local_token_count,
            "authMode": self.auth_mode,
            "negativeMode": self.negative_mode,
            "httpErrorRoute": self.http_error_route,
            "issuer": self.issuer,
            "mediaDirectory": str(self.media_dir),
            "pendingTransactions": transaction_count,
            "requests": requests,
        }


class FixtureRequestHandler(BaseHTTPRequestHandler):
    server: FixtureHTTPServer
    protocol_version = "HTTP/1.1"

    def do_GET(self) -> None:
        self._dispatch(send_body=True)

    def do_HEAD(self) -> None:
        self._dispatch(send_body=False)

    def do_POST(self) -> None:
        self._dispatch(send_body=True)

    def log_message(self, _format: str, *args: object) -> None:
        # Request logs are emitted as structured JSON by _dispatch instead.
        return

    def _dispatch(self, send_body: bool) -> None:
        started_at = self.server.clock()
        parsed = urlsplit(self.path)
        query = parse_qs(parsed.query, keep_blank_values=True)
        self._response_status = HTTPStatus.INTERNAL_SERVER_ERROR
        self._logged_form: dict[str, object] | None = None

        try:
            mode = self._negative_mode(query)
            if self.command == "GET" and parsed.path == "/health":
                self._handle_health(mode, send_body)
                return
            if self.command == "GET" and parsed.path == "/status":
                self._handle_status(mode, send_body)
                return
            if self.command == "GET" and parsed.path == "/auth/config":
                self._handle_auth_config(mode, send_body)
                return
            if self.command == "GET" and parsed.path == "/.well-known/openid-configuration":
                self._handle_discovery(mode, send_body)
                return
            if self.command == "GET" and parsed.path == "/authorize/":
                self._handle_authorize(query, mode)
                return
            if self.command == "POST" and parsed.path == "/token/":
                self._handle_token(mode, send_body)
                return
            if self.command == "POST" and parsed.path == "/auth/login":
                self._handle_local_login(mode, send_body)
                return
            if self.command == "POST" and parsed.path == "/auth/refresh":
                self._handle_local_refresh(mode, send_body)
                return
            if self.command in {"GET", "HEAD"} and parsed.path.startswith("/media/"):
                self._handle_media(parsed.path, mode, send_body)
                return
            self._send_json(HTTPStatus.NOT_FOUND, {"error": "not_found"}, send_body)
        except ValueError as error:
            self._send_json(HTTPStatus.BAD_REQUEST, {"error": "invalid_request", "error_description": str(error)}, send_body)
        except (BrokenPipeError, ConnectionResetError):
            pass
        finally:
            duration_ms = round((self.server.clock() - started_at) * 1000, 3)
            query_for_log = {key: value if len(value) != 1 else value[0] for key, value in query.items()}
            entry: dict[str, object] = {
                "timestamp": started_at,
                "durationMs": duration_ms,
                "method": self.command,
                "path": parsed.path,
                "query": _redact_mapping(query_for_log),
                "status": int(self._response_status),
            }
            if self._logged_form is not None:
                entry["form"] = _redact_mapping(self._logged_form)
            self.server.add_request_log(entry)

    def _negative_mode(self, query: Mapping[str, list[str]]) -> str:
        requested = query.get("fixture_mode")
        self._negative_mode_is_query = requested is not None
        if requested is None:
            return self.server.negative_mode
        if len(requested) != 1 or requested[0] not in NEGATIVE_MODES:
            raise ValueError("fixture_mode is invalid")
        return requested[0]

    def _should_http_error(self, mode: str, route: str) -> bool:
        if mode != "http_error":
            return False
        return self._negative_mode_is_query or self.server.http_error_route in {"any", route}

    def _handle_health(self, mode: str, send_body: bool) -> None:
        if self._should_http_error(mode, "health"):
            self._send_fixture_http_error(send_body)
            return
        self._send_json(HTTPStatus.OK, {"ok": True}, send_body)

    def _handle_status(self, mode: str, send_body: bool) -> None:
        if self._should_http_error(mode, "status"):
            self._send_fixture_http_error(send_body)
            return
        self._send_json(HTTPStatus.OK, self.server.status_snapshot(), send_body)

    def _handle_auth_config(self, mode: str, send_body: bool) -> None:
        if self._should_http_error(mode, "auth_config"):
            self._send_fixture_http_error(send_body)
            return
        if self.server.auth_mode == "local":
            self._send_json(HTTPStatus.OK, {"mode": "local"}, send_body)
            return
        self._send_json(
            HTTPStatus.OK,
            {
                "mode": "oidc",
                "issuer": self.server.issuer,
                "clientId": DEFAULT_CLIENT_ID,
                "scopes": OIDC_SCOPES,
            },
            send_body,
        )

    def _handle_discovery(self, mode: str, send_body: bool) -> None:
        if self._should_http_error(mode, "discovery"):
            self._send_fixture_http_error(send_body)
            return
        if mode == "malformed_discovery":
            self._send_bytes(HTTPStatus.OK, b'{"issuer":', "application/json", send_body)
            return
        self._send_json(
            HTTPStatus.OK,
            {
                "issuer": self.server.issuer,
                "authorization_endpoint": f"{self.server.base_url}/authorize/",
                "token_endpoint": f"{self.server.base_url}/token/",
                "response_types_supported": ["code"],
                "grant_types_supported": ["authorization_code"],
                "token_endpoint_auth_methods_supported": ["none"],
                "code_challenge_methods_supported": ["S256"],
            },
            send_body,
        )

    def _handle_authorize(self, query: Mapping[str, list[str]], mode: str) -> None:
        response_type = _single_value(query, "response_type")
        client_id = _single_value(query, "client_id")
        redirect_uri = _single_value(query, "redirect_uri")
        state = _single_value(query, "state")
        code_challenge = _single_value(query, "code_challenge")
        challenge_method = _single_value(query, "code_challenge_method")

        if response_type != "code":
            raise ValueError("response_type must be code")
        if client_id != DEFAULT_CLIENT_ID:
            raise ValueError("client_id is invalid")
        if redirect_uri != REDIRECT_URI:
            raise ValueError(f"redirect_uri must be {REDIRECT_URI}")
        if challenge_method != "S256":
            raise ValueError("code_challenge_method must be S256")
        if not PKCE_CHALLENGE_RE.fullmatch(code_challenge):
            raise ValueError("code_challenge must be a SHA-256 base64url value")
        if self._should_http_error(mode, "authorize"):
            self._send_fixture_http_error(send_body=True)
            return
        if mode == "missing_code":
            self._send_redirect(f"{redirect_uri}?{urlencode({'state': state})}")
            return

        transaction = AuthorizationTransaction(
            client_id=client_id,
            redirect_uri=redirect_uri,
            state=state,
            code_challenge=code_challenge,
            expires_at=self.server.clock() + self.server.transaction_ttl_seconds,
        )
        code = self.server.issue_code(transaction)
        returned_state = f"{state}-mismatch" if mode == "state_mismatch" else state
        self._send_redirect(f"{redirect_uri}?{urlencode({'code': code, 'state': returned_state})}")

    def _handle_token(self, mode: str, send_body: bool) -> None:
        content_type = self.headers.get("Content-Type", "")
        if content_type.split(";", 1)[0].strip().lower() != "application/x-www-form-urlencoded":
            raise ValueError("Content-Type must be application/x-www-form-urlencoded")

        content_length_text = self.headers.get("Content-Length")
        if content_length_text is None:
            raise ValueError("Content-Length is required")
        try:
            content_length = int(content_length_text)
        except ValueError as error:
            raise ValueError("Content-Length is invalid") from error
        if content_length < 0 or content_length > MAX_FORM_BYTES:
            raise ValueError("form body is too large")

        raw_body = self.rfile.read(content_length)
        try:
            body_text = raw_body.decode("utf-8")
        except UnicodeDecodeError as error:
            raise ValueError("form body must be UTF-8") from error
        form = parse_qs(body_text, keep_blank_values=True)
        self._logged_form = {key: value if len(value) != 1 else value[0] for key, value in form.items()}

        grant_type = _single_value(form, "grant_type")
        client_id = _single_value(form, "client_id")
        redirect_uri = _single_value(form, "redirect_uri")
        code = _single_value(form, "code")
        verifier = _single_value(form, "code_verifier")
        if grant_type != "authorization_code":
            raise ValueError("grant_type must be authorization_code")
        if client_id != DEFAULT_CLIENT_ID:
            raise ValueError("client_id is invalid")
        if redirect_uri != REDIRECT_URI:
            raise ValueError(f"redirect_uri must be {REDIRECT_URI}")

        try:
            self.server.redeem_code(code, client_id, redirect_uri, verifier)
        except ValueError as error:
            self._send_json(
                HTTPStatus.BAD_REQUEST,
                {"error": "invalid_grant", "error_description": str(error)},
                send_body,
            )
            return

        if self._should_http_error(mode, "token"):
            self._send_fixture_http_error(send_body)
            return
        if mode == "malformed_token_json":
            self._send_bytes(HTTPStatus.OK, b'{"access_token":', "application/json", send_body)
            return

        proof_digest = hashlib.sha256(code.encode("ascii")).hexdigest()[:16]
        self._send_json(
            HTTPStatus.OK,
            {
                "access_token": f"fixture-access-proof-{proof_digest}",
                "token_type": "Bearer",
                "expires_in": 300,
            },
            send_body,
        )

    def _read_json_body(self) -> dict[str, object]:
        content_type = self.headers.get("Content-Type", "")
        if content_type.split(";", 1)[0].strip().lower() != "application/json":
            raise ValueError("Content-Type must be application/json")
        content_length_text = self.headers.get("Content-Length")
        if content_length_text is None:
            raise ValueError("Content-Length is required")
        try:
            content_length = int(content_length_text)
        except ValueError as error:
            raise ValueError("Content-Length is invalid") from error
        if content_length < 0 or content_length > MAX_FORM_BYTES:
            raise ValueError("body is too large")
        raw_body = self.rfile.read(content_length)
        try:
            payload = json.loads(raw_body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise ValueError("body must be valid JSON") from error
        if not isinstance(payload, dict):
            raise ValueError("body must be a JSON object")
        self._logged_form = payload
        return payload

    def _bearer_token(self) -> str | None:
        authorization = self.headers.get("Authorization", "")
        scheme, _, credentials = authorization.partition(" ")
        if scheme.lower() != "bearer" or not credentials.strip():
            return None
        return credentials.strip()

    def _send_issued_local_token(self, send_body: bool) -> None:
        token, expires_at_seconds = self.server.issue_local_token()
        # expiresAt is epoch milliseconds on the wire, like the real API.
        self._send_json(
            HTTPStatus.OK,
            {"token": token, "expiresAt": int(expires_at_seconds * 1000)},
            send_body,
        )

    def _handle_local_login(self, mode: str, send_body: bool) -> None:
        # Local auth endpoints exist only in local mode, mirroring the API's
        # deployment-exclusive AUTH_MODE route registration.
        if self.server.auth_mode != "local":
            self._send_json(HTTPStatus.NOT_FOUND, {"error": "not_found"}, send_body)
            return
        payload = self._read_json_body()
        if self._should_http_error(mode, "local_login"):
            self._send_fixture_http_error(send_body)
            return
        username = payload.get("username")
        password = payload.get("password")
        if username != LOCAL_USERNAME or password != LOCAL_PASSWORD:
            # Exact body the API sends for a bad login.
            self._send_json(HTTPStatus.UNAUTHORIZED, {"error": "invalid credentials"}, send_body)
            return
        self._send_issued_local_token(send_body)

    def _handle_local_refresh(self, mode: str, send_body: bool) -> None:
        if self.server.auth_mode != "local":
            self._send_json(HTTPStatus.NOT_FOUND, {"error": "not_found"}, send_body)
            return
        if self._should_http_error(mode, "local_refresh"):
            self._send_fixture_http_error(send_body)
            return
        token = self._bearer_token()
        if token is None or not self.server.local_token_is_valid(token):
            self._send_json(HTTPStatus.UNAUTHORIZED, {"error": "invalid token"}, send_body)
            return
        self._send_issued_local_token(send_body)

    def _handle_media(self, request_path: str, mode: str, send_body: bool) -> None:
        relative_text = unquote(request_path[len("/media/") :])
        if not relative_text or "\x00" in relative_text:
            raise ValueError("media path is invalid")
        candidate = (self.server.media_dir / relative_text).resolve()
        try:
            candidate.relative_to(self.server.media_dir)
        except ValueError:
            self._send_json(HTTPStatus.FORBIDDEN, {"error": "path_traversal_rejected"}, send_body)
            return
        if not candidate.is_file():
            self._send_json(HTTPStatus.NOT_FOUND, {"error": "media_not_found"}, send_body)
            return
        if self._should_http_error(mode, "media"):
            self._send_fixture_http_error(send_body)
            return

        size = candidate.stat().st_size
        range_header = self.headers.get("Range")
        start = 0
        end = size - 1
        status = HTTPStatus.OK
        if range_header is not None:
            parsed_range = self._parse_range(range_header, size)
            if parsed_range is None:
                self._send_range_not_satisfiable(size, send_body)
                return
            start, end = parsed_range
            status = HTTPStatus.PARTIAL_CONTENT

        content_length = max(0, end - start + 1)
        self._response_status = status
        self.send_response(status)
        self.send_header("Content-Type", mimetypes.guess_type(candidate.name)[0] or "application/octet-stream")
        self.send_header("Content-Length", str(content_length))
        self.send_header("Accept-Ranges", "bytes")
        if status == HTTPStatus.PARTIAL_CONTENT:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if not send_body or content_length == 0:
            return
        with candidate.open("rb") as media_file:
            media_file.seek(start)
            remaining = content_length
            while remaining:
                chunk = media_file.read(min(64 * 1024, remaining))
                if not chunk:
                    break
                self.wfile.write(chunk)
                remaining -= len(chunk)

    @staticmethod
    def _parse_range(value: str, size: int) -> tuple[int, int] | None:
        match = RANGE_RE.fullmatch(value.strip())
        if match is None or size == 0:
            return None
        start_text, end_text = match.groups()
        if not start_text and not end_text:
            return None
        if not start_text:
            suffix_length = int(end_text)
            if suffix_length <= 0:
                return None
            return max(0, size - suffix_length), size - 1

        start = int(start_text)
        if start >= size:
            return None
        if not end_text:
            return start, size - 1
        end = int(end_text)
        if end < start:
            return None
        return start, min(end, size - 1)

    def _send_range_not_satisfiable(self, size: int, send_body: bool) -> None:
        body = json.dumps({"error": "range_not_satisfiable"}, separators=(",", ":")).encode("utf-8")
        self._response_status = HTTPStatus.REQUESTED_RANGE_NOT_SATISFIABLE
        self.send_response(HTTPStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Content-Range", f"bytes */{size}")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if send_body:
            self.wfile.write(body)

    def _send_fixture_http_error(self, send_body: bool) -> None:
        self._send_json(
            HTTPStatus.SERVICE_UNAVAILABLE,
            {"error": "fixture_http_error", "error_description": "deliberate fixture failure"},
            send_body,
        )

    def _send_redirect(self, location: str) -> None:
        self._response_status = HTTPStatus.FOUND
        self.send_response(HTTPStatus.FOUND)
        self.send_header("Location", location)
        self.send_header("Content-Length", "0")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()

    def _send_json(self, status: HTTPStatus, payload: object, send_body: bool) -> None:
        body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        self._send_bytes(status, body, "application/json", send_body)

    def _send_bytes(
        self,
        status: HTTPStatus,
        body: bytes,
        content_type: str,
        send_body: bool,
    ) -> None:
        self._response_status = status
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if send_body:
            self.wfile.write(body)


def create_server(
    host: str = DEFAULT_HOST,
    port: int = DEFAULT_PORT,
    media_dir: Path | str | None = None,
    auth_mode: str = "oidc",
    negative_mode: str = "normal",
    http_error_route: str = "any",
    transaction_ttl_seconds: float = 120.0,
    local_token_ttl_seconds: float = DEFAULT_LOCAL_TOKEN_TTL_SECONDS,
    emit_logs: bool = True,
    clock: Callable[[], float] = time.time,
) -> FixtureHTTPServer:
    if auth_mode not in {"local", "oidc"}:
        raise ValueError("auth_mode must be local or oidc")
    if negative_mode not in NEGATIVE_MODES:
        raise ValueError(f"negative_mode must be one of: {', '.join(sorted(NEGATIVE_MODES))}")
    if http_error_route not in HTTP_ERROR_ROUTES:
        raise ValueError(f"http_error_route must be one of: {', '.join(sorted(HTTP_ERROR_ROUTES))}")
    if transaction_ttl_seconds <= 0:
        raise ValueError("transaction_ttl_seconds must be positive")
    if local_token_ttl_seconds <= 0:
        raise ValueError("local_token_ttl_seconds must be positive")
    resolved_media_dir = Path(media_dir) if media_dir is not None else Path(__file__).with_name("media")
    return FixtureHTTPServer(
        (host, port),
        resolved_media_dir,
        auth_mode,
        negative_mode,
        http_error_route,
        transaction_ttl_seconds,
        local_token_ttl_seconds,
        emit_logs,
        clock,
    )


def _parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default=DEFAULT_HOST, help=f"bind host (default: {DEFAULT_HOST})")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help=f"bind port (default: {DEFAULT_PORT})")
    parser.add_argument(
        "--media-dir",
        type=Path,
        default=Path(__file__).with_name("media"),
        help="directory exposed below /media/ (default: fixtures/media)",
    )
    parser.add_argument("--auth-mode", choices=("local", "oidc"), default="oidc")
    parser.add_argument("--negative-mode", choices=sorted(NEGATIVE_MODES), default="normal")
    parser.add_argument(
        "--http-error-route",
        choices=sorted(HTTP_ERROR_ROUTES),
        default="any",
        help="route targeted when --negative-mode=http_error (default: any)",
    )
    parser.add_argument("--transaction-ttl", type=float, default=120.0, metavar="SECONDS")
    parser.add_argument(
        "--local-token-ttl",
        type=float,
        default=DEFAULT_LOCAL_TOKEN_TTL_SECONDS,
        metavar="SECONDS",
        help="lifetime of local-mode session tokens (default: 30 days)",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = _parse_args(sys.argv[1:] if argv is None else argv)
    server = create_server(
        host=args.host,
        port=args.port,
        media_dir=args.media_dir,
        auth_mode=args.auth_mode,
        negative_mode=args.negative_mode,
        http_error_route=args.http_error_route,
        transaction_ttl_seconds=args.transaction_ttl,
        local_token_ttl_seconds=args.local_token_ttl,
    )
    print(
        json.dumps(
            {
                "event": "fixture_started",
                "baseUrl": server.base_url,
                "authMode": server.auth_mode,
                "negativeMode": server.negative_mode,
                "httpErrorRoute": server.http_error_route,
                "mediaDirectory": str(server.media_dir),
            },
            sort_keys=True,
        ),
        flush=True,
    )
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
