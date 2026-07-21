import XCTest

final class OwnershipUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        // These suites drive the harness signed out from the login form; a session
        // persisted by an auth suite or manual run must not auto-restore past it.
        app.launchEnvironment["HALO_RESET_SESSION"] = "1"
        app.launch()
    }

    func testSwiftHostOwnsCoreAndViewAcrossComposeLifecycle() throws {
        tapButton("Continue")
        tapButton("Open gate menu")
        tapButton("Player shell")
        tapButton("Refresh counters")

        let initialCore = text(beginningWith: "Core instance: ")
        let initialView = text(beginningWith: "Player view instance: ")
        let initialCoreLifecycle = text(beginningWith: "Core lifecycle: ")
        let initialViewCreations = text(beginningWith: "Player view creations: ")
        let initialSurface = text(beginningWith: "Surface: ")
        let initialPlayback = text(beginningWith: "Playback: ")

        XCTAssertEqual(counter("create", in: initialCoreLifecycle), 1)
        XCTAssertEqual(counter("destroy", in: initialCoreLifecycle), 0)
        XCTAssertEqual(counter("creations", in: initialViewCreations), 1)
        XCTAssertEqual(counter("load", in: initialPlayback), 1)
        XCTAssertEqual(counter("teardown", in: initialPlayback), 0)
        XCTAssertGreaterThanOrEqual(counter("attach", in: initialSurface), 1)

        tapButton("Recompose")
        tapButton("Refresh counters")

        XCTAssertEqual(text(beginningWith: "Core instance: "), initialCore)
        XCTAssertEqual(text(beginningWith: "Player view instance: "), initialView)
        XCTAssertEqual(text(beginningWith: "Core lifecycle: "), initialCoreLifecycle)
        XCTAssertEqual(text(beginningWith: "Player view creations: "), initialViewCreations)
        XCTAssertEqual(text(beginningWith: "Surface: "), initialSurface)
        XCTAssertEqual(text(beginningWith: "Playback: "), initialPlayback)

        tapButton("Gate")
        tapButton("Refresh host counters")

        let detachedSurface = text(beginningWith: "Surface: ")
        XCTAssertEqual(text(beginningWith: "Core instance: "), initialCore)
        XCTAssertEqual(text(beginningWith: "Player view instance: "), initialView)
        XCTAssertEqual(counter("attach", in: detachedSurface), counter("attach", in: initialSurface))
        XCTAssertEqual(counter("detach", in: detachedSurface), counter("detach", in: initialSurface) + 1)
        XCTAssertEqual(counter("load", in: text(beginningWith: "Playback: ")), 1)

        tapButton("Player shell")
        tapButton("Refresh counters")

        let reattachedSurface = text(beginningWith: "Surface: ")
        XCTAssertEqual(text(beginningWith: "Core instance: "), initialCore)
        XCTAssertEqual(text(beginningWith: "Player view instance: "), initialView)
        XCTAssertEqual(counter("attach", in: reattachedSurface), counter("attach", in: initialSurface) + 1)
        XCTAssertEqual(counter("detach", in: reattachedSurface), counter("detach", in: detachedSurface))
        XCTAssertEqual(counter("load", in: text(beginningWith: "Playback: ")), 1)

        tapButton("Explicitly recreate player core")

        let recreatedCore = text(beginningWith: "Core instance: ")
        let recreatedLifecycle = text(beginningWith: "Core lifecycle: ")
        XCTAssertNotEqual(recreatedCore, initialCore)
        XCTAssertEqual(text(beginningWith: "Player view instance: "), initialView)
        XCTAssertEqual(counter("create", in: recreatedLifecycle), 2)
        XCTAssertEqual(counter("destroy", in: recreatedLifecycle), 1)
        XCTAssertEqual(counter("creations", in: text(beginningWith: "Player view creations: ")), 1)
        XCTAssertEqual(counter("load", in: text(beginningWith: "Playback: ")), 1)
        XCTAssertEqual(counter("teardown", in: text(beginningWith: "Playback: ")), 0)
    }

    private func tapButton(_ label: String) {
        let predicate = NSPredicate(format: "label == %@", label)
        let element = app.descendants(matching: .any).matching(predicate).firstMatch
        XCTAssertTrue(element.waitForExistence(timeout: 10), "Missing action: \(label)")

        let hittable = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "hittable == true"),
            object: element
        )
        XCTAssertEqual(
            XCTWaiter.wait(for: [hittable], timeout: 10),
            .completed,
            "Action is not hittable: \(label)"
        )
        element.tap()
    }

    private func text(beginningWith prefix: String) -> String {
        let predicate = NSPredicate(format: "label BEGINSWITH %@", prefix)
        let element = app.descendants(matching: .any).matching(predicate).firstMatch
        XCTAssertTrue(element.waitForExistence(timeout: 10), "Missing text beginning with: \(prefix)")
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
}
