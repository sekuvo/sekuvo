import SekuvoCore
import SwiftUI
import UIKit

/// Sekuvo teması — Android'deki `ui/theme/Theme.kt` ile aynı renkler:
/// pirinç birincil, açık temada sıcak kâğıt üzerine beyaz içerik, koyu temada
/// sıcak kömür. Renk, kategorideki her rakibin mavisine karşı kasa donanımının
/// pirinci; iki platformda da aynı kimlik.
/// Henüz olmayan özelliklerin arayüzü: yalan vaat gösterilmez.
enum Features {
    /// Sekuvo Klavyesi uzantısı yok (karar bekliyor). Olmayan klavye için
    /// "klavyede parolasız kullan" anahtarı ve ⚡ işareti gizli; veri alanı
    /// (`quick`) korunur, yedekle gelir ve gider.
    static let keyboardExtension = false
}

enum Theme {
    static let primary = renk(0x856520, 0xF0C87A)
    static let onPrimary = renk(0xFFFFFF, 0x3F2E00)
    /// Dolu düğmelerin rengi: altın yüzey + koyu yazı.
    static let primaryContainer = renk(0xD9B45F, 0x6B5210)
    static let onPrimaryContainer = renk(0x241A00, 0xFFE7B0)
    static let secondaryContainer = renk(0xF2E3C6, 0x4F452E)
    static let onSecondaryContainer = renk(0x241A07, 0xF2E0BF)
    static let tertiary = renk(0x3F5A47, 0xB2CFB6)
    static let background = renk(0xFFFFFF, 0x211D18)
    static let surface = renk(0xFFFFFF, 0x302A23)
    static let surfaceContainer = renk(0xFFFFFF, 0x332D26)
    static let surfaceContainerHighest = renk(0xF5EFE4, 0x463E35)
    static let onSurface = renk(0x221D13, 0xEDE3D3)
    static let onSurfaceVariant = renk(0x4E4639, 0xD3C7B2)
    static let outline = renk(0x7F7767, 0x9B9282)
    static let outlineVariant = renk(0xD1C7B4, 0x4E463A)
    static let error = renk(0xBA1A1A, 0xFFB4AB)

    private static func renk(_ acik: UInt32, _ koyu: UInt32) -> Color {
        Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(rgb: koyu) : UIColor(rgb: acik) })
    }
}

extension UIColor {
    convenience init(rgb: UInt32, alpha: CGFloat = 1) {
        self.init(red: CGFloat((rgb >> 16) & 0xFF) / 255, green: CGFloat((rgb >> 8) & 0xFF) / 255,
                  blue: CGFloat(rgb & 0xFF) / 255, alpha: alpha)
    }
}

extension Color {
    /// Android'in işaretli ARGB etiket rengi.
    init(argb: Int32) {
        let u = UInt32(bitPattern: argb)
        self.init(UIColor(rgb: u & 0xFFFFFF, alpha: CGFloat((u >> 24) & 0xFF) / 255))
    }
}

// MARK: Tür kimliği — renk ve ikon (Android: EntryType.renk(), icon())

extension EntryType {
    var color: Color {
        switch self {
        case .login: Theme.primary
        case .everyday: Color(UIColor(rgb: 0x63C7B2))
        case .card: Theme.tertiary
        case .note: Color(UIColor(rgb: 0xB08FE0))
        }
    }

    /// SF Symbols karşılığı (Android Material: Key, Badge, CreditCard, Note).
    var symbol: String {
        switch self {
        case .login: "key.fill"
        case .everyday: "person.text.rectangle.fill"
        case .card: "creditcard.fill"
        case .note: "note.text"
        }
    }

    var label: String {
        switch self {
        case .login: L("type_login")
        case .everyday: L("type_everyday")
        case .card: L("type_card")
        case .note: L("type_note")
        }
    }
}

extension NoteKind {
    /// Android: Note, Terminal, VpnKey, Fingerprint, HealthAndSafety, DataObject.
    var symbol: String {
        switch self {
        case .genel: "note.text"
        case .betik: "apple.terminal.fill"
        case .anahtar: "key.horizontal.fill"
        case .parmakIzi: "touchid"
        case .kurtarma: "cross.case.fill"
        case .yapilandirma: "curlybraces"
        }
    }

    var label: String {
        switch self {
        case .genel: L("note_kind_general")
        case .betik: L("note_kind_script")
        case .anahtar: L("note_kind_key")
        case .parmakIzi: L("note_kind_fingerprint")
        case .kurtarma: L("note_kind_recovery")
        case .yapilandirma: L("note_kind_config")
        }
    }
}

extension CustomFieldType {
    var label: String {
        switch self {
        case .text: L("field_type_text")
        case .password: L("field_type_password")
        case .email: L("field_type_email")
        case .phone: L("field_type_phone")
        case .url: L("field_type_url")
        case .number: L("field_type_number")
        case .multiline: L("field_type_multiline")
        }
    }

    var symbol: String {
        switch self {
        case .text: "textformat"
        case .password: "key.fill"
        case .email: "envelope.fill"
        case .phone: "phone.fill"
        case .url: "globe"
        case .number: "number"
        case .multiline: "text.alignleft"
        }
    }
}

// MARK: Etiket ikonları ve sıralama adları

/// Etiket ikon anahtarının SF Symbols karşılığı (Android: `tagIcon`, Material
/// ikonları). Tanınmayan anahtar `nil` döner; o zaman renk noktası çizilir.
enum TagSymbol {
    static func of(_ key: String) -> String? {
        switch key {
        case "work": "briefcase.fill"
        case "home": "house.fill"
        case "person": "person.fill"
        case "group": "person.3.fill"
        case "bank": "building.columns.fill"
        case "card": "creditcard.fill"
        case "shopping": "cart.fill"
        case "mail": "envelope.fill"
        case "cloud": "cloud.fill"
        case "server": "server.rack"
        case "code": "chevron.left.forwardslash.chevron.right"
        case "key": "key.fill"
        case "shield": "shield.fill"
        case "wifi": "wifi"
        case "game": "gamecontroller.fill"
        case "media": "film.fill"
        case "music": "music.note"
        case "school": "graduationcap.fill"
        case "health": "cross.case.fill"
        case "travel": "airplane"
        default: nil
        }
    }
}

extension EntrySort {
    var label: String {
        switch self {
        case .titleAsc: L("sort_title_asc")
        case .titleDesc: L("sort_title_desc")
        case .updatedDesc: L("sort_updated_desc")
        case .createdDesc: L("sort_created_desc")
        case .type: L("sort_type")
        case .manual: L("sort_manual")
        }
    }
}
