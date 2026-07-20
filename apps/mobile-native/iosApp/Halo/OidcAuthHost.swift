import AuthenticationServices
import ComposeApp
import CryptoKit
import Foundation
import Security
import UIKit

/// Real native OIDC host.
///
/// Owns the whole flow in Swift on purpose — the `expo-auth-session` landmines
/// the Expo client works around in `apps/mobile/src/oidc.ts` (trailing-slash
/// mangling of `/token/`, the Android browser-dismiss race) are Foundation
/// behaviours, so the strongest proof that the native path dodges them is to
/// build it directly on `URLSession` + `ASWebAuthenticationSession`. Kotlin
/// only renders the outcome.
///
/// Deliberately NOT included yet: token persistence, refresh, logout, or
/// secure storage — those land with the production auth subsystem.
/// `OidcSucceeded.tokenProof` is the fixture's non-authoritative access
/// token, surfaced once as round-trip evidence.
final class OidcAuthHost: NSObject, HaloIosAuthHost, ASWebAuthenticationPresentationContextProviding {
    let hostId = UUID().uuidString
    private(set) var oidcRequestCount: Int64 = 0

    /// Assigned by `AppDelegate` after the key window exists; the browser sheet
    /// anchors to it.
    weak var anchorWindow: UIWindow?

    private var eventSink: HaloIosAuthEventSink?
    // Strongly retained for the lifetime of the flow — a released session
    // dismisses its own sheet immediately (the classic ASWebAuthenticationSession
    // "nothing happens" bug).
    private var authSession: ASWebAuthenticationSession?

    private static let redirectUri = "halo://oauth/callback"
    private static let callbackScheme = "halo"

    // Test-only negative-mode injection. Absent in production/normal runs, so
    // requests go out clean. The fixture reads `fixture_mode` from the query of
    // whichever route it targets.
    private let fixtureMode: String? = ProcessInfo.processInfo.environment["HALO_OIDC_FIXTURE_MODE"]
    private let fixtureHttpRoute: String = ProcessInfo.processInfo.environment["HALO_OIDC_FIXTURE_HTTP_ROUTE"] ?? "token"

    private enum FixtureRoute { case discovery, authorize, token }

    private struct OidcEndpoints {
        let authorization: String
        let token: String
    }

    // MARK: - HaloIosAuthHost

    func setAuthEventSink(sink: HaloIosAuthEventSink?) {
        eventSink = sink
    }

    /// Reads `{serverUrl}/auth/config` off the main thread and returns the raw
    /// JSON body; the Kotlin adapter parses it. Async so a real GET never blocks
    /// the Compose thread.
    func fetchAuthConfig(serverUrl: String, completion: @escaping (String?, String?) -> Void) {
        let base = serverUrl.hasSuffix("/") ? String(serverUrl.dropLast()) : serverUrl
        guard let url = URL(string: base + "/auth/config") else {
            completion(nil, "Invalid server URL")
            return
        }
        URLSession.shared.dataTask(with: url) { data, response, error in
            if let error = error {
                completion(nil, "Config request failed: \(error.localizedDescription)")
                return
            }
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            guard (200 ..< 300).contains(status) else {
                completion(nil, "Auth config returned HTTP \(status)")
                return
            }
            guard let data = data, let body = String(data: data, encoding: .utf8) else {
                completion(nil, "Auth config response was empty")
                return
            }
            completion(body, nil)
        }.resume()
    }

    func requestOidc(serverUrl: String, issuer: String, clientId: String, scopes: String) {
        oidcRequestCount += 1

        // PKCE: 32 random bytes → 43-char base64url verifier; S256 challenge.
        let verifier = randomURLSafe(byteCount: 32)
        let challenge = codeChallenge(for: verifier)
        let state = randomURLSafe(byteCount: 32)

        fetchDiscovery(issuer: issuer) { [weak self] endpoints, reason in
            guard let self = self else { return }
            guard let endpoints = endpoints else {
                self.fail(reason ?? "Discovery failed")
                return
            }
            // ASWebAuthenticationSession must be created and started on main.
            DispatchQueue.main.async {
                self.startBrowser(
                    endpoints: endpoints,
                    clientId: clientId,
                    scopes: scopes,
                    state: state,
                    verifier: verifier,
                    challenge: challenge
                )
            }
        }
    }

    // MARK: - Flow steps

