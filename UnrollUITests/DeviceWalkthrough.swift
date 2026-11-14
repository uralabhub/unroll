import XCTest

/// Drives the main flow on a real device and keeps a screenshot of each step.
/// Run: xcodebuild test -scheme UnrollUI -destination 'id=<device>'
final class DeviceWalkthrough: XCTestCase {
    let app = XCUIApplication()

    override func setUp() {
        continueAfterFailure = true
        app.launch()
    }

    private func shot(_ name: String) {
        let a = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        a.name = name
        a.lifetime = .keepAlways
        add(a)
    }

    private func tap(_ text: String, timeout: TimeInterval = 6) -> Bool {
        let p = NSPredicate(format: "label CONTAINS[c] %@", text)
        let el = app.descendants(matching: .any).matching(p).firstMatch
        guard el.waitForExistence(timeout: timeout) else { XCTFail("missing: \(text)"); return false }
        el.tap()
        return true
    }

    /// Filter tabs read "Picks 16"; match the whole label so "Import picks 16" / "All new 42" aren't hit.
    private func tapTab(_ name: String) -> Bool {
        let el = app.buttons.matching(NSPredicate(format: "label MATCHES %@", "^\\Q\(name)\\E \\d+$")).firstMatch
        guard el.waitForExistence(timeout: 3) else { return false }
        el.tap(); return true
    }

    /// Edge-swipe back, like a thumb would.
    private func back() {
        let w = app.windows.firstMatch
        w.coordinate(withNormalizedOffset: CGVector(dx: 0.01, dy: 0.5))
            .press(forDuration: 0.05, thenDragTo: w.coordinate(withNormalizedOffset: CGVector(dx: 0.8, dy: 0.5)))
    }

    /// Accepts the Photos permission sheet if iOS shows one.
    private func allowPhotos() {
        let sb = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        for label in ["Allow Full Access", "Allow Access to All Photos", "Allow", "OK",
                      "Cho phép truy cập đầy đủ", "Cho phép truy cập tất cả ảnh", "Cho phép", "Not now"] {
            let b = sb.buttons[label]
            if b.waitForExistence(timeout: 1.5) { b.tap(); return }
        }
    }

    func testWalkthrough() {
        sleep(2); shot("01-home")
        guard tap("demo roll") else { return }
        sleep(2); shot("02-library")

        if tapTab("Picks") { sleep(1); shot("03-picks") }
        _ = tapTab("All")

        let cell = app.scrollViews.firstMatch.images.firstMatch
        if cell.waitForExistence(timeout: 4) {
            cell.tap(); sleep(2); shot("04-detail")
            if tap("settings", timeout: 3) { sleep(1); shot("05-recipe") }
            if tap("Frame", timeout: 3) {
                sleep(2); shot("06-frame")
                _ = tap("Close", timeout: 3); sleep(1)
            }
            back(); sleep(1)
        } else {
            XCTFail("no grid cell"); shot("04-nogrid")
        }

        if tap("Import picks", timeout: 4) {
            usleep(700_000); shot("07a-tapped"); allowPhotos(); shot("07-importing")
            sleep(8); allowPhotos(); shot("08-after-import")
        }
    }
}
