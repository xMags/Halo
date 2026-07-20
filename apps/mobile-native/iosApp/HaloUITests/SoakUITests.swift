import XCTest

/// Matrix row: 30-minute simulator leak/hang soak.
///
/// Plays local media continuously for 30 minutes on one core. The loop keeps
/// playback moving (resumes paused auto-queued media, reloads after natural
/// end) and fails on either failure mode this row exists to catch:
/// - hang: status Playing but position frozen for 3 consecutive 20s samples;
/// - death: Status: Failed at any point.
///
/// Memory is sampled host-side (RSS of the app process) by the runner script
/// while this test runs; leak judgment happens there, not in-process.
final class SoakUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
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

    func testThirtyMinutePlaybackSoak() throws {
        tapExpecting("Load ASS (local)", produces: "4K ASS (local)")
        assertText(beginningWith: "Tracks: 1 audio · 1 subtitle", timeout: 60)
        tapExpecting("Resume", produces: "Status: Playing")
        waitForPosition(atLeast: 2.0)

        tapButton("Refresh counters")
        let initialCore = text(beginningWith: "Core instance: ")

        let deadline = Date().addingTimeInterval(30 * 60)
        var lastPosition = -1.0
        var stallStrikes = 0
        var pushForwards = 0

        while Date() < deadline {
            settle(20)

            XCTAssertFalse(
                textExists(beginningWith: "Status: Failed"),
                "Playback failed during soak after \(pushForwards) push-forwards"
            )

            let status = text(beginningWith: "Status: ")
            let position = currentPosition()

            if status.contains("Playing") {
                if position > lastPosition {
                    stallStrikes = 0
                    lastPosition = position
                } else {
                    stallStrikes += 1
                    XCTAssertLessThan(
                        stallStrikes,
                        3,
                        "Hang: status Playing but position frozen at \(position)s for \(stallStrikes * 20)s"
                    )
                }
                continue
            }

            // Paused (auto-queued media lands paused by design) or
            // Ended/Idle (media ran out): push playback forward.
            pushForwards += 1
            if status.contains("Paused") {
                tapExpecting("Resume", produces: "Status: Playing")
            } else {
                tapExpecting("Load ASS (local)", produces: "4K ASS (local)")
                assertText(beginningWith: "Tracks: 1 audio · 1 subtitle", timeout: 60)
                tapExpecting("Resume", produces: "Status: Playing")
            }
            lastPosition = -1.0
            stallStrikes = 0
        }

        // 30 minutes of one-core playback must not have churned ownership.
        tapButton("Refresh counters")
        XCTAssertEqual(
            text(beginningWith: "Core instance: "),
            initialCore,
            "Core identity must survive the soak"
        )
        let lifecycle = text(beginningWith: "Core lifecycle: ")
        XCTAssertEqual(counter("create", in: lifecycle), 1)
        XCTAssertEqual(counter("destroy", in: lifecycle), 0)
        XCTAssertEqual(counter("creations", in: text(beginningWith: "Player view creations: ")), 1)
        XCTAssertFalse(textExists(beginningWith: "Status: Failed"))
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