    // Reports `(endpoints, nil)` on success or `(nil, reason)` on failure —
    // `Result` needs an `Error` failure type, and a plain reason string is
    // enough here (matches `fetchAuthConfig`'s shape).
    private func fetchDiscovery(issuer: String, completion: @escaping (OidcEndpoints?, String?) -> Void) {
        let base = issuer.hasSuffix("/") ? String(issuer.dropLast()) : issuer
        guard var comps = URLComponents(string: base + "/.well-known/openid-configuration") else {
            completion(nil, "Invalid issuer URL")
            return
        }
        if let item = fixtureModeQueryItem(for: .discovery) { comps.queryItems = [item] }
        guard let url = comps.url else {
            completion(nil, "Could not build discovery URL")
            return
        }
        URLSession.shared.dataTask(with: url) { data, response, error in
            if let error = error {
                completion(nil, "Discovery request failed: \(error.localizedDescription)")
                return
            }
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            guard (200 ..< 300).contains(status) else {
                completion(nil, "Discovery returned HTTP \(status)")
                return
            }
            guard
                let data = data,
                let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
            else {
                completion(nil, "Discovery response was not valid JSON")
                return
            }
            guard
                let authorization = json["authorization_endpoint"] as? String,
                let token = json["token_endpoint"] as? String
            else {
                completion(nil, "Discovery document is missing endpoints")
                return
            }
            completion(OidcEndpoints(authorization: authorization, token: token), nil)
        }.resume()
    }

    private func startBrowser(
        endpoints: OidcEndpoints,
        clientId: String,
        scopes: String,
        state: String,
        verifier: String,
        challenge: String
    ) {
        // URLComponents preserves the discovered path verbatim (including any
        // trailing slash) and only appends the query — the endpoints are used
        // exactly as the IdP advertised them.
        guard var comps = URLComponents(string: endpoints.authorization) else {
            fail("Invalid authorization endpoint")
            return
        }
        var items = [
            URLQueryItem(name: "response_type", value: "code"),
            URLQueryItem(name: "client_id", value: clientId),
            URLQueryItem(name: "redirect_uri", value: Self.redirectUri),
            URLQueryItem(name: "scope", value: scopes),
            URLQueryItem(name: "state", value: state),
            URLQueryItem(name: "code_challenge", value: challenge),
            URLQueryItem(name: "code_challenge_method", value: "S256"),
        ]
        if let item = fixtureModeQueryItem(for: .authorize) { items.append(item) }
        comps.queryItems = items
        guard let authorizeURL = comps.url else {
            fail("Could not build authorization URL")
            return
        }

        let session = ASWebAuthenticationSession(
            url: authorizeURL,
            callbackURLScheme: Self.callbackScheme
        ) { [weak self] callbackURL, error in
            guard let self = self else { return }
            self.handleCallback(
                callbackURL: callbackURL,
                error: error,
                expectedState: state,
                verifier: verifier,
                tokenEndpoint: endpoints.token,
                clientId: clientId
            )
        }
        session.presentationContextProvider = self
        // Ephemeral by default: no shared cookies, so the fixture's immediate
        // redirect is deterministic and there is no SSO carry-over. Ephemeral
        // also suppresses the system consent entirely — the cancel test sets
        // HALO_OIDC_EPHEMERAL=0 so the consent appears and can be dismissed.
        session.prefersEphemeralWebBrowserSession =
            ProcessInfo.processInfo.environment["HALO_OIDC_EPHEMERAL"] != "0"
        authSession = session
        // A false return means the sheet never presented; surface it instead of
        // leaving the flow hung with no completion.
        if !session.start() {
            fail("Could not present the sign-in browser")
        }
    }

    private func handleCallback(
        callbackURL: URL?,
        error: Error?,
        expectedState: String,
        verifier: String,
        tokenEndpoint: String,
        clientId: String
    ) {
        if let error = error {
            if let asError = error as? ASWebAuthenticationSessionError, asError.code == .canceledLogin {
                fail("Sign-in was cancelled")
            } else {
                fail("Browser sign-in failed: \(error.localizedDescription)")
            }
            return
        }
        guard let callbackURL = callbackURL else {
            fail("No callback URL was returned")
            return
        }

        let items = URLComponents(url: callbackURL, resolvingAgainstBaseURL: false)?.queryItems ?? []
        if let oauthError = items.first(where: { $0.name == "error" })?.value {
            fail("Authorization error: \(oauthError)")
            return
        }
        // Validate state before touching the code — the fallback path in
        // apps/mobile/src/oidc.ts makes the same point.
        let returnedState = items.first(where: { $0.name == "state" })?.value
        guard returnedState == expectedState else {
            fail("Authorization state did not match")
            return
        }
        guard let code = items.first(where: { $0.name == "code" })?.value, !code.isEmpty else {
            fail("Authorization response had no code")
            return
        }

        exchangeToken(code: code, verifier: verifier, tokenEndpoint: tokenEndpoint, clientId: clientId)
    }

