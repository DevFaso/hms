import XCTest

final class ExampleUITests: XCTestCase {
    func testLaunch() {
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.buttons.count > 0)
    }
}
