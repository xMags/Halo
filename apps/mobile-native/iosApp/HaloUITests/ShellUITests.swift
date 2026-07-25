import XCTest

/// The signed-in shell: that a session lands on it, that its tabs switch, and
/// that the diagnostics harness is still reachable from Settings now that it is
/// no longer where sign-in arrives.
///
/// Prerequisites:
/// - the fixture server in local mode on its own port, the same instance
///   `LocalAuthUITests` uses:
///   `python3 -u fixtures/fixture_server.py --port 18788 --auth-mode local`
///
/// The tab screens are placeholders, so the per-tab content assertions name
/// their filler rows; those move to real content when the screens land.
final class ShellUITests: XCTestCase {
    private static let serverUrl = "http://127.0.0.1:18788"
    private static let username = "fixture-user"
    private static let password = "fixture-pass"

    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    // MARK: - Tests

    func testSignInLandsOnTheShellWithEveryTab() {
        launch()
        signIn()

        for tab in ["Home", "Library", "Downloads", "Settings"] {
            XCTAssertTrue(
                element(labeled: tab).waitForExistence(timeout: 20),
                "Missing tab: \(tab)"
            )
        }
        assertText(containing: "Home row 1")
    }

    func testTabsSwitchContent() {
        launch()
        signIn()
        assertText(containing: "Home row 1")

        tapButton("Library")
        assertText(containing: "Library row 1")

        tapButton("Downloads")
        assertText(containing: "Downloads row 1")
    }

    func testDebugGateOpensFromSettingsAndReturns() {
        launch()
        signIn()

        tapButton("Settings")
        tapButton("Debug gate")
        XCTAssertTrue(
            element(labeled: "Refresh host counters").waitForExistence(timeout: 20),
            "Debug gate never appeared"
        )
        // The session row proves the gate is reading the same signed-in state
        // the shell was showing, not a fresh one.
        assertText(beginningWith: "Session: local · \(Self.serverUrl)")

        tapButton("Back to app")
        XCTAssertTrue(
            element(labeled: "Library").waitForExistence(timeout: 20),
            "Returning from the gate did not land back on the shell"
        )
    }

    // MARK: - Flow helpers

    private func launch() {
        app = XCUIApplication()
        app.launchEnvironment["HALO_AUTH_HOST"] = "oidc"
        app.launchEnvironment["HALO_SERVER_URL"] = Self.serverUrl
        app.launchEnvironment["HALO_RESET_SESSION"] = "1"
        app.launch()
    }

    private func signIn() {
        tapButton("Continue")
        assertText(containing: "Local mode discovered")
        type(text: Self.username + "\n", intoFieldLabeled: "Username")
        app.typeText(Self.password + "\n")
        tapButton("Sign In")
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

    private func assertText(containing substring: String, timeout: TimeInterval = 20) {
        let predicate = NSPredicate(format: "label CONTAINS %@", substring)
        let element = app.descendants(matching: .any).matching(predicate).firstMatch
        XCTAssertTrue(element.waitForExistence(timeout: timeout), "Missing text containing: \(substring)")
    }

    private func assertText(beginningWith prefix: String, timeout: TimeInterval = 20) {
        let predicate = NSPredicate(format: "label BEGINSWITH %@", prefix)
        let element = app.descendants(matching: .any).matching(predicate).firstMatch
        XCTAssertTrue(element.waitForExistence(timeout: timeout), "Missing text beginning with: \(prefix)")
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