    private func exchangeToken(code: String, verifier: String, tokenEndpoint: String, clientId: String) {
        // Token endpoint is used verbatim (trailing slash intact); this hand-built
        // form POST is the whole point — no library that could normalise `/token/`
        // into a Django APPEND_SLASH 301 sits in the path.
        guard var comps = URLComponents(string: tokenEndpoint) else {
            fail("Invalid token endpoint")
            return
        }
        if let item = fixtureModeQueryItem(for: .token) { comps.queryItems = [item] }
        guard let url = comps.url else {
            fail("Could not build token URL")
            return
        }

        let form: [(String, String)] = [
            ("grant_type", "authorization_code"),
            ("client_id", clientId),
            ("redirect_uri", Self.redirectUri),
            ("code", code),
            ("code_verifier", verifier),
        ]
        let body = form
            .map { "\(Self.formEncode($0.0))=\(Self.formEncode($0.1))" }
            .joined(separator: "&")

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.httpBody = body.data(using: .utf8)

        URLSession.shared.dataTask(with: request) { [weak self] data, response, error in
            guard let self = self else { return }
            if let error = error {
                self.fail("Token request failed: \(error.localizedDescription)")
                return
            }
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            guard
                let data = data,
                let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
            else {
                self.fail("Token response was not valid JSON (HTTP \(status))")
                return
            }
            // A structured OAuth error (invalid_grant, fixture_http_error, …) is
            // more useful than the bare status.
            if let oauthError = json["error"] as? String {
                self.fail("Token exchange failed: \(oauthError)")
                return
            }
            guard (200 ..< 300).contains(status) else {
                self.fail("Token endpoint returned HTTP \(status)")
                return
            }
            guard let accessToken = json["access_token"] as? String, !accessToken.isEmpty else {
                self.fail("Token response had no access_token")
                return
            }
            self.succeed(accessToken)
        }.resume()
    }

    // MARK: - Outcome

    private func succeed(_ tokenProof: String) {
        DispatchQueue.main.async {
            self.authSession = nil
            self.eventSink?.onOidcSucceeded(tokenProof: tokenProof)
        }
    }

    private func fail(_ reason: String) {
        DispatchQueue.main.async {
            self.authSession = nil
            self.eventSink?.onOidcFailed(reason: reason)
        }
    }

    // MARK: - ASWebAuthenticationPresentationContextProviding

    func presentationAnchor(for _: ASWebAuthenticationSession) -> ASPresentationAnchor {
        if let window = anchorWindow { return window }
        let keyWindow = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
            .first { $0.isKeyWindow }
        return keyWindow ?? ASPresentationAnchor()
    }

    // MARK: - Helpers

    private func fixtureModeQueryItem(for route: FixtureRoute) -> URLQueryItem? {
        guard let mode = fixtureMode, !mode.isEmpty else { return nil }
        // http_error would 503 the first request it touches, so it is targeted at
        // one route; every other mode is harmlessly ignored by routes it does not
        // apply to, so it rides on all of them.
        if mode == "http_error" {
            let target: FixtureRoute = fixtureHttpRoute == "discovery" ? .discovery : .token
            return route == target ? URLQueryItem(name: "fixture_mode", value: "http_error") : nil
        }
        return URLQueryItem(name: "fixture_mode", value: mode)
    }

    private func randomURLSafe(byteCount: Int) -> String {
        var bytes = [UInt8](repeating: 0, count: byteCount)
        if SecRandomCopyBytes(kSecRandomDefault, byteCount, &bytes) != errSecSuccess {
            for index in 0 ..< byteCount { bytes[index] = UInt8.random(in: 0 ... 255) }
        }
        return Data(bytes).base64URLEncodedString()
    }

    private func codeChallenge(for verifier: String) -> String {
        let digest = SHA256.hash(data: Data(verifier.utf8))
        return Data(digest).base64URLEncodedString()
    }

    private static func formEncode(_ value: String) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
    }
}

private extension Data {
    func base64URLEncodedString() -> String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
