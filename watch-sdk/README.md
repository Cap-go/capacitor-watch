# CapgoWatchSDK

Swift Package for watchOS apps to communicate with the `@capgo/capacitor-watch` plugin.

## Installation

Add this package to your watchOS app target in Xcode:

1. File > Add Package Dependencies
2. Enter: `https://github.com/Cap-go/capacitor-watch.git`
3. Select the `watch-sdk` directory
4. Add `CapgoWatchSDK` to your watchOS target

Or add to your `Package.swift`:

```swift
dependencies: [
    .package(url: "https://github.com/Cap-go/capacitor-watch.git", from: "8.0.0")
]
```

## Usage

### SwiftUI Example

```swift
import SwiftUI
import CapgoWatchSDK

@main
struct MyWatchApp: App {
    init() {
        WatchConnector.shared.activate()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

struct ContentView: View {
    @ObservedObject var connector = WatchConnector.shared

    var body: some View {
        VStack {
            Text(connector.isReachable ? "Phone Connected" : "Phone Disconnected")

            Button("Send Message") {
                connector.sendMessage(["action": "hello"]) { reply in
                    print("Reply: \(reply)")
                }
            }
            .disabled(!connector.isReachable)
        }
    }
}
```

### Using Delegate

```swift
class WatchManager: WatchConnectorDelegate {
    init() {
        WatchConnector.shared.delegate = self
        WatchConnector.shared.activate()
    }

    func didReceiveMessage(_ message: [String: Any]) {
        print("Received: \(message)")
    }

    func didReceiveMessage(_ message: [String: Any], replyHandler: @escaping ([String: Any]) -> Void) {
        // Process and reply
        replyHandler(["status": "ok"])
    }
}
```

### Async/Await

```swift
Task {
    do {
        let reply = try await WatchConnector.shared.sendMessage(["action": "getData"])
        print("Reply: \(reply)")
    } catch {
        print("Error: \(error)")
    }
}
```

## License

MPL-2.0
