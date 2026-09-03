# Sekuvo 🔐

*Your secure vault.*

Sekuvo is a fully offline password, card and secret vault for Android.
It does not request the `INTERNET` permission — your data is technically
incapable of leaving the device.

*English — [Türkçe](README.tr.md) · [sekuvo.com](https://sekuvo.com)*

## Highlights

- **Entry types:** Account/Password, Everyday (name, phone, email, address),
  Card (number, expiry, CVV, IBAN), Secure Note — plus unlimited custom
  fields, all encrypted.
- **Strong crypto:** every sensitive field is AES-256-GCM encrypted; the key
  is derived from your master password with PBKDF2-HMAC-SHA256 (310,000
  rounds). No account, no cloud, no backdoor.
- **Clipboard-free channels:** an Android autofill service, the Sekuvo
  Keyboard (types secrets straight into any app), and a Bluetooth HID mode
  that types a secret directly into a nearby computer — no software installed
  on the computer, works on Windows/macOS/Linux.
- **Screen-off locking:** the vault locks the moment the screen it lives on
  goes dark — including folding a foldable. The app, keyboard and autofill
  share one session.
- **Quick access, opt-in per entry:** entries you explicitly mark are readable
  from the keyboard while the vault is locked, protected by the phone's
  screen lock; everything else stays cryptographically sealed.
- **Encrypted, device-independent backups:** a single `.vaultbak` file saved
  wherever you choose. File + backup password restores everything on any
  phone — verified end to end. QR-based import from a computer is built in.
- **Usage log:** which field of which entry went where and when (clipboard,
  computer, app) — encrypted, values never logged.
- **Hardening:** clipboard auto-clear (45 s), `FLAG_SECURE` against
  screenshots and app-switcher previews, system backup disabled by design,
  PIN brute-force throttling.

## Security model

```
Master password ──PBKDF2(310k)──▶ KEK ──AES-GCM wrap──▶ dataKey (random 256-bit)
                                                            │
                                                            ▼
                                          All entries encrypted with AES-256-GCM
```

- `dataKey` lives in memory only while unlocked and is wiped the moment the
  screen goes off.
- Biometric unlock is a convenience layer: `dataKey` is additionally wrapped
  by a hardware-backed Android Keystore key. The master password always works.
- The backup file is self-contained: salt and PBKDF2 parameters live in its
  header; a wrong password fails GCM authentication.
- **If you forget the master password or the backup password, the data is
  unrecoverable.** That is the design, not a bug.

Details (quick-access second copies, threat notes): see the
[Turkish document](README.tr.md#güvenlik-modeli).

## Building

```
./gradlew assembleDebug        # debug APK
./gradlew testDebugUnitTest    # JVM unit tests
```

- Android Studio (its bundled JBR/JDK 21) builds and runs the project as-is.
- Command line needs JDK 17–21 (Kotlin's embedded compiler cannot parse
  JDK 25's version string). Point `JAVA_HOME` at a suitable JDK, e.g. the
  Android Studio JBR.
- `local.properties` (git-ignored) must contain your `sdk.dir`. Release
  signing reads `RELEASE_*` keys from the same file and is skipped when they
  are absent — CI builds stay unsigned instead of breaking.

Before committing UI-adjacent changes, run the on-device smoke test described
in [README.tr.md](README.tr.md#geliştirme-notları) — a class of
`androidx.fragment` bugs only appears when system dialogs open on a device.

## Tools

`tools/` contains the computer-side companions, verified against the app's
own crypto path:

- `vault-clip.py` — builds an encrypted transfer envelope from the Mac
  clipboard or a file, and renders it as QR codes in the terminal.
- `aktar.html` — the same, as a single self-contained page you download and
  open **locally**; it performs all crypto in the browser and never talks to
  a server.

## Privacy

Sekuvo collects nothing, shares nothing, and has no network access.
Full policy: https://sekuvo.com/privacy/

## License

[GPLv3](LICENSE) — derivatives must remain open under the same license.

Contributions need a DCO sign-off and a relicensing grant — [CONTRIBUTING.md](CONTRIBUTING.md)
explains what that is and why a single-maintainer GPL project asks for it.
