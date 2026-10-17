// swift-tools-version: 6.0
// SekuvoCore — iOS uygulamasının platformdan bağımsız çekirdeği: kripto,
// zarf/yedek biçimi, kayıt modeli, QR kareleri. Android'deki core/, data/ ve
// backup/ paketlerinin karşılığıdır; ikisi `bicim/vektorler/` değerleriyle
// aynı biçime kilitlenir (bkz. bicim/README.md). macOS'ta `swift test` ile sınanır.
import PackageDescription

let package = Package(
    name: "SekuvoCore",
    platforms: [.iOS(.v18), .macOS(.v14)],
    products: [
        .library(name: "SekuvoCore", targets: ["SekuvoCore"]),
    ],
    targets: [
        .target(name: "SekuvoCore"),
        .testTarget(name: "SekuvoCoreTests", dependencies: ["SekuvoCore"]),
    ]
)
