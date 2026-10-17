import Foundation

/// Metinler Android'in anahtarlarıyla çağrılır; katalog
/// (`Localizable.xcstrings`) `ios/araclar/ceviri_aktar.py` ile Android'in 16
/// dilli `strings.xml`'inden üretilir. Bir metin tek yerde çevrilir.
func L(_ anahtar: String) -> String {
    NSLocalizedString(anahtar, comment: "")
}

/// Sıralı belirteçli metin (`%1$@`, `%2$lld`).
func L(_ anahtar: String, _ degerler: CVarArg...) -> String {
    String(format: L(anahtar), locale: Locale.current, arguments: degerler)
}

/// Çoğullu metin: katalogdaki one/few/many/other biçimini sayıya göre seçer.
func Lp(_ anahtar: String, _ sayi: Int) -> String {
    String.localizedStringWithFormat(L(anahtar), sayi)
}
