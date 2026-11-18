import XCTest

/// Real-device USB test: with a PTP device plugged in (a camera, or an Android phone in
/// "Transferring images" mode), starts importing everything and keeps screenshots while the
/// host side unplugs/replugs the device. Skipped unless a USB source is on screen.
/// Run: xcodebuild test -scheme UnrollUI -only-testing:UnrollUITests/USBImportRun -destination 'id=<device>'
final class USBImportRun: XCTestCase {
    let app = XCUIApplication()

    private func shot(_ name: String) {
        let a = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        a.name = name
        a.lifetime = .keepAlways
        add(a)
    }

    private func allowPhotos() {
        let sb = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        for label in ["Allow Full Access", "Allow Access to All Photos", "Allow", "OK",
                      "Cho phép truy cập đầy đủ", "Cho phép truy cập tất cả ảnh", "Cho phép"] {
            let b = sb.buttons[label]
            if b.waitForExistence(timeout: 1) { b.tap(); return }
        }
    }

    func testImportAllWhileHostTogglesUSB() throws {
        app.launchArguments += ["-debugPro", "YES"]   // DEBUG-only Pro unlock: no 10-a-day cap
        app.launch()
        let all = app.buttons.matching(NSPredicate(format: "label MATCHES %@", "^All( new)? \\d+$")).firstMatch
        guard all.waitForExistence(timeout: 20) else {
            shot("00-no-usb-library")
            throw XCTSkip("no USB library on screen")
        }
        sleep(15)   // let the catalog finish ("Reading card …") so "All" covers every file
        shot("01-library")
        all.tap()
        allowPhotos()
        // The host script unplugs ~30 s after the tap and replugs 15 s later; watch for 3 minutes.
        for i in 0..<36 {
            sleep(5)
            if i < 3 { allowPhotos() }
            shot(String(format: "%02d-t%03ds", i + 2, (i + 1) * 5))
        }
    }
}
