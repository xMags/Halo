import XCTest

/// Shipping player flows over the real fixture API and Swift-owned libmpv host.
///
/// Prerequisites:
/// - `corepack pnpm --filter @halo/api dev:fixtures --media <file>` on port 18790;
/// - the fixture media contains one embedded ASS subtitle track.
final class PlayerScreenUITests: XCTestCase {
    private static let serverUrl = "http://127.0.0.1:18790"
    private static let username = "admin"
    private static let password = "fixture-pass"

    private var app: XCUIApplication!
    private var baselineSystemState = ""

    override func setUpWithError() throws {
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .portrait

        app = XCUIApplication()
        app.launchEnvironment["HALO_AUTH_HOST"] = "oidc"
        app.launchEnvironment["HALO_SERVER_URL"] = Self.serverUrl
        app.launchEnvironment["HALO_RESET_SESSION"] = "1"
        app.launchEnvironment["HALO_UI_TEST_SYSTEM_DIAGNOSTICS"] = "1"
        app.launch()

        baselineSystemState = systemState()
        openPlayer()
    }

    override func tearDownWithError() throws {
        app?.terminate()
        XCUIDevice.shared.orientation = .portrait
    }

    func testChromeAutoHidesAfterPlaybackResumes() {
        resumePlayback()
        XCTAssertTrue(element(labeled: "SUBTITLES").waitForExistence(timeout: 5))

        let chromeGone = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "exists == false"),
            object: element(labeled: "SUBTITLES")
        )
        XCTAssertEqual(
            XCTWaiter.wait(for: [chromeGone], timeout: 8),
            .completed,
            "Player chrome did not auto-hide after playback resumed"
        )
    }

    func testSubtitleSwitchDoesNotResetPlaybackPosition() {
        resumePlayback()
        waitForElapsed(atLeast: 2)
        showChrome()
        tapElement(labeled: "SUBTITLES")

        tapElement(containingAll: ["eng", "ASS"])
        tapElement(labeled: "Close playback options")
        XCTAssertTrue(element(labeled: "eng").waitForExistence(timeout: 10))
        XCTAssertTrue(element(labeled: ".ASS").waitForExistence(timeout: 10))

        showChrome()
        tapElement(labeled: "SUBTITLES")
        let before = waitForElapsed(atLeast: 5)
        tapElement(containingAll: ["Off", "No subtitles"])
        tapElement(labeled: "Close playback options")
        XCTAssertTrue(element(labeled: "Off").waitForExistence(timeout: 10))

        let after = elapsedSeconds()
        XCTAssertGreaterThanOrEqual(
            after,
            before,
            "Playback reset from \(before)s to \(after)s after switching subtitles"
        )
    }

    func testBackReleasesOrientationAndIdleTimerClaims() {
        waitForSystemState(containingAll: [
            "landscape locked",
            "screen claimed",
            "idle disabled",
        ])
        showChrome()
        tapElement(labeled: "Back")
        XCTAssertTrue(element(labeled: "Sources").waitForExistence(timeout: 15))

        waitForSystemState(equalTo: baselineSystemState)
        XCUIDevice.shared.orientation = .portrait
        XCTAssertTrue(waitForPortrait(), "Portrait remained blocked after leaving the player")
    }

    // MARK: - Shipping flow

    private func openPlayer() {
        tapElement(labeled: "Continue")
        assertText(containing: "Local mode discovered")
        type(text: Self.username + "\n", intoFieldLabeled: "Username")
        app.typeText(Self.password + "\n")
        tapElement(labeled: "Sign In")

        XCTAssertTrue(element(labeled: "The Matrix").waitForExistence(timeout: 20))
        tapElement(labeled: "The Matrix")
        tapElement(labeled: "Sources")
        tapElement(labeled: "tt0133093.2160p.WEB-DL.DDP5.1.HDR.HEVC.mkv")

        XCTAssertTrue(
            element(beginningWith: "Elapsed ").waitForExistence(timeout: 60),
            "The shipping player never became ready"
        )
        showChrome()
    }

    private func resumePlayback() {
        showChrome()
        tapElement(labeled: "Play")
        XCTAssertTrue(element(labeled: "Pause").waitForExistence(timeout: 10))
    }

    private func showChrome() {
        if element(labeled: "SUBTITLES").exists { return }
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        XCTAssertTrue(
            element(labeled: "SUBTITLES").waitForExistence(timeout: 5),
            "Tapping the video did not show player chrome"
        )
    }

    private func type(text: String, intoFieldLabeled label: String) {
        let field = element(labeled: label)
        XCTAssertTrue(field.waitForExistence(timeout: 15), "Missing field: \(label)")
        field.tap()
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 10), "Keyboard never appeared")
        app.typeText(text)
    }

    // MARK: - Player assertions

    @discardableResult
    private func waitForElapsed(atLeast seconds: Int, timeout: TimeInterval = 30) -> Int {
        let deadline = Date().addingTimeInterval(timeout)
        var last = -1
        while Date() < deadline {
            last = elapsedSeconds()
            if last >= seconds { return last }
            RunLoop.current.run(until: Date().addingTimeInterval(0.25))
        }
        XCTFail("Elapsed position \(last)s never reached \(seconds)s")
        return last
    }

    private func elapsedSeconds() -> Int {
        let label = element(beginningWith: "Elapsed ").label
        let expression = try! NSRegularExpression(pattern: "Elapsed ([0-9]+(?::[0-9]{2}){1,2})")
        let range = NSRange(label.startIndex..<label.endIndex, in: label)
        guard
            let match = expression.firstMatch(in: label, range: range),
            let valueRange = Range(match.range(at: 1), in: label)
        else {
            return -1
        }
        let parts = label[valueRange].split(separator: ":").compactMap { Int($0) }
        switch parts.count {
        case 2: return parts[0] * 60 + parts[1]
        case 3: return parts[0] * 3600 + parts[1] * 60 + parts[2]
        default: return -1
        }
    }

    private func systemState() -> String {
        let diagnostics = element(identifier: "Player system diagnostics")
        XCTAssertTrue(diagnostics.waitForExistence(timeout: 10), "Missing player system diagnostics")
        return diagnostics.label
    }

    private func waitForSystemState(
        equalTo expected: String,
        timeout: TimeInterval = 10
    ) {
        let diagnostics = element(identifier: "Player system diagnostics")
        let state = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "label == %@", expected),
            object: diagnostics
        )
        XCTAssertEqual(
            XCTWaiter.wait(for: [state], timeout: timeout),
            .completed,
            "System state did not return to: \(expected)"
        )
    }

    private func waitForSystemState(
        containingAll fragments: [String],
        timeout: TimeInterval = 10
    ) {
        let diagnostics = element(identifier: "Player system diagnostics")
        let predicate = NSCompoundPredicate(
            andPredicateWithSubpredicates: fragments.map { NSPredicate(format: "label CONTAINS %@", $0) }
        )
        let state = XCTNSPredicateExpectation(predicate: predicate, object: diagnostics)
        XCTAssertEqual(
            XCTWaiter.wait(for: [state], timeout: timeout),
            .completed,
            "System state did not contain: \(fragments.joined(separator: ", "))"
        )
    }

    private func waitForPortrait(timeout: TimeInterval = 10) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if XCUIDevice.shared.orientation == .portrait { return true }
            RunLoop.current.run(until: Date().addingTimeInterval(0.25))
        }
        return false
    }

    // MARK: - UI helpers

    private func assertText(containing substring: String, timeout: TimeInterval = 20) {
        XCTAssertTrue(
            element(containingAll: [substring]).waitForExistence(timeout: timeout),
            "Missing text containing: \(substring)"
        )
    }

    private func element(labeled label: String) -> XCUIElement {
        let predicate = NSPredicate(format: "label == %@", label)
        return app.descendants(matching: .any).matching(predicate).firstMatch
    }

    private func element(beginningWith prefix: String) -> XCUIElement {
        let predicate = NSPredicate(format: "label BEGINSWITH %@", prefix)
        return app.descendants(matching: .any).matching(predicate).firstMatch
    }

    private func element(identifier: String) -> XCUIElement {
        let predicate = NSPredicate(format: "identifier == %@", identifier)
        return app.descendants(matching: .any).matching(predicate).firstMatch
    }

    private func element(containingAll fragments: [String]) -> XCUIElement {
        let predicates = fragments.map { NSPredicate(format: "label CONTAINS %@", $0) }
        let predicate = NSCompoundPredicate(andPredicateWithSubpredicates: predicates)
        return app.descendants(matching: .any).matching(predicate).firstMatch
    }

    private func tapElement(labeled label: String) {
        let predicate = NSPredicate(format: "label == %@", label)
        tap(app.descendants(matching: .any).matching(predicate), description: label)
    }

    private func tapElement(containingAll fragments: [String]) {
        let predicates = fragments.map { NSPredicate(format: "label CONTAINS %@", $0) }
        let predicate = NSCompoundPredicate(andPredicateWithSubpredicates: predicates)
        tap(
            app.descendants(matching: .any).matching(predicate),
            description: fragments.joined(separator: " + ")
        )
    }

    private func tap(_ query: XCUIElementQuery, description: String) {
        let firstMatch = query.firstMatch
        XCTAssertTrue(firstMatch.waitForExistence(timeout: 20), "Missing action: \(description)")
        var swipesLeft = 6
        while swipesLeft >= 0 {
            if let hittable = query.allElementsBoundByIndex.first(where: { $0.isHittable }) {
                hittable.tap()
                return
            }
            if swipesLeft == 0 { break }
            app.swipeUp()
            RunLoop.current.run(until: Date().addingTimeInterval(0.5))
            swipesLeft -= 1
        }
        XCTFail("Action is not hittable: \(description)")
    }
}
