import XCTest

/// Local-mode sign-in against the fixture server, including the Keychain
/// persistence proof: a session established in one process must survive
/// termination and restore straight to the gate screen in the next.
///
/// Prerequisites:
/// - the fixture server running in local mode on its own port so the OIDC
///   suites' default-mode server on 18787 can stay up alongside:
///   `python3 -u fixtures/fixture_server.py --port 18788 --auth-mode local`
///
/// The app launches with `HALO_AUTH_HOST=oidc` because that host performs real
/// `/auth/config` discovery over HTTP (the fake host cans it); against a
/// local-mode server the flow never reaches a browser, so no consent handling
/// is needed anywhere in this suite.
final class LocalAuthUITests: XCTestCase {
    private static let serverUrl = "http://127.0.0.1:18788"
    private static let username = "fixture-user"
    private static let password = "fixture-pass"

    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    // MARK: - Tests

    func testSignInPersistsSessionAcrossRelaunch() {
        launch(resetSession: true)
        signIn()
        assertGateScreen()

        // The relaunch (no reset) must restore the Keychain session and skip
        // the login form entirely — this is the cross-process persistence proof.
        app.terminate()
        launch(resetSession: false)
        assertGateScreen()
        XCTAssertFalse(
            element(labeled: "Continue").exists,
            "Login form rendered despite a persisted session"
        )
    }

    func testWrongPasswordSurfacesServerErrorAndKeepsTheForm() {
        launch(resetSession: true)
        discoverLocalMode()
        enterCredentials(username: Self.username, password: "wrong-\(Self.password)")
        tapButton("Sign In")

        // The server's own message, verbatim from the 401 body.
        assertText(containing: "invalid credentials")
        XCTAssertTrue(element(labeled: "Sign In").exists, "Credentials form was lost on rejection")

        // A failed attempt must not leave anything restorable behind.
        app.terminate()
        launch(resetSession: false)
        assertText(containing: "Continue")
    }

    func testResetHatchClearsThePersistedSession() {
        launch(resetSession: true)
        signIn()
        assertGateScreen()

        app.terminate()
        launch(resetSession: true)
        assertText(containing: "Continue")
        XCTAssertFalse(
            element(labeled: "Refresh host counters").exists,
            "Gate screen appeared despite the session reset hatch"
        )
    }

    // MARK: - Flow helpers

    private func launch(resetSession: Bool) {
        app = XCUIApplication()
        app.launchEnvironment["HALO_AUTH_HOST"] = "oidc"
        app.launchEnvironment["HALO_SERVER_URL"] = Self.serverUrl
        if resetSession { app.launchEnvironment["HALO_RESET_SESSION"] = "1" }
        app.launch()
    }

    private func signIn() {
        discoverLocalMode()
        enterCredentials(username: Self.username, password: Self.password)
        tapButton("Sign In")
    }

    private func discoverLocalMode() {
        tapButton("Continue")
        assertText(containing: "Local mode discovered")
    }

    private func enterCredentials(username: String, password: String) {
        // Default IME actions: return on Username (Next) moves focus to
        // Password; return on Password (Done) hides the keyboard so the
        // Sign In button is hittable again.
        type(text: username + "\n", intoFieldLabeled: "Username")
        app.typeText(password + "\n")
    }

    private func type(text: String, intoFieldLabeled label: String) {
        let field = element(labeled: label)
        XCTAssertTrue(field.waitForExistence(timeout: 15), "Missing field: \(label)")
        field.tap()
        XCTAssertTrue(
            app.keyboards.firstMatch.waitForExistence(timeout: 10),
            "Keyboard never appeared for field: \(label)"
        )
        app.typeText(text)
    }

    // MARK: - Assertions

    private func assertGateScreen(timeout: TimeInterval = 20) {
        // "Refresh host counters" only exists on the gate screen, which the shell
        // auto-navigates to once the session state reports signed in.
        let gateMarker = element(labeled: "Refresh host counters")
        XCTAssertTrue(gateMarker.waitForExistence(timeout: timeout), "Gate screen never appeared")
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
