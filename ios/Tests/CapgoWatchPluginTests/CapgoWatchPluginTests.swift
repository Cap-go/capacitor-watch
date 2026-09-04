import XCTest
@testable import CapgoWatchPlugin

class CapgoWatchPluginTests: XCTestCase {
    func testPluginExists() throws {
        let plugin = CapgoWatchPlugin()
        XCTAssertNotNil(plugin)
        XCTAssertEqual(plugin.jsName, "CapgoWatch")
        XCTAssertEqual(plugin.identifier, "CapgoWatchPlugin")
    }

    func testPluginMethodsIncludeGetReceivedState() throws {
        let plugin = CapgoWatchPlugin()
        let methodNames = plugin.pluginMethods.map { $0.name }
        XCTAssertTrue(methodNames.contains("getReceivedState"))
        XCTAssertTrue(methodNames.contains("sendMessage"))
    }
}

class CapgoWatchMessageConverterTests: XCTestCase {
    func testConvertFromWatchMessageHandlesNestedObjects() {
        let input: [String: Any] = [
            "action": "ping",
            "meta": ["count": 2, "enabled": true]
        ]

        let converted = CapgoWatchMessageConverter.convertFromWatchMessage(input)
        XCTAssertEqual(converted["action"] as? String, "ping")
        let meta = converted["meta"] as? [String: Any]
        XCTAssertEqual(meta?["count"] as? Int, 2)
        XCTAssertEqual(meta?["enabled"] as? Bool, true)
    }

    func testConvertFromWatchMessageRoundTripShape() {
        let input: [String: Any] = ["value": "test", "count": 3]
        let converted = CapgoWatchMessageConverter.convertFromWatchMessage(input)
        XCTAssertEqual(converted.count, 2)
        XCTAssertEqual(converted["value"] as? String, "test")
        XCTAssertEqual(converted["count"] as? Int, 3)
    }
}
