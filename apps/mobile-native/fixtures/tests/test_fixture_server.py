from __future__ import annotations

import base64
import hashlib
import http.client
import json
import sys
import tempfile
import threading
import unittest
from contextlib import contextmanager
from pathlib import Path
from typing import Iterator
from urllib.parse import parse_qs, urlencode, urlsplit

COMPOSE_MOBILE_DIR = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(COMPOSE_MOBILE_DIR))

from fixtures.fixture_server import (
    DEFAULT_CLIENT_ID,
    OIDC_SCOPES,
    REDACTED,
    REDIRECT_URI,
    FixtureHTTPServer,
    create_server,
)

VERIFIER = "fixture-verifier-0123456789-ABCDEFGHIJKLMNOPQRSTUVWXYZ"
MEDIA_BYTES = b"0123456789abcdefghijklmnopqrstuvwxyz"


def pkce_challenge(verifier: str = VERIFIER) -> str:
    digest = hashlib.sha256(verifier.encode("ascii")).digest()
    return base64.urlsafe_b64encode(digest).rstrip(b"=").decode("ascii")


@contextmanager
def running_server(
    media_dir: Path,
    *,
    auth_mode: str = "oidc",
    negative_mode: str = "normal",
    http_error_route: str = "any",
) -> Iterator[FixtureHTTPServer]:
    server = create_server(
        host="127.0.0.1",
        port=0,
        media_dir=media_dir,
        auth_mode=auth_mode,
        negative_mode=negative_mode,
        http_error_route=http_error_route,
        emit_logs=False,
    )
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield server
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


class FixtureServerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp_dir.cleanup)
        self.temp_path = Path(self.temp_dir.name)
        self.media_dir = self.temp_path / "media"
        self.media_dir.mkdir()
        (self.media_dir / "sample.bin").write_bytes(MEDIA_BYTES)
        (self.media_dir / "nested").mkdir()
        (self.media_dir / "nested" / "clip.bin").write_bytes(b"nested-media")
        (self.temp_path / "outside.bin").write_bytes(b"outside")

    def request(
        self,
        server: FixtureHTTPServer,
        method: str,
        path: str,
        *,
        body: bytes | str | None = None,
        headers: dict[str, str] | None = None,
    ) -> tuple[int, dict[str, str], bytes]:
        host, port = server.server_address[:2]
        connection = http.client.HTTPConnection(host, port, timeout=5)
        try:
            connection.request(method, path, body=body, headers=headers or {})
            response = connection.getresponse()
            return response.status, dict(response.getheaders()), response.read()
        finally:
            connection.close()

    def authorize(
        self,
        server: FixtureHTTPServer,
        *,
        verifier: str = VERIFIER,
        state: str = "fixture-state",
        fixture_mode: str | None = None,
        overrides: dict[str, str] | None = None,
    ) -> tuple[int, dict[str, str], bytes]:
        query = {
            "response_type": "code",
            "client_id": DEFAULT_CLIENT_ID,
            "redirect_uri": REDIRECT_URI,
            "state": state,
            "code_challenge": pkce_challenge(verifier),
            "code_challenge_method": "S256",
        }
        if fixture_mode is not None:
            query["fixture_mode"] = fixture_mode
        if overrides:
            query.update(overrides)
        return self.request(server, "GET", f"/authorize/?{urlencode(query)}")

    def authorization_params(self, headers: dict[str, str]) -> dict[str, list[str]]:
        location = headers["Location"]
        parsed = urlsplit(location)
        self.assertEqual("halo", parsed.scheme)
        self.assertEqual("oauth", parsed.netloc)
        self.assertEqual("/callback", parsed.path)
        return parse_qs(parsed.query, keep_blank_values=True)

    def token(
        self,
        server: FixtureHTTPServer,
        code: str,
        *,
        verifier: str = VERIFIER,
        fixture_mode: str | None = None,
        overrides: dict[str, str] | None = None,
    ) -> tuple[int, dict[str, str], bytes]:
        form = {
            "grant_type": "authorization_code",
            "client_id": DEFAULT_CLIENT_ID,
            "redirect_uri": REDIRECT_URI,
            "code": code,
            "code_verifier": verifier,
        }
        if overrides:
            form.update(overrides)
        suffix = "" if fixture_mode is None else f"?{urlencode({'fixture_mode': fixture_mode})}"
        return self.request(
            server,
            "POST",
            f"/token/{suffix}",
            body=urlencode(form),
            headers={"Content-Type": "application/x-www-form-urlencoded"},
        )

    def valid_code(self, server: FixtureHTTPServer, *, verifier: str = VERIFIER) -> str:
        status, headers, _ = self.authorize(server, verifier=verifier)
        self.assertEqual(302, status)
        return self.authorization_params(headers)["code"][0]

    def test_auth_config_matches_halo_wire_contract_in_both_modes(self) -> None:
        with running_server(self.media_dir) as server:
            status, _, body = self.request(server, "GET", "/auth/config")
            self.assertEqual(200, status)
            self.assertEqual(
                {
                    "mode": "oidc",
                    "issuer": server.issuer,
                    "clientId": DEFAULT_CLIENT_ID,
                    "scopes": OIDC_SCOPES,
                },
                json.loads(body),
            )
        with running_server(self.media_dir, auth_mode="local") as server:
            status, _, body = self.request(server, "GET", "/auth/config")
            self.assertEqual(200, status)
            self.assertEqual({"mode": "local"}, json.loads(body))

    def test_discovery_advertises_exact_trailing_slash_routes(self) -> None:
        with running_server(self.media_dir) as server:
            status, _, body = self.request(server, "GET", "/.well-known/openid-configuration")
            self.assertEqual(200, status)
            discovery = json.loads(body)
            self.assertEqual(f"{server.base_url}/authorize/", discovery["authorization_endpoint"])
            self.assertEqual(f"{server.base_url}/token/", discovery["token_endpoint"])

            status, _, _ = self.request(server, "GET", "/authorize")
            self.assertEqual(404, status)
            status, _, _ = self.request(
                server,
                "POST",
                "/token",
                body="",
                headers={"Content-Type": "application/x-www-form-urlencoded"},
            )
            self.assertEqual(404, status)

    def test_pkce_authorization_code_exchange_succeeds_once(self) -> None:
        with running_server(self.media_dir) as server:
            status, headers, _ = self.authorize(server)
            self.assertEqual(302, status)
            redirect = self.authorization_params(headers)
            self.assertEqual(["fixture-state"], redirect["state"])
            code = redirect["code"][0]

            status, _, body = self.token(server, code)
            self.assertEqual(200, status)
            token = json.loads(body)
            self.assertTrue(token["access_token"].startswith("fixture-access-proof-"))
            self.assertEqual("Bearer", token["token_type"])
            self.assertEqual(300, token["expires_in"])

            status, _, body = self.token(server, code)
            self.assertEqual(400, status)
            self.assertEqual("invalid_grant", json.loads(body)["error"])

    def test_pkce_wrong_verifier_is_rejected(self) -> None:
        with running_server(self.media_dir) as server:
            code = self.valid_code(server)
            status, _, body = self.token(server, code, verifier="wrong-verifier-with-enough-characters-0123456789")
            self.assertEqual(400, status)
            response = json.loads(body)
            self.assertEqual("invalid_grant", response["error"])
            self.assertIn("PKCE", response["error_description"])

    def test_authorize_validates_redirect_state_and_s256_before_negative_mode(self) -> None:
        with running_server(self.media_dir) as server:
            cases = (
                ({"redirect_uri": "other://callback"}, "redirect_uri"),
                ({"state": ""}, "state"),
                ({"code_challenge": "not-a-sha256-challenge"}, "code_challenge"),
                ({"code_challenge_method": "plain"}, "code_challenge_method"),
            )
            for overrides, expected_error in cases:
                with self.subTest(overrides=overrides):
                    status, _, body = self.authorize(
                        server,
                        fixture_mode="state_mismatch",
                        overrides=overrides,
                    )
                    self.assertEqual(400, status)
                    self.assertIn(expected_error, json.loads(body)["error_description"])

    def test_token_validates_form_contract_before_negative_mode(self) -> None:
        with running_server(self.media_dir) as server:
            code = self.valid_code(server)
            status, _, body = self.token(
                server,
                code,
                verifier="wrong-verifier-with-enough-characters-0123456789",
                fixture_mode="malformed_token_json",
            )
            self.assertEqual(400, status)
            self.assertEqual("invalid_grant", json.loads(body)["error"])

            code = self.valid_code(server)
            status, _, body = self.token(
                server,
                code,
                fixture_mode="malformed_token_json",
                overrides={"grant_type": "refresh_token"},
            )
            self.assertEqual(400, status)
            self.assertEqual("invalid_request", json.loads(body)["error"])

    def test_state_mismatch_negative_mode_changes_only_returned_state(self) -> None:
        with running_server(self.media_dir) as server:
            status, headers, _ = self.authorize(server, state="expected", fixture_mode="state_mismatch")
            self.assertEqual(302, status)
            redirect = self.authorization_params(headers)
            self.assertIn("code", redirect)
            self.assertEqual(["expected-mismatch"], redirect["state"])

    def test_missing_code_negative_mode_returns_state_without_code(self) -> None:
        with running_server(self.media_dir) as server:
            status, headers, _ = self.authorize(server, state="expected", fixture_mode="missing_code")
            self.assertEqual(302, status)
            redirect = self.authorization_params(headers)
            self.assertNotIn("code", redirect)
            self.assertEqual(["expected"], redirect["state"])

    def test_malformed_discovery_negative_mode_returns_invalid_json(self) -> None:
        with running_server(self.media_dir) as server:
            status, _, body = self.request(
                server,
                "GET",
                "/.well-known/openid-configuration?fixture_mode=malformed_discovery",
            )
            self.assertEqual(200, status)
            with self.assertRaises(json.JSONDecodeError):
                json.loads(body)

    def test_malformed_token_json_negative_mode_follows_valid_pkce(self) -> None:
        with running_server(self.media_dir) as server:
            code = self.valid_code(server)
            status, _, body = self.token(server, code, fixture_mode="malformed_token_json")
            self.assertEqual(200, status)
            with self.assertRaises(json.JSONDecodeError):
                json.loads(body)

    def test_http_error_negative_mode_is_selectable_per_route(self) -> None:
        with running_server(self.media_dir) as server:
            status, _, body = self.request(server, "GET", "/auth/config?fixture_mode=http_error")
            self.assertEqual(503, status)
            self.assertEqual("fixture_http_error", json.loads(body)["error"])

            status, _, _ = self.authorize(server, fixture_mode="http_error")
            self.assertEqual(503, status)

            code = self.valid_code(server)
            status, _, _ = self.token(server, code, fixture_mode="http_error")
            self.assertEqual(503, status)

            status, _, _ = self.request(server, "GET", "/media/sample.bin?fixture_mode=http_error")
            self.assertEqual(503, status)

    def test_global_negative_mode_supports_native_app_runs(self) -> None:
        with running_server(self.media_dir, negative_mode="malformed_discovery") as server:
            status, _, body = self.request(server, "GET", "/.well-known/openid-configuration")
            self.assertEqual(200, status)
            with self.assertRaises(json.JSONDecodeError):
                json.loads(body)

    def test_global_http_error_can_target_a_deep_auth_route(self) -> None:
        with running_server(
            self.media_dir,
            negative_mode="http_error",
            http_error_route="token",
        ) as server:
            status, _, _ = self.request(server, "GET", "/auth/config")
            self.assertEqual(200, status)
            status, _, _ = self.request(server, "GET", "/.well-known/openid-configuration")
            self.assertEqual(200, status)
            code = self.valid_code(server)
            status, _, body = self.token(server, code)
            self.assertEqual(503, status)
            self.assertEqual("fixture_http_error", json.loads(body)["error"])

    def test_full_media_get_and_head(self) -> None:
        with running_server(self.media_dir) as server:
            status, headers, body = self.request(server, "GET", "/media/sample.bin")
            self.assertEqual(200, status)
            self.assertEqual(MEDIA_BYTES, body)
            self.assertEqual(str(len(MEDIA_BYTES)), headers["Content-Length"])
            self.assertEqual("bytes", headers["Accept-Ranges"])

            status, headers, body = self.request(server, "HEAD", "/media/sample.bin")
            self.assertEqual(200, status)
            self.assertEqual(b"", body)
            self.assertEqual(str(len(MEDIA_BYTES)), headers["Content-Length"])
            self.assertEqual("bytes", headers["Accept-Ranges"])

    def test_media_byte_ranges_support_bounded_open_and_suffix_forms(self) -> None:
        with running_server(self.media_dir) as server:
            cases = (
                ("bytes=2-5", b"2345", "bytes 2-5/36"),
                ("bytes=30-", b"uvwxyz", "bytes 30-35/36"),
                ("bytes=-4", b"wxyz", "bytes 32-35/36"),
                ("bytes=34-99", b"yz", "bytes 34-35/36"),
            )
            for range_value, expected_body, expected_content_range in cases:
                with self.subTest(range_value=range_value):
                    status, headers, body = self.request(
                        server,
                        "GET",
                        "/media/sample.bin",
                        headers={"Range": range_value},
                    )
                    self.assertEqual(206, status)
                    self.assertEqual(expected_body, body)
                    self.assertEqual(expected_content_range, headers["Content-Range"])
                    self.assertEqual(str(len(expected_body)), headers["Content-Length"])

            status, headers, body = self.request(
                server,
                "HEAD",
                "/media/sample.bin",
                headers={"Range": "bytes=2-5"},
            )
            self.assertEqual(206, status)
            self.assertEqual(b"", body)
            self.assertEqual("bytes 2-5/36", headers["Content-Range"])
            self.assertEqual("4", headers["Content-Length"])

    def test_unsatisfiable_and_malformed_ranges_return_416(self) -> None:
        with running_server(self.media_dir) as server:
            for range_value in ("bytes=99-100", "bytes=8-2", "bytes=-0", "bytes=0-1,4-5", "items=0-1"):
                with self.subTest(range_value=range_value):
                    status, headers, body = self.request(
                        server,
                        "GET",
                        "/media/sample.bin",
                        headers={"Range": range_value},
                    )
                    self.assertEqual(416, status)
                    self.assertEqual("bytes */36", headers["Content-Range"])
                    self.assertEqual("bytes", headers["Accept-Ranges"])
                    self.assertEqual("range_not_satisfiable", json.loads(body)["error"])

    def test_media_traversal_is_rejected_and_only_regular_files_are_served(self) -> None:
        with running_server(self.media_dir) as server:
            for path in ("/media/../outside.bin", "/media/%2e%2e%2foutside.bin", "/media/..%5coutside.bin"):
                with self.subTest(path=path):
                    status, _, body = self.request(server, "GET", path)
                    self.assertEqual(403, status)
                    self.assertEqual("path_traversal_rejected", json.loads(body)["error"])

            status, _, _ = self.request(server, "GET", "/media/nested")
            self.assertEqual(404, status)
            status, _, body = self.request(server, "GET", "/media/nested/clip.bin")
            self.assertEqual(200, status)
            self.assertEqual(b"nested-media", body)

    def test_health_status_and_request_log_are_structured_and_redacted(self) -> None:
        with running_server(self.media_dir) as server:
            status, _, body = self.request(server, "GET", "/health")
            self.assertEqual(200, status)
            self.assertEqual({"ok": True}, json.loads(body))

            code = self.valid_code(server)
            status, _, _ = self.token(server, code)
            self.assertEqual(200, status)

            snapshot = server.status_snapshot()
            self.assertTrue(snapshot["ok"])
            requests = snapshot["requests"]
            self.assertGreaterEqual(len(requests), 3)
            token_request = next(entry for entry in requests if entry["path"] == "/token/")
            self.assertEqual(REDACTED, token_request["form"]["code"])
            self.assertEqual(REDACTED, token_request["form"]["code_verifier"])
            serialized = json.dumps(requests)
            self.assertNotIn(VERIFIER, serialized)
            self.assertNotIn(code, serialized)
            self.assertNotIn("fixture-state", serialized)

    def test_invalid_fixture_mode_is_rejected(self) -> None:
        with running_server(self.media_dir) as server:
            status, _, body = self.request(server, "GET", "/health?fixture_mode=unknown")
            self.assertEqual(400, status)
            self.assertEqual("invalid_request", json.loads(body)["error"])


if __name__ == "__main__":
    unittest.main()
