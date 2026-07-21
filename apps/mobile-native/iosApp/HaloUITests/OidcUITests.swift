import XCTest

/// Native OIDC gate for `OidcAuthHost`.
///
/// Prerequisites:
/// - the fixture server running on `127.0.0.1:18787` (default OIDC mode);
/// - launched with `HALO_AUTH_HOST=oidc` so the real host (not the fake) is
///   wired, and `HALO_SERVER_URL` pointed at the fixture.
///
/// Each negative case is selected with `HALO_OIDC_FIXTURE_MODE` (+ route for
/// `http_error`); the host appends `fixture_mode` to the matching request. The
/// system "Sign In" consent is answered through springboard.
final class OidcUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    // MARK: - Success

    func testSuccessfulSignInEstablishesASessionAndSurfacesTokenProof() {
        launch()
        startSignIn()

        // A successful sign-in now establishes a session, so the shell
        // auto-navigates to the gate; the session row mirrors Kotlin's state.
        assertText(beginningWith: "Session: oidc · http://127.0.0.1:18787", timeout: 45)

        // The proof is read back through the persisted session (Keychain →
        // token fetch), so its presence covers discovery → authorize →
        // /token/ → persist → provider, strictly more than the old transient
        // login-screen text did.
        tapButton("Fetch access token")
        assertText(containing: "Token: fixture-access-proof", timeout: 10)
        assertText(beginningWith: "OIDC requests: 1", timeout: 10)
    }

    // MARK: - Negatives that fail after the browser round-trip

    func testStateMismatchIsRejected() {
        launch(mode: "state_mismatch")
        startSignIn()
        assertFailure(containing: "state did not match")
    }

    func testMissingAuthorizationCodeIsRejected() {
        launch(mode: "missing_code")
        startSignIn()
        assertFailure(containing: "no code")
    }

    func testMalformedTokenResponseIsRejected() {
        launch(mode: "malformed_token_json")
        startSignIn()
        assertFailure(containing: "Token response")
    }

    func testHttpErrorOnTokenIsRejected() {
        launch(mode: "http_error", httpRoute: "token")
        startSignIn()
        assertFailure(containing: "fixture_http_error")
    }

    // MARK: - Negatives that fail before the browser (no consent)

    func testMalformedDiscoveryIsRejected() {
        launch(mode: "malformed_discovery")
        tapButton("Continue")
        assertFailure(containing: "Discovery")
    }

    func testHttpErrorOnDiscoveryIsRejected() {
        launch(mode: "http_error", httpRoute: "discovery")
        tapButton("Continue")
        assertFailure(containing: "HTTP 503")
    }

    // MARK: - User cancel

    func testUserCancelIsReported() {
        // Non-ephemeral so the system "Sign In" consent actually appears;
        // dismissing it is the deterministic cancel point (an ephemeral session
        // shows no consent and the fixture would auto-redirect before any tap).
        launch(ephemeral: false)
        tapButton("Continue")
        XCTAssertTrue(resolveConsent(button: "Cancel", timeout: 12), "Consent dialog to cancel never appeared")
        assertFailure(containing: "cancelled")
    }

    // MARK: - Launch / flow helpers

    private func launch(mode: String? = nil, httpRoute: String? = nil, ephemeral: Bool = true) {
        app = XCUIApplication()
        app.launchEnvironment["HALO_AUTH_HOST"] = "oidc"
        app.launchEnvironment["HALO_SERVER_URL"] = "http://127.0.0.1:18787"
        // Every test here starts from the login form; a session persisted by an
        // earlier test (or a manual run) would auto-restore past it.
        app.launchEnvironment["HALO_RESET_SESSION"] = "1"
        if let mode = mode { app.launchEnvironment["HALO_OIDC_FIXTURE_MODE"] = mode }
        if let httpRoute = httpRoute { app.launchEnvironment["HALO_OIDC_FIXTURE_HTTP_ROUTE"] = httpRoute }
        if !ephemeral { app.launchEnvironment["HALO_OIDC_EPHEMERAL"] = "0" }
        app.launch()
    }

    private func startSignIn() {
        tapButton("Continue")
        // The ephemeral session shows no consent and the fixture auto-redirects,
        // so the flow completes on its own. Answer a consent only if one appears
        // (other iOS versions/environments); keep the wait short.
        _ = resolveConsent(button: "Continue", timeout: 2)
    }

    @discardableResult
    private func resolveConsent(button label: String, timeout: TimeInterval = 8) -> Bool {
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let springboardButton = springboard.buttons[label]
        if springboardButton.waitForExistence(timeout: timeout) {
            springboardButton.tap()
            return true
        }
        // Some iOS versions surface the consent as an app-level alert instead.
        let alertButton = app.alerts.buttons[label]
        if alertButton.waitForExistence(timeout: 2) {
            alertButton.tap()
            return true
        }
        return false
    }

    // MARK: - Assertions

    private func assertFailure(containing substring: String, timeout: TimeInterval = 45) {
        assertText(beginningWith: "OIDC failed:", timeout: timeout)
        assertText(containing: substring, timeout: 5)
    }

    private func assertText(beginningWith prefix: String, timeout: TimeInterval = 20) {
        let predicate = NSPredicate(format: "label BEGINSWITH %@", prefix)
        let element = app.descendants(matching: .any).matching(predicate).firstMatch
        XCTAssertTrue(element.waitForExistence(timeout: timeout), "Missing text beginning with: \(prefix)")
    }

    private func assertText(containing substring: String, timeout: TimeInterval = 20) {
        let predicate = NSPredicate(format: "label CONTAINS %@", substring)
        let element = app.descendants(matching: .any).matching(predicate).firstMatch
        XCTAssertTrue(element.waitForExistence(timeout: timeout), "Missing text containing: \(substring)")
    }

    private func tapButton(_ label: String) {
        let predicate = NSPredicate(format: "label == %@", label)
        let element = app.descendants(matching: .any).matching(predicate).firstMatch
        XCTAssertTrue(element.waitForExistence(timeout: 15), "Missing action: \(label)")

        let hittable = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "hittable == true"),
            object: element
        )
        XCTAssertEqual(
            XCTWaiter.wait(for: [hittable], timeout: 15),
            .completed,
            "Action is not hittable: \(label)"
        )
        element.tap()
    }
}
