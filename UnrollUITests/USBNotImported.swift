import XCTest

/// After a USB import run: shows what is still not imported, for the record.
final class USBNotImported: XCTestCase {
    func testShowNotImported() throws {
        let app = XCUIApplication()
        app.launchArguments += ["-debugPro", "YES", "-onboarded", "YES"]
        app.launch()
        let tab = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Not imported")).firstMatch
        guard tab.waitForExistence(timeout: 30) else { throw XCTSkip("no USB library on screen") }
        sleep(10)
        tab.tap()
        sleep(3)
        let a = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        a.name = "not-imported"; a.lifetime = .keepAlways; add(a)
        let cell = app.scrollViews.firstMatch.images.firstMatch
        if cell.waitForExistence(timeout: 3) {
            cell.tap(); sleep(3)
            let b = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
            b.name = "not-imported-detail"; b.lifetime = .keepAlways; add(b)
        }
    }
}
