# Sekuvo shared formats

This folder is the contract between the Android app (`app/`) and the iOS app
(`ios/`). Everything that leaves a device — a `.vaultbak` backup, a transfer
envelope from `aktar.html`, a QR frame — must open on both platforms, byte
for byte.

The contract is enforced, not described: both test suites read the same
values from `vektorler/`, and a third implementation (Python's
`cryptography`, in `uret.py`) produces them.

| File | What it pins |
|---|---|
| `vektorler/kdf.json` | key derivation |
| `vektorler/aesgcm.json` | sealing, including a tampered blob that must fail |
| `vektorler/zarf.json` | envelopes, their decrypted entries, wrong-password and invalid cases |
| `vektorler/qr.json` | QR frames: multi-frame, single-frame, plain, unparsable and inconsistent sets |

Tests: Android `app/src/test/.../backup/OrtakBicimTest.kt`
(`./gradlew testDebugUnitTest`), iOS `ios/SekuvoCore`
(`swift test`). Regenerate with `python3 bicim/uret.py`; salts and IVs are
fixed, so the output is identical on every run and any diff in git means the
format changed.

## 1. Key derivation

PBKDF2-HMAC-SHA256, 32-byte key, 16-byte random salt. The iteration count
travels with what it protects (the envelope's `kdf.iterations`, the
on-device wrap), so every file opens with the count it was written with:

| Writer | Iterations |
|---|---|
| the apps: backups, the master-password wrap (since 1.3) | **600,000** |
| the apps before 1.3; `aktar.html`; `tools/vault-clip.py` | 310,000 |
| the PIN wrap (§6): fixed, its count is not stored | 310,000 |

Readers accept 10,000–1,000,000 iterations and reject anything else (a
hostile envelope must not lock the device in a long derivation). A missing
`iterations` reads as 310,000, the count every writer used before 1.3.
Raising the writers' count never breaks a reader: an old app opens a new
backup, a new app opens an old one.

The apps warn, and do not refuse, when a backup password is weak: shorter
than 16 characters (Unicode code points) or made of a single kind of
character (lowercase, uppercase, a letter without case, digit, other). For
ASCII this is the rule `aktar.html` and `vault-clip.py` apply before
uploading to the relay.

The password enters as its **UTF-8 bytes**. Android's `PBEKeySpec` agrees for
ASCII; non-ASCII passwords are not yet measured on the Android runtime, which
is why `aktar.html` asks for an ASCII transfer password and why the vectors
carry ASCII passwords only.

## 2. Sealing

AES-256-GCM, 12-byte random IV, 128-bit tag, no associated data.

```
blob = iv (12 bytes) || ciphertext || tag (16 bytes)
```

A blob of 12 bytes or less, a wrong key and a modified byte all fail the same
way: no plaintext.

## 3. Envelope

Used by `.vaultbak` backups, `aktar.html`, `tools/vault-clip.py` and the QR
payload. UTF-8 JSON:

```json
{
  "app": "vault",
  "version": 1,
  "kdf": { "algo": "PBKDF2WithHmacSHA256", "iterations": 310000, "salt": "<base64, 16 bytes>" },
  "cipher": "AES-256-GCM",
  "data": "<base64 of blob>"
}
```

Readers:

- strip U+FEFF, U+200B and U+200E, trim, and keep the text from the first
  `{` to the last `}` (text pasted from a browser often carries invisible
  characters);
- refuse more than 16 MiB;
- require `"app": "vault"`; `version` and `cipher` are written but not
  checked;
- report two different failures: **wrong password** (the tag does not verify)
  and **invalid format** (anything else).

Base64 is the standard alphabet with padding; readers also accept it
without padding.

## 4. Payload

The decrypted `data` is a JSON object:

```json
{ "entries": [ … ], "tagDefs": [ … ] }
```

### Entry

