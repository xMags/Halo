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
/// Beyond the interactive sign-in, this host owns the whole token lifecycle:
/// the Keychain-persisted session (`OidcSessionStore`), the single-flight
/// rotating refresh, and best-effort revocation on sign-out. Sign-out happens
/// ONLY on a definitive `invalid_grant` from the refresh grant — transport
/// failures and 5xx surface as fetch errors and leave the session intact,
/// matching the local arm's refresh-401 rule.
///
/// `tokenProof` in the success event is the access token, surfaced for the
/// debug gate/fixture suites as round-trip evidence; production UI never
/// renders it.
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

    // Session state is confined to this serial queue: the persisted session,
    // the single-flight refresh latch, and the generation counter that lets a
    // sign-out or re-sign-in racing an in-flight refresh win over its late
    // write-back (the Swift analogue of LocalSessionManager's identity check).
    private let sessionQueue = DispatchQueue(label: "moe.ditto.halo.oidc-session")
    private let sessionStore = OidcSessionStore()
    private var session: OidcSession?
    private var sessionGeneration: UInt64 = 0
    private var sessionLoaded = false
    private var pendingTokenCompletions: [(String?, String?) -> Void] = []

    /// Below this remaining lifetime the access token cannot be trusted to
    /// survive a request round-trip; mirrors the local arm's margin.
    private static let expiryMarginSeconds: Double = 60

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
        let revocation: String?
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
                    signIn: SignInContext(serverUrl: serverUrl, issuer: issuer, clientId: clientId),
                    endpoints: endpoints,
                    scopes: scopes,
                    state: state,
                    verifier: verifier,
                    challenge: challenge
                )
            }
        }
    }

    /// What the exchange needs beyond the wire parameters to build a
    /// persistable session.
    private struct SignInContext {
        let serverUrl: String
        let issuer: String
        let clientId: String
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
            completion(
                OidcEndpoints(
                    authorization: authorization,
                    token: token,
                    revocation: json["revocation_endpoint"] as? String
                ),
                nil
            )
        }.resume()
    }

    private func startBrowser(
        signIn: SignInContext,
        endpoints: OidcEndpoints,
        scopes: String,
        state: String,
        verifier: String,
        challenge: String
    ) {
        let clientId = signIn.clientId
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
                signIn: signIn,
                endpoints: endpoints
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
        signIn: SignInContext,
        endpoints: OidcEndpoints
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

        exchangeToken(code: code, verifier: verifier, signIn: signIn, endpoints: endpoints)
    }

    private func exchangeToken(code: String, verifier: String, signIn: SignInContext, endpoints: OidcEndpoints) {
        let form: [(String, String)] = [
            ("grant_type", "authorization_code"),
            ("client_id", signIn.clientId),
            ("redirect_uri", Self.redirectUri),
            ("code", code),
            ("code_verifier", verifier),
        ]
        postTokenForm(form, tokenEndpoint: endpoints.token) { [weak self] outcome in
            guard let self = self else { return }
            switch outcome {
            case let .failure(reason):
                self.fail(reason)
            case .invalidGrant:
                self.fail("Token exchange failed: invalid_grant")
            case let .success(issued):
                self.sessionQueue.async {
                    let session = OidcSession(
                        serverUrl: signIn.serverUrl,
                        issuer: signIn.issuer,
                        clientId: signIn.clientId,
                        tokenEndpoint: endpoints.token,
                        revocationEndpoint: endpoints.revocation,
                        accessToken: issued.accessToken,
                        accessTokenExpiresAt: Date().timeIntervalSince1970 + issued.expiresIn,
                        refreshToken: issued.refreshToken
                    )
                    // Persisting must be loud: a silent failure would look like
                    // a successful sign-in that vanishes on the next launch.
                    guard self.sessionStore.save(session) else {
                        self.fail("Could not persist the session to the Keychain")
                        return
                    }
                    self.session = session
                    self.sessionGeneration += 1
                    self.succeed(serverUrl: signIn.serverUrl, tokenProof: issued.accessToken)
                }
            }
        }
    }

    // MARK: - Token wire (shared by exchange and refresh)

    private struct IssuedTokens {
        let accessToken: String
        let expiresIn: Double
        let refreshToken: String
    }

    private enum TokenOutcome {
        case success(IssuedTokens)
        /// The server's definitive rejection — the only outcome allowed to end
        /// a session.
        case invalidGrant
        case failure(String)
    }

    /// Hand-built form POST to the token endpoint, used verbatim (trailing
    /// slash intact) — no library that could normalise `/token/` into a Django
    /// APPEND_SLASH 301 sits in the path. Shared by the authorization-code
    /// exchange and the refresh grant so both keep the exact wire format.
    private func postTokenForm(
        _ form: [(String, String)],
        tokenEndpoint: String,
        completion: @escaping (TokenOutcome) -> Void
    ) {
        guard var comps = URLComponents(string: tokenEndpoint) else {
            completion(.failure("Invalid token endpoint"))
            return
        }
        if let item = fixtureModeQueryItem(for: .token) { comps.queryItems = [item] }
        guard let url = comps.url else {
            completion(.failure("Could not build token URL"))
            return
        }

        let body = form
            .map { "\(Self.formEncode($0.0))=\(Self.formEncode($0.1))" }
            .joined(separator: "&")

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.httpBody = body.data(using: .utf8)

        URLSession.shared.dataTask(with: request) { data, response, error in
            if let error = error {
                completion(.failure("Token request failed: \(error.localizedDescription)"))
                return
            }
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            guard
                let data = data,
                let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
            else {
                completion(.failure("Token response was not valid JSON (HTTP \(status))"))
                return
            }
            // A structured OAuth error (invalid_grant, fixture_http_error, …) is
            // more useful than the bare status — and invalid_grant is the one
            // definitive rejection, distinguished from every other failure.
            if let oauthError = json["error"] as? String {
                if oauthError == "invalid_grant" {
                    completion(.invalidGrant)
                } else {
                    completion(.failure("Token exchange failed: \(oauthError)"))
                }
                return
            }
            guard (200 ..< 300).contains(status) else {
                completion(.failure("Token endpoint returned HTTP \(status)"))
                return
            }
            guard let accessToken = json["access_token"] as? String, !accessToken.isEmpty else {
                completion(.failure("Token response had no access_token"))
                return
            }
            // The session thesis requires a rotating refresh token; a
            // deployment that omits one would silently break refresh later, so
            // fail fast here instead.
            guard let refreshToken = json["refresh_token"] as? String, !refreshToken.isEmpty else {
                completion(.failure("Token response had no refresh_token"))
                return
            }
            let expiresIn = (json["expires_in"] as? NSNumber)?.doubleValue ?? 300
            completion(.success(IssuedTokens(
                accessToken: accessToken,
                expiresIn: expiresIn,
                refreshToken: refreshToken
            )))
        }.resume()
    }

    // MARK: - Outcome

    private func succeed(serverUrl: String, tokenProof: String) {
        DispatchQueue.main.async {
            self.authSession = nil
            self.eventSink?.onOidcSucceeded(serverUrl: serverUrl, tokenProof: tokenProof)
        }
    }

    private func fail(_ reason: String) {
        DispatchQueue.main.async {
            self.authSession = nil
            self.eventSink?.onOidcFailed(reason: reason)
        }
    }

    // MARK: - Session lifecycle (HaloIosAuthHost)

    func restoreOidcSession() -> String? {
        sessionQueue.sync {
            loadSessionIfNeeded()
            return session?.serverUrl
        }
    }

    func fetchOidcAccessToken(forceRefresh: Bool, completion: @escaping (String?, String?) -> Void) {
        sessionQueue.async {
            self.loadSessionIfNeeded()
            guard let session = self.session else {
                completion(nil, nil)
                return
            }
            let remaining = session.accessTokenExpiresAt - Date().timeIntervalSince1970
            if !forceRefresh && remaining > Self.expiryMarginSeconds {
                completion(session.accessToken, nil)
                return
            }
            // Single-flight: everyone arriving while a refresh is in flight
            // shares its outcome — including a failure, so no waiter can
            // silently receive a stale token in place of the error.
            self.pendingTokenCompletions.append(completion)
            if self.pendingTokenCompletions.count == 1 {
                self.refreshNow(session: session, generation: self.sessionGeneration)
            }
        }
    }

    func signOutOidc(completion: @escaping () -> Void) {
        sessionQueue.async {
            self.loadSessionIfNeeded()
            let signedOut = self.session
            self.sessionStore.clear()
            self.session = nil
            self.sessionGeneration += 1
            // Completion fires as soon as storage is clear — the automation
            // reset hatch runs this with no server up, so the revoke below is
            // fire-and-forget by design.
            completion()
            guard
                let session = signedOut,
                let revocationEndpoint = session.revocationEndpoint
            else { return }
            self.revoke(refreshToken: session.refreshToken, clientId: session.clientId, endpoint: revocationEndpoint)
        }
    }

    // MARK: - Refresh internals (sessionQueue-confined entry points)

    private func loadSessionIfNeeded() {
        guard !sessionLoaded else { return }
        sessionLoaded = true
        session = sessionStore.load()
    }

    private func refreshNow(session: OidcSession, generation: UInt64) {
        let form: [(String, String)] = [
            ("grant_type", "refresh_token"),
            ("client_id", session.clientId),
            ("refresh_token", session.refreshToken),
        ]
        postTokenForm(form, tokenEndpoint: session.tokenEndpoint) { [weak self] outcome in
            guard let self = self else { return }
            self.sessionQueue.async {
                // A sign-out or new sign-in bumped the generation while this
                // flight was out: its session is gone or replaced, so the
                // write-back is dropped and waiters answer from current state.
                guard generation == self.sessionGeneration else {
                    self.flushTokenCompletions(token: self.session?.accessToken, error: nil)
                    return
                }
                switch outcome {
                case let .success(issued):
                    var updated = session
                    updated.accessToken = issued.accessToken
                    updated.accessTokenExpiresAt = Date().timeIntervalSince1970 + issued.expiresIn
                    // Rotation: the IdP invalidates the used refresh token and
                    // issues the successor; persisting it is what keeps the
                    // session alive across launches.
                    updated.refreshToken = issued.refreshToken
                    guard self.sessionStore.save(updated) else {
                        self.flushTokenCompletions(token: nil, error: "Could not persist the refreshed session")
                        return
                    }
                    self.session = updated
                    self.flushTokenCompletions(token: issued.accessToken, error: nil)
                case .invalidGrant:
                    // The one definitive rejection: the session is dead at the
                    // IdP. Clear it, answer nil (not an error — callers treat
                    // this as signed-out), and tell Kotlin so app state flips
                    // even when this fetch happened deep inside an API call.
                    self.sessionStore.clear()
                    self.session = nil
                    self.sessionGeneration += 1
                    self.flushTokenCompletions(token: nil, error: nil)
                    DispatchQueue.main.async {
                        self.eventSink?.onOidcSessionInvalidated()
                    }
                case let .failure(reason):
                    // Transport/5xx: the session stays; the caller surfaces a
                    // network error instead of a sign-out.
                    self.flushTokenCompletions(token: nil, error: reason)
                }
            }
        }
    }

    private func flushTokenCompletions(token: String?, error: String?) {
        let completions = pendingTokenCompletions
        pendingTokenCompletions = []
        for completion in completions {
            completion(token, error)
        }
    }

    /// Best-effort: sign-out must succeed locally whether or not the IdP is
    /// reachable, so the result is only logged by the server, never awaited.
    private func revoke(refreshToken: String, clientId: String, endpoint: String) {
        guard let url = URL(string: endpoint) else { return }
        let body = [("token", refreshToken), ("client_id", clientId)]
            .map { "\(Self.formEncode($0.0))=\(Self.formEncode($0.1))" }
            .joined(separator: "&")
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.httpBody = body.data(using: .utf8)
        URLSession.shared.dataTask(with: request).resume()
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
