// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "WasloraIOSSources",
    platforms: [.iOS(.v16)],
    products: [.library(name: "WasloraIOSSources", targets: ["WasloraIOSSources"])],
    targets: [
        .target(name: "WasloraIOSSources", path: "Waslora", exclude: ["WasloraApp.swift", "Views.swift", "Info.plist", "Assets.xcassets"])
    ]
)
