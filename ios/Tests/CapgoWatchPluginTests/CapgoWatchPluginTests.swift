import XCTest
@testable import CapgoWatchPlugin

class CapgoWatchPluginTests: XCTestCase {
    func testPluginExists() throws {
        let plugin = CapgoWatchPlugin()
        XCTAssertNotNil(plugin)
        XCTAssertEqual(plugin.jsName, "CapgoWatch")
        XCTAssertEqual(plugin.identifier, "CapgoWatchPlugin")
    }
}
