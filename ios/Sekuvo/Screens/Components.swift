import SekuvoCore
import SwiftUI

/// Yumuşak renkli kapsül içinde tür ikonu (Android: `TypeBadge`).
struct TypeBadge: View {
    let type: EntryType
    var kind: NoteKind = .genel
    var size: CGFloat = 42

    var body: some View {
        Image(systemName: type == .note ? kind.symbol : type.symbol)
            .font(.system(size: size * 0.45, weight: .semibold))
            .foregroundStyle(type.color)
            .frame(width: size, height: size)
            .background(type.color.opacity(0.16), in: RoundedRectangle(cornerRadius: size / 3))
    }
}

/// Öncelik numarası rozeti (Android: `PriorityBadge`).
struct PriorityBadge: View {
    let number: Int

    var body: some View {
        Text(verbatim: "\(number)")
            .font(.caption2.weight(.medium))
            .foregroundStyle(Theme.onSecondaryContainer)
            .padding(.horizontal, 6).padding(.vertical, 1)
            .background(Theme.secondaryContainer, in: RoundedRectangle(cornerRadius: 6))
    }
}

/// Detay ekranındaki alan kartı (Android: `FieldCard`): etiket, göster/gizle,
/// kopyala, değer. Gizli alan (şifre, CVV, şifre türündeki ek alan) varsayılan
/// olarak maskelidir; uzunluğu belli etmemek için sabit 8 nokta.
struct FieldCard: View {
    let label: String
    let value: String
    var display: String? = nil
    var hidden: Bool = false
    var icon: String? = nil
    var onCopy: () -> Void

    @State private var gorunur = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                if let icon { Image(systemName: icon).font(.caption) }
                Text(label).font(.caption.weight(.medium)).tracking(0.4)
                Spacer()
                if hidden {
                    Button { gorunur.toggle() } label: { Image(systemName: gorunur ? "eye.slash" : "eye") }
                        .accessibilityLabel(L(gorunur ? "detail_hide" : "detail_show"))
                }
                Button(action: onCopy) { Image(systemName: "doc.on.doc") }
                    .accessibilityLabel(L("detail_copy"))
            }
            .foregroundStyle(Theme.primary)
            .buttonStyle(.borderless)
            .tint(Theme.onSurface)
            Text(hidden && !gorunur ? "••••••••" : (display ?? value))
                .font(.body.monospaced())
                .foregroundStyle(Theme.onSurface)
                .textSelection(.disabled)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 16).padding(.vertical, 12)
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Theme.outlineVariant, lineWidth: 1))
    }
}
