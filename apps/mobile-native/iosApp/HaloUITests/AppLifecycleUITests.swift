import XCTest

/// Matrix row: background/foreground state across an active playback session.
///
/// Three home-press/reactivate cycles (the last with a longer 10s background
/// dwell) while the local ASS sample plays. Returning to the foreground must
/// find the same core and Swift-owned UIView (no create/teardown churn), no
/// Failed status, and a position that resumes advancing.
///
/// Simulator caveat: the simulator does not
/// enforce real device-style suspension, jetsam, or Metal
/// don't-render-in-background kills — device-phase evidence still owns those.
final class AppLifecycleUITests: XCTestCase {
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
        tapButton("Continue")
        tapButton("Open gate menu")
        tapButton("Player shell")
    }

    func testBackgroundForegroundCyclesKeepCoreAndPlaybackAlive() throws {
        // Local media keeps this suite hermetic; the HTTP auto-load is simply
        // replaced whether it reached Paused or failed fast.
        tapExpecting("Load ASS (local)", produces: "4K ASS (local)")
        assertText(beginningWith: "Tracks: 1 audio · 1 subtitle", timeout: 60)
        tapExpecting("Resume", produces: "Status: Playing")
        waitForPosition(atLeast: 2.0)

        tapButton("Refresh counters")
        let initialCore = text(beginningWith: "Core instance: ")
        let initialLifecycle = text(beginningWith: "Core lifecycle: ")

        for cycle in 1...3 {
            XCUIDevice.shared.press(.home)
            settle(cycle == 3 ? 10.0 : 3.0)
            app.activate()
            XCTAssertTrue(
                app.wait(for: .runningForeground, timeout: 15),
                "App did not return to foreground on cycle \(cycle)"
            )
            settle(1.0)

            XCTAssertFalse(
                textExists(beginningWith: "Status: Failed"),
                "Playback failed across background cycle \(cycle)"
            )

            tapButton("Refresh counters")
            XCTAssertEqual(
                text(beginningWith: "Core instance: "),
                initialCore,
                "Core identity must survive background cycle \(cycle)"
            )
            XCTAssertEqual(
                text(beginningWith: "Core lifecycle: "),
                initialLifecycle,
                "No core create/destroy may happen across background cycle \(cycle)"
            )
            XCTAssertEqual(
                counter("creations", in: text(beginningWith: "Player view creations: ")),
                1,
                "Player UIView must survive background cycle \(cycle)"
            )
            XCTAssertEqual(
                counter("teardown", in: text(beginningWith: "Playback: ")),
                0,
                "No playback teardown may happen across background cycle \(cycle)"
            )

            // Foreground playback must keep moving after every return.
            let position = currentPosition()
            waitForPosition(atLeast: position + 1.0, timeout: 30)
        }
    }

    // MARK: helpers (mirrors PlaybackUITests; duplicated to keep suites independent)

    private func settle(_ seconds: TimeInterval) {
        RunLoop.current.run(until: Date().addingTimeInterval(seconds))
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

    private func counter(_ name: String, in label: String) -> Int {
        let expression = try! NSRegularExpression(pattern: "\\b\(name)\\b\\s*:?\\s*([0-9]+)\\b")
        let range = NSRange(label.startIndex..<label.endIndex, in: label)
        guard
            let match = expression.firstMatch(in: label, range: range),
            let valueRange = Range(match.range(at: 1), in: label),
            let value = Int(label[valueRange])
        else {
            XCTFail("Missing counter '\(name)' in '\(label)'")
            return -1
        }
        return value
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