| Key | Type | When missing |
|---|---|---|
| `type` | `LOGIN` · `EVERYDAY` · `CARD` · `NOTE` | — ; an unknown value reads as `NOTE` |
| `title` | string | `""` |
| `createdAt`, `updatedAt` | epoch milliseconds | the time of import |
| `quick` | bool — usable on the keyboard while the vault is locked | `false` |
| `sortIndex` | int — the user's priority number, 0 = none | `0` |
| `noteKind` | `GENEL` · `BETIK` · `ANAHTAR` · `PARMAK_IZI` · `KURTARMA` · `YAPILANDIRMA` | `GENEL`; unknown reads as `GENEL`; written only when not `GENEL` |
| `anchor` | bool — the tool anchor (SHA-256 of `aktar.html`), hidden from the list | `false`; written only when `true` |
| `tags` | array of tag **names** (ids are device-local) | `[]`; written only when non-empty |
| `data` | object, below | `{}` |

### `data`

String fields, each written only when non-empty and read as `""` when
missing: `username`, `password`, `url`, `cardholder`, `cardNumber`,
`expiry` (`MMYY`), `cvv`, `iban`, `notes`, `fullName`, `phone`, `email`,
`address`.

| Key | Shape |
|---|---|
| `custom` | `[{"label", "value", "type"?}]`; entries with an empty label are dropped; `type` is one of `text` · `password` · `email` · `phone` · `url` · `number` · `multiline`, omitted for `text`, unknown reads as `text` |
| `passwordChangedAt` | epoch ms, written only when > 0 |
| `brand` | institution logo chosen by the user: a catalog id from `markalar/katalog.json`, or `none` for no logo; omitted = automatic (matched from `url`, then `title`; rule in `markalar/README.md`). An id the reader does not know falls back to automatic. Written only when non-empty |
| `passwordHistory` | `[{"value", "changedAt"}]`, newest first; entries with an empty value are dropped. `changedAt` is when that password was set, its `passwordChangedAt` at the time; if that was unknown (0), the moment it was replaced. 0 means unknown |

### Tag definition

`{"name", "color", "icon"?}`. `color` is ARGB as a **signed** 32-bit integer
(`0xFF4FC3F7` → `-11549705`). `icon` is one of the twenty keys in
`TagIcons`; an unknown key reads as no icon. Only tags used by the exported
entries are written.

### Compatibility rule

Fields are only ever **added**. An old reader skips what it does not know; a
new reader fills what an old file lacks with the defaults above. The
envelope `version` stays 1.

## 5. QR frames

```
VLT1|i/n|F|data
```

- `i`, `n`: 1-based frame number and total, `1 ≤ i ≤ n ≤ 512`.
- `F`: `Z` = raw DEFLATE (RFC 1951, no zlib header) then base64; `P` =
  base64 of the UTF-8 text. Writers compress and fall back to `P` when
  compression does not shrink the text.
- `data`: this frame's slice of the base64 text, 1,200 characters per frame.

Readers collect frames in any order and assemble only a complete, consistent
set: every frame present, the same `n` and the same `F` everywhere.
Inflation stops at 4 MiB (zip bomb). The assembled text is an envelope (§3).

## 6. On-device keys (for reference, not exchanged)

A random 32-byte `dataKey` seals each entry's `data` JSON (§2). A `KEK`
derived from the master password (§1) seals the `dataKey`. Each platform
stores the wrapped key its own way, together with its salt and iteration
count. A wrap written with fewer iterations than today's (a vault created
before 1.3) is rewritten with a new salt and 600,000 iterations the next
time the vault is opened with the master password; biometric unlock never
sees the password, so it cannot do this. Biometric unlock and the optional
PIN wrap the same `dataKey` again; the PIN is always derived with 310,000
iterations, because neither the PIN wrap nor its check blob records a count.
The master password always opens the vault on its own, so a backup needs
nothing but the file and its password.

## Changing the format

1. Change `uret.py` and this file together.
2. Regenerate: `python3 bicim/uret.py`.
3. Both test suites must pass, and an old file must still open: add fields,
   never repurpose one.
