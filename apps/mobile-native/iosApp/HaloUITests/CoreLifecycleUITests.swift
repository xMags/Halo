import XCTest

/// Matrix row: 20 explicit core destroy/recreate cycles.
///
/// Explicit recreate is the only path allowed to change the core identity.
/// Each cycle must destroy exactly one core and create exactly one, produce a
/// never-seen-before core id, and leave the Swift-owned player UIView
/// untouched (creations stays 1). The 21st core must still be fully
/// functional: the final local load reaches Ready and plays with an advancing
/// position. Per-core mpv logs land in /tmp/halo-mpv-*.log for host-side
/// inspection (one per created core).
final class CoreLifecycleUITests: XCTestCase {
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

    func testTwentyDestroyRecreateCyclesKeepViewAndEndPlayable() throws {
        tapButton("Refresh counters")

        var seenCores: Set<String> = [text(beginningWith: "Core instance: ")]
        let initialLifecycle = text(beginningWith: "Core lifecycle: ")
        XCTAssertEqual(counter("create", in: initialLifecycle), 1)
        XCTAssertEqual(counter("destroy", in: initialLifecycle), 0)
        XCTAssertEqual(counter("creations", in: text(beginningWith: "Player view creations: ")), 1)

        for cycle in 1...20 {
            tapButton("Explicitly recreate player core")
            tapButton("Refresh counters")

            let core = text(beginningWith: "Core instance: ")
            XCTAssertTrue(
                seenCores.insert(core).inserted,
                "Core id was reused on cycle \(cycle): \(core)"
            )

            let lifecycle = text(beginningWith: "Core lifecycle: ")
            XCTAssertEqual(counter("create", in: lifecycle), cycle + 1, "cycle \(cycle)")
            XCTAssertEqual(counter("destroy", in: lifecycle), cycle, "cycle \(cycle)")
            XCTAssertEqual(
                counter("creations", in: text(beginningWith: "Player view creations: ")),
                1,
                "Player UIView must survive recreate cycle \(cycle)"
            )
        }

        // The 21st core is not just countable — it must still play.
        tapExpecting("Load ASS (local)", produces: "4K ASS (local)")
        assertText(beginningWith: "Tracks: 1 audio · 1 subtitle", timeout: 60)
        tapExpecting("Resume", produces: "Status: Playing")
        waitForPosition(atLeast: 2.0)
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
