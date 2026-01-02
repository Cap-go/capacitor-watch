// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "CapgoWatchSDK",
    platforms: [.iOS(.v15), .watchOS(.v9)],
    products: [
        .library(
            name: "CapgoWatchSDK",
            targets: ["CapgoWatchSDK"])
    ],
    dependencies: [],
    targets: [
        .target(
            name: "CapgoWatchSDK",
            dependencies: [],
            path: "Sources/CapgoWatchSDK")
    ]
)
