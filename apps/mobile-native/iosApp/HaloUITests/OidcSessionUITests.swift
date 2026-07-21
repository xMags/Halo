import XCTest

/// OIDC token lifecycle: Keychain persistence across relaunch, the rotating
/// refresh, the invalid_grant sign-out, the transport-failure survival rule,
/// and explicit sign-out.
///
/// Prerequisites (same as `OidcUITests`):
/// - the fixture server on `127.0.0.1:18787` in its default OIDC mode.
///
/// Lifecycle fixture modes ride on `HALO_OIDC_FIXTURE_MODE`: the three modes
/// used here all make the sign-in exchange issue a token already inside the
/// host's 60s refresh margin, so the first "Fetch access token" tap after
/// sign-in deterministically hits the refresh path.
final class OidcSessionUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    // MARK: - Tests

    func testSessionPersistsAcrossRelaunchWithoutABrowser() {
        launch(resetSession: true)
        signInToGate()

        // The relaunch must restore from the Keychain straight to the gate —
        // no ASWebAuthenticationSession, no network.
        app.terminate()
        launch(resetSession: false)
        assertText(beginningWith: "Session: oidc · http://127.0.0.1:18787", timeout: 20)
        XCTAssertFalse(element(labeled: "Continue").exists, "Login form rendered despite a persisted session")

        // The restored session serves tokens: the fetch reads the persisted
        // access token back through the provider chain.
        tapButton("Fetch access token")
        assertText(containing: "Token: fixture-access-proof", timeout: 10)
    }

    func testExpiredTokenRefreshesWithRotation() {
        launch(resetSession: true, mode: "short_access_ttl")
        signInToGate()

        // The sign-in token was born inside the refresh margin, so this fetch
        // must run the refresh grant; the fixture's rotated-token marker is the
        // proof the refresh (not the cached token) answered.
        tapButton("Fetch access token")
        assertText(containing: "Token: fixture-access-rotated", timeout: 15)
    }

    func testRefreshInvalidGrantSignsOut() {
        launch(resetSession: true, mode: "refresh_invalid_grant")
        signInToGate()

        // The forced refresh is definitively rejected: the host clears the
        // session and the shell kicks back to the login form.
        tapButton("Fetch access token")
        XCTAssertTrue(element(labeled: "Continue").waitForExistence(timeout: 15), "Sign-out kick never reached the login form")

        // Nothing restorable may survive a definitive rejection.
        app.terminate()
        launch(resetSession: false)
        XCTAssertTrue(element(labeled: "Continue").waitForExistence(timeout: 15), "A definitively rejected session was restored")
    }

    func testRefreshTransportFailureKeepsTheSession() {
        launch(resetSession: true, mode: "refresh_http_error")
        signInToGate()

        // A 503 on refresh surfaces as an error and must NOT sign out.
        tapButton("Fetch access token")
        assertText(containing: "Token error:", timeout: 15)
        assertText(beginningWith: "Session: oidc", timeout: 5)

        // The session also survives a relaunch after the failed refresh.
        app.terminate()
        launch(resetSession: false)
        assertText(beginningWith: "Session: oidc", timeout: 20)
    }

    func testSignOutClearsThePersistedSessionAndEndsTheIdpSession() {
        launch(resetSession: true)
        signInToGate()

        tapButton("Sign out")
        XCTAssertTrue(element(labeled: "Continue").waitForExistence(timeout: 15), "Sign out did not return to the login form")

        // The user-facing sign-out must also run RP-initiated logout — the
        // fixture's request log is the evidence the browser actually hit
        // end-session with a valid hint (302 = hint accepted + redirect back).
        XCTAssertTrue(
            fixtureSawEndSessionRedirect(timeout: 15),
            "No accepted /end-session/ request reached the fixture"
        )

        app.terminate()
        launch(resetSession: false)
        XCTAssertTrue(element(labeled: "Continue").waitForExistence(timeout: 15), "A signed-out session was restored")
    }

    // MARK: - Flow helpers

    private func launch(resetSession: Bool, mode: String? = nil) {
        app = XCUIApplication()
        app.launchEnvironment["HALO_AUTH_HOST"] = "oidc"
        app.launchEnvironment["HALO_SERVER_URL"] = "http://127.0.0.1:18787"
        if resetSession { app.launchEnvironment["HALO_RESET_SESSION"] = "1" }
        if let mode = mode { app.launchEnvironment["HALO_OIDC_FIXTURE_MODE"] = mode }
        app.launch()
    }

    /// Full ephemeral browser sign-in ending on the debug gate (the shell
    /// auto-navigates once the session is established).
    private func signInToGate() {
        tapButton("Continue")
        // The ephemeral session shows no consent and the fixture auto-redirects;
        // answer a consent only if this environment surfaces one.
        _ = resolveConsent(button: "Continue", timeout: 2)
        assertText(beginningWith: "Session: oidc · http://127.0.0.1:18787", timeout: 45)
    }

    @discardableResult
    private func resolveConsent(button label: String, timeout: TimeInterval = 8) -> Bool {
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let springboardButton = springboard.buttons[label]
        if springboardButton.waitForExistence(timeout: timeout) {
            springboardButton.tap()
            return true
        }
        let alertButton = app.alerts.buttons[label]
        if alertButton.waitForExistence(timeout: 2) {
            alertButton.tap()
            return true
        }
        return false
    }

    // MARK: - Assertions

    /// Polls the fixture's `/status` request log for a 302-answered
    /// `/end-session/` entry — the sheet itself dismisses too fast to assert on.
    private func fixtureSawEndSessionRedirect(timeout: TimeInterval) -> Bool {
        let statusURL = URL(string: "http://127.0.0.1:18787/status")!
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            let expectation = XCTestExpectation(description: "fixture status")
            var matched = false
            URLSession.shared.dataTask(with: statusURL) { data, _, _ in
                defer { expectation.fulfill() }
                guard
                    let data = data,
                    let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
                    let requests = json["requests"] as? [[String: Any]]
                else { return }
                matched = requests.contains { entry in
                    entry["path"] as? String == "/end-session/" && entry["status"] as? Int == 302
                }
            }.resume()
            _ = XCTWaiter.wait(for: [expectation], timeout: 5)
            if matched { return true }
            Thread.sleep(forTimeInterval: 0.5)
        }
        return false
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

    private func element(labeled label: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", label)).firstMatch
    }

    private func tapButton(_ label: String) {
        let element = element(labeled: label)
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
