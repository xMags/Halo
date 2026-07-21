import XCTest

/// Real-playback gate for the owned libmpv adapter.
///
/// Prerequisites:
/// - `fixtures/fixture_server.py` running on 127.0.0.1:18787 with the two 4K
///   samples in `fixtures/media/`;
/// - the same files readable at the local media base passed via
///   `TEST_RUNNER_HALO_MEDIA_LOCAL_BASE` (a `file://` URL; forwarded to the
///   app's `HALO_MEDIA_LOCAL_BASE`).
///
/// Every load starts paused by design, so each test explicitly resumes and
/// then waits for the position to advance — that advance is the playback
/// evidence, not just a Ready event.
final class PlaybackUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        // These suites drive the harness signed out from the login form; a session
        // persisted by an auth suite or manual run must not auto-restore past it.
        app.launchEnvironment["HALO_RESET_SESSION"] = "1"
        // The runner receives TEST_RUNNER_-prefixed vars from xcodebuild; the
        // local media base is machine-specific so it rides the environment.
        if let localBase = ProcessInfo.processInfo.environment["HALO_MEDIA_LOCAL_BASE"] {
            app.launchEnvironment["HALO_MEDIA_LOCAL_BASE"] = localBase
        }
        app.launch()
        openPlayerShell()
    }

    func testHttpAssSamplePlaysAndSubtitleControlsAreLive() throws {
        // Auto-load is the HTTP ASS sample; it must reach Ready and pause.
        waitForStatus("Paused")
        assertText(beginningWith: "4K ASS (HTTP)")
        assertText(beginningWith: "Tracks: 1 audio · 1 subtitle")

        tapExpecting("Resume", produces: "Status: Playing")
        waitForPosition(atLeast: 2.0)

        // Live subtitle controls: echo line and stateful button labels must
        // update without any core recreation (ownership test covers counters).
        tapExpecting("Delay +0.5s", produces: "Sub style: delay 0.5s")
        tapExpecting("Scale 1.0", produces: "Sub style: delay 0.5s · scale 1.5")
        tapExpecting("Font: default", produces: "Sub style: delay 0.5s · scale 1.5 · font Courier New")

        // External ASS via sub-add must appear as a new subtitle track.
        tapExpecting("Add external sub", produces: "Tracks: 1 audio · 2 subtitle")

        // Seek forward and confirm playback continues past the target.
        let before = currentPosition()
        tapButton("+10s")
        waitForPosition(atLeast: before + 9.0)
    }

    func testHttpBitmapSamplePlays() throws {
        waitForStatus("Paused")
        tapExpecting("Load bitmap (HTTP)", produces: "4K bitmap (HTTP)")
        assertText(beginningWith: "Tracks: 2 audio · 1 subtitle", timeout: 60)

        tapExpecting("Resume", produces: "Status: Playing")
        waitForPosition(atLeast: 2.0)
    }

    func testLocalAssSamplePlays() throws {
        waitForStatus("Paused")
        tapExpecting("Load ASS (local)", produces: "4K ASS (local)")
        assertText(beginningWith: "Tracks: 1 audio · 1 subtitle", timeout: 60)

        tapExpecting("Resume", produces: "Status: Playing")
        waitForPosition(atLeast: 2.0)
    }

    func testLocalBitmapSamplePlays() throws {
        waitForStatus("Paused")
        tapExpecting("Load bitmap (local)", produces: "4K bitmap (local)")
        assertText(beginningWith: "Tracks: 2 audio · 1 subtitle", timeout: 60)

        tapExpecting("Resume", produces: "Status: Playing")
        waitForPosition(atLeast: 2.0)
    }

    /// Taps and requires the tap's observable effect. Compose can silently
    /// swallow a tap (e.g. as stop-fling); when the expected text does not
    /// appear the tap is retried. Safe because every expected text here can
    /// only be produced by that specific tap landing exactly once.
    private func tapExpecting(_ label: String, produces prefix: String, attempts: Int = 3) {
        for attempt in 1...attempts {
            tapButton(label)
            let predicate = NSPredicate(format: "label BEGINSWITH %@", prefix)
            let element = app.descendants(matching: .any).matching(predicate).firstMatch
            if element.waitForExistence(timeout: attempt == attempts ? 30 : 8) { return }
        }
        XCTFail("Tapping \(label) never produced text beginning with: \(prefix)")
    }

    // MARK: navigation

    private func openPlayerShell() {
        tapButton("Continue")
        tapButton("Open gate menu")
        tapButton("Player shell")
    }

    // MARK: waiting helpers

    private func waitForStatus(_ status: String, timeout: TimeInterval = 90) {
        let predicate = NSPredicate(format: "label == %@", "Status: \(status)")
        let element = app.descendants(matching: .any).matching(predicate).firstMatch
        XCTAssertTrue(
            element.waitForExistence(timeout: timeout),
            "Player never reached status \(status)"
        )
    }

    private func waitForPosition(atLeast seconds: Double, timeout: TimeInterval = 90) {
        let deadline = Date().addingTimeInterval(timeout)
        var last = -1.0
        while Date() < deadline {
            last = currentPosition()
            if last >= seconds { return }
            RunLoop.current.run(until: Date().addingTimeInterval(0.5))
        }
        XCTFail("Position \(last) never reached \(seconds) within \(timeout)s")
    }

    private func currentPosition() -> Double {
        let label = text(beginningWith: "Position: ")
        let expression = try! NSRegularExpression(pattern: "Position: ([0-9]+(?:\\.[0-9]+)?)s")
        let range = NSRange(label.startIndex..<label.endIndex, in: label)
        guard
            let match = expression.firstMatch(in: label, range: range),
            let valueRange = Range(match.range(at: 1), in: label),
            let value = Double(label[valueRange])
        else {
            return 0
        }
        return value
    }

    private func assertText(beginningWith prefix: String, timeout: TimeInterval = 30) {
        let predicate = NSPredicate(format: "label BEGINSWITH %@", prefix)
        let element = app.descendants(matching: .any).matching(predicate).firstMatch
        XCTAssertTrue(
            element.waitForExistence(timeout: timeout),
            "Missing text beginning with: \(prefix)"
        )
    }

    private func text(beginningWith prefix: String) -> String {
        let predicate = NSPredicate(format: "label BEGINSWITH %@", prefix)
        let element = app.descendants(matching: .any).matching(predicate).firstMatch
        XCTAssertTrue(element.waitForExistence(timeout: 20), "Missing text beginning with: \(prefix)")
        return element.label
    }

    private func tapButton(_ label: String) {
        let predicate = NSPredicate(format: "label == %@", label)
        let element = app.descendants(matching: .any).matching(predicate).firstMatch
        XCTAssertTrue(element.waitForExistence(timeout: 20), "Missing action: \(label)")

        // The player shell scrolls; bring off-screen controls into reach in
        // either direction. After each swipe, wait for the fling to settle —
        // Compose consumes a tap during a fling as "stop scrolling",
        // silently dropping the click.
        var downSwipesLeft = 4
        while !element.isHittable && downSwipesLeft > 0 {
            app.swipeDown()
            RunLoop.current.run(until: Date().addingTimeInterval(0.6))
            downSwipesLeft -= 1
        }
        var upSwipesLeft = 6
        while !element.isHittable && upSwipesLeft > 0 {
            app.swipeUp()
            RunLoop.current.run(until: Date().addingTimeInterval(0.6))
            upSwipesLeft -= 1
        }
        XCTAssertTrue(element.isHittable, "Action is not hittable: \(label)")
        element.tap()
    }
}
