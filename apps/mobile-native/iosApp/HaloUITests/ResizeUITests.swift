import XCTest

/// Mid-playback resize gate (mpvkit/MPVKit#3): repeated rotation + layout resizes
/// while the core keeps playing. Uses only local media — no fixture server.
///
/// On the unpatched prebuilt Libmpv this documents the failure mode; with the
/// patched artifact the same sequence must keep core ID, position advance,
/// tracks, and geometry stable. Per-core mpv logs land in /tmp/halo-mpv-*.log.
final class ResizeUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .portrait
        app = XCUIApplication()
        // The runner receives TEST_RUNNER_-prefixed vars from xcodebuild; the
        // local media base is machine-specific so it rides the environment.
        if let localBase = ProcessInfo.processInfo.environment["HALO_MEDIA_LOCAL_BASE"] {
            app.launchEnvironment["HALO_MEDIA_LOCAL_BASE"] = localBase
        }
        app.launch()
        tapButton("Continue")
        tapButton("Open gate menu")
        tapButton("Player shell")
    }

    override func tearDownWithError() throws {
        XCUIDevice.shared.orientation = .portrait
    }

    func testTwentyMidPlaybackResizeCycles() throws {
        // No fixture server in this suite: the HTTP auto-load fails fast and
        // is immediately replaced by the local load — by design.
        tapExpecting("Load bitmap (local)", produces: "4K bitmap (local)")
        assertText(beginningWith: "Tracks: 2 audio · 1 subtitle", timeout: 60)
        tapExpecting("Resume", produces: "Status: Playing")
        waitForPosition(atLeast: 2.0)

        tapButton("Refresh counters")
        let initialCore = text(beginningWith: "Core instance: ")
        var lastPosition = currentPosition()

        for cycle in 1...20 {
            if cycle % 2 == 1 {
                XCUIDevice.shared.orientation = (cycle % 4 == 1) ? .landscapeLeft : .portrait
                settle(1.2)
            } else {
                tapButton("Cycle surface size")
                settle(0.8)
            }

            // The player must never fail mid-cycle, and playback must keep
            // moving — a frozen clock with a live UI is exactly the kind of
            // silent failure this gate exists to catch.
            XCTAssertFalse(
                textExists(beginningWith: "Status: Failed"),
                "Playback failed during resize cycle \(cycle)"
            )
            if cycle % 5 == 0 {
                waitForPosition(atLeast: lastPosition + 0.5, timeout: 30)
                lastPosition = currentPosition()
            }
        }

        XCUIDevice.shared.orientation = .portrait
        settle(1.2)
        tapButton("Refresh counters")
        XCTAssertEqual(
            text(beginningWith: "Core instance: "),
            initialCore,
            "Core identity must survive all resize cycles"
        )
        waitForPosition(atLeast: lastPosition + 1.0, timeout: 30)
        XCTAssertFalse(textExists(beginningWith: "Status: Failed"))
    }

    // MARK: helpers (mirrors PlaybackUITests; duplicated to keep suites independent)

    private func settle(_ seconds: TimeInterval) {
        RunLoop.current.run(until: Date().addingTimeInterval(seconds))
    }

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
            settle(0.5)
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

    private func textExists(beginningWith prefix: String) -> Bool {
        let predicate = NSPredicate(format: "label BEGINSWITH %@", prefix)
        return app.descendants(matching: .any).matching(predicate).firstMatch.exists
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

    private func tapExpecting(_ label: String, produces prefix: String, attempts: Int = 3) {
        for attempt in 1...attempts {
            tapButton(label)
            let predicate = NSPredicate(format: "label BEGINSWITH %@", prefix)
            let element = app.descendants(matching: .any).matching(predicate).firstMatch
            if element.waitForExistence(timeout: attempt == attempts ? 30 : 8) { return }
        }
        XCTFail("Tapping \(label) never produced text beginning with: \(prefix)")
    }

    private func tapButton(_ label: String) {
        let predicate = NSPredicate(format: "label == %@", label)
        let element = app.descendants(matching: .any).matching(predicate).firstMatch
        XCTAssertTrue(element.waitForExistence(timeout: 20), "Missing action: \(label)")

        var downSwipesLeft = 4
        while !element.isHittable && downSwipesLeft > 0 {
            app.swipeDown()
            settle(0.6)
            downSwipesLeft -= 1
        }
        var upSwipesLeft = 6
        while !element.isHittable && upSwipesLeft > 0 {
            app.swipeUp()
            settle(0.6)
            upSwipesLeft -= 1
        }
        XCTAssertTrue(element.isHittable, "Action is not hittable: \(label)")
        element.tap()
    }
}
