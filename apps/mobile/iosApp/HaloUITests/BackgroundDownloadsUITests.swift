import XCTest

/// A shipping download through the real API fixture and iOS background URLSession.
///
/// Prerequisites:
/// - `python3 -u fixtures/fixture_server.py` on port 18787;
/// - the API fixture on port 18790, with every stream pointed at a generated,
///   throttled source so the test can observe work on both sides of Home:
///
/// `corepack pnpm --filter @halo/api dev:fixtures --media-url
/// "http://127.0.0.1:18787/media/generated.bin?fixture_size=67108864&fixture_throttle_ms=20"`
///
/// The relaunch happens only after completion. XCUITest termination is a user
/// force-quit, which iOS explicitly exempts from background-transfer guarantees.
final class BackgroundDownloadsUITests: XCTestCase {
    private static let serverUrl = "http://127.0.0.1:18790"
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .portrait
        app = XCUIApplication()
        app.launchEnvironment["HALO_AUTH_HOST"] = "oidc"
        app.launchEnvironment["HALO_SERVER_URL"] = Self.serverUrl
        app.launchEnvironment["HALO_RESET_SESSION"] = "1"
        addUIInterruptionMonitor(withDescription: "Download notifications") { alert in
            let allow = alert.buttons["Allow"]
            guard allow.exists else { return false }
            allow.tap()
            return true
        }
        app.launch()
    }

    override func tearDownWithError() throws {
        if app?.state == .runningForeground {
            openDownloads()
            if element(labeled: "Delete download").exists {
                tap(labeled: "Delete download")
                tap(labeled: "Delete from device")
            }
        }
        app?.terminate()
        XCUIDevice.shared.orientation = .portrait
    }

    func testDownloadAdvancesAcrossHomeAndSurvivesPostCompletionRelaunch() {
        signInAndStartDownload()
        openDownloads()

        let progress = element(containing: " of ")
        XCTAssertTrue(progress.waitForExistence(timeout: 30), "No byte progress appeared")
        let beforeHome = progress.label

        XCUIDevice.shared.press(.home)
        RunLoop.current.run(until: Date().addingTimeInterval(5))
        app.activate()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 20))
        openDownloads()

        let readyHeader = element(labeled: "ON THIS DEVICE")
        if !readyHeader.exists {
            let afterHome = element(containing: " of ")
            XCTAssertTrue(afterHome.waitForExistence(timeout: 20), "Download disappeared after Home")
            XCTAssertNotEqual(afterHome.label, beforeHome, "Download made no progress while Halo was backgrounded")
        }
        XCTAssertTrue(readyHeader.waitForExistence(timeout: 180), "Background URLSession never completed")
        XCTAssertTrue(element(labeled: "Play").waitForExistence(timeout: 10))

        app.terminate()
        app.launchEnvironment["HALO_RESET_SESSION"] = "0"
        app.launch()
        XCTAssertTrue(element(labeled: "Downloads").waitForExistence(timeout: 30))
        openDownloads()
        XCTAssertTrue(element(labeled: "ON THIS DEVICE").waitForExistence(timeout: 30))
        XCTAssertTrue(element(labeled: "Play").waitForExistence(timeout: 10))
    }

    private func signInAndStartDownload() {
        tap(labeled: "Continue")
        XCTAssertTrue(element(containing: "Local mode discovered").waitForExistence(timeout: 20))
        type(text: "admin\n", intoFieldLabeled: "Username")
        app.typeText("fixture-pass\n")
        tap(labeled: "Sign In")
        tap(labeled: "The Matrix")
        tap(labeled: "Sources")
        XCTAssertTrue(
            element(labeled: "Download this source").waitForExistence(timeout: 30),
            "Fixture source did not expose its download action"
        )
        tap(labeled: "Download this source")
        app.tap()
    }

    private func openDownloads() {
        if element(labeled: "ON THIS DEVICE").exists || element(labeled: "IN PROGRESS").exists { return }
        tap(labeled: "Downloads")
    }

    private func type(text: String, intoFieldLabeled label: String) {
        let field = element(labeled: label)
        XCTAssertTrue(field.waitForExistence(timeout: 15), "Missing field: \(label)")
        field.tap()
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 10), "Keyboard never appeared")
        app.typeText(text)
    }

    private func element(labeled label: String) -> XCUIElement {
        app.descendants(matching: .any)
            .matching(NSPredicate(format: "label == %@", label))
            .firstMatch
    }

    private func element(containing text: String) -> XCUIElement {
        app.descendants(matching: .any)
            .matching(NSPredicate(format: "label CONTAINS %@", text))
            .firstMatch
    }

    private func tap(labeled label: String) {
        let matches = app.descendants(matching: .any)
            .matching(NSPredicate(format: "label == %@", label))
        XCTAssertTrue(matches.firstMatch.waitForExistence(timeout: 30), "Missing action: \(label)")
        for _ in 0..<8 {
            if let hittable = matches.allElementsBoundByIndex.first(where: { $0.isHittable }) {
                hittable.tap()
                return
            }
            app.swipeUp()
            RunLoop.current.run(until: Date().addingTimeInterval(0.4))
        }
        XCTFail("Action is not hittable: \(label)")
    }
}
