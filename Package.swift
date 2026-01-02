// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "CapgoCapacitorWatch",
    platforms: [.iOS(.v15)],
    products: [
        .library(
            name: "CapgoCapacitorWatch",
            targets: ["CapgoWatchPlugin"])
    ],
    dependencies: [
        .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", from: "8.0.0")
    ],
    targets: [
        .target(
            name: "CapgoWatchPlugin",
            dependencies: [
                .product(name: "Capacitor", package: "capacitor-swift-pm"),
                .product(name: "Cordova", package: "capacitor-swift-pm")
            ],
            path: "ios/Sources/CapgoWatchPlugin"),
        .testTarget(
            name: "CapgoWatchPluginTests",
            dependencies: ["CapgoWatchPlugin"],
            path: "ios/Tests/CapgoWatchPluginTests")
    ]
)
