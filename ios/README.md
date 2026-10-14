# Sekuvo for iOS

**Status: early development.** The iOS app is being written natively in
Swift and SwiftUI, next to the Android app in this repository. It is not on
the App Store yet.

What works today: creating a vault with a master password, unlocking,
locking when the app leaves the screen, adding, editing and deleting all
four entry types, the entry list with search and type filter, the detail
screen with masked fields, and copying a value to the clipboard for this
iPhone only, cleared after 45 seconds. Every screen is translated into the
same sixteen languages as the Android app.

## Layout

| Path | What |
|---|---|
| `SekuvoCore/` | Swift package: crypto, envelope and backup format, entry model, QR frames, password generator, field formats. Platform-independent, tested on macOS. |
| `Sekuvo/` | The SwiftUI app. |
| `Sekuvo.xcodeproj` | Xcode project (Xcode 26+, iOS 18+). |
| `araclar/ceviri_aktar.py` | Builds `Sekuvo/Localizable.xcstrings` from the Android app's `strings.xml` in all sixteen languages. |
| `Ceviriler/yalniz-ios.json` | The few strings that exist only on iOS. |

## Same format as Android

A backup made on one platform restores on the other, and a QR transfer
from `aktar.html` opens on both. The rules live in [`../bicim/`](../bicim/README.md);
`SekuvoCore`'s tests and the Android unit tests read the same test values.

## Build and test

```bash
cd ios/SekuvoCore && swift test
```

```bash
xcodebuild -project ios/Sekuvo.xcodeproj -scheme Sekuvo -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build CODE_SIGNING_ALLOWED=NO
```

After changing a string in the Android app:

```bash
python3 ios/araclar/ceviri_aktar.py
```

## What iOS cannot do

- **Typing into a computer over Bluetooth.** iOS does not let apps act as a
  Bluetooth keyboard.
- **Proving there is no network.** Android shows the absence of the
  `INTERNET` permission in the manifest; iOS has no such permission to
  leave out. The iOS app makes no network requests, but on iOS that is a
  promise you can check in the source, not a fact the system enforces.
- **Blocking screenshots.** There is no `FLAG_SECURE` on iOS. The app hides
  its content in the app switcher and locks when it leaves the screen.
