import Foundation
import Testing
@testable import SekuvoCore

/// Android'deki `FieldFormatsTest`'in aynı durumları — iki platform aynı kuralı uygular.
struct FieldFormatsTests {

    @Test func kartNumarasiYalnizRakamTutarVe19HanedeDurur() {
        #expect(FieldFormats.cardNumberInput("4111 1111-1111/1111") == "4111111111111111")
        #expect(FieldFormats.cardNumberInput("12345678901234567890123") == "1234567890123456789")
        #expect(FieldFormats.cardNumberInput("abc-def") == "")
    }

    @Test func sonKullanmaAAYYOlarakSinirlanirVeBoluIleGosterilir() {
        #expect(FieldFormats.expiryInput("12/29") == "1229")
        #expect(FieldFormats.expiryInput("122934") == "1229")
        #expect(FieldFormats.expiryDisplay("1229") == "12/29")
        #expect(FieldFormats.expiryDisplay("1") == "1")
        #expect(FieldFormats.expiryDisplay("123") == "12/3")
    }

    @Test func gecersizAyReddedilirBosVeGecmisTarihKabulEdilir() {
        #expect(FieldFormats.expiryIsValid(""))
        #expect(FieldFormats.expiryIsValid("0130"))
        #expect(FieldFormats.expiryIsValid("1220"))
        #expect(!FieldFormats.expiryIsValid("1329"))
        #expect(!FieldFormats.expiryIsValid("0029"))
        #expect(!FieldFormats.expiryIsValid("129"))
    }

    @Test func cvvUzunluguAgaBagli() {
        #expect(FieldFormats.cvvInput("1234") == "123")
        #expect(FieldFormats.cvvInput("1a2") == "12")
        #expect(FieldFormats.cvvInput("12345", network: .amex) == "1234")
        #expect(FieldFormats.cvvInput("12345", network: .visa) == "123")
    }

    @Test func ibanBBANIcindeHarfKabulEder() {
        #expect(FieldFormats.ibanInput("GB29 NWBK 6016 1331 9268 19") == "GB29NWBK60161331926819")
        #expect(FieldFormats.ibanInput("NL91 ABNA 0417 1643 00") == "NL91ABNA0417164300")
        #expect(FieldFormats.ibanInput("TR33 0006 1005 1978 6457 8413 26") == "TR330006100519786457841326")
        #expect(FieldFormats.ibanInput("tr") == "TR")
        #expect(FieldFormats.ibanInput("TR1") == "TR1")
        #expect(FieldFormats.ibanInput("TR1a2") == "TR12")
        #expect(FieldFormats.ibanInput("12") == "")
        #expect(FieldFormats.ibanInput("TR12" + String(repeating: "1", count: 40)).count == 34)
    }

    @Test func mod97DogruIbaniGecirirYanlisiYakalar() {
        #expect(FieldFormats.ibanIsValid("GB29 NWBK 6016 1331 9268 19"))
        #expect(FieldFormats.ibanIsValid("NL91 ABNA 0417 1643 00"))
        #expect(FieldFormats.ibanIsValid("TR33 0006 1005 1978 6457 8413 26"))
        #expect(FieldFormats.ibanIsValid("DE89 3704 0044 0532 0130 00"))
        #expect(!FieldFormats.ibanIsValid("GB29 NWBK 6016 1331 9268 18"))
        #expect(!FieldFormats.ibanIsValid("TR33 0006 1005 1978 6457 8413 62"))
        #expect(FieldFormats.ibanIsValid(""))
        #expect(FieldFormats.ibanIsValid("TR33 0006"))
        #expect(!FieldFormats.ibanIsValid("TR" + String(repeating: "1", count: 40)))
    }

    @Test func epostaAtVeAlanAdiNoktasiArar() {
        #expect(FieldFormats.emailIsValid(""))
        #expect(FieldFormats.emailIsValid("demo@example.com"))
        #expect(FieldFormats.emailIsValid("a.b+c@alt.example.co.uk"))
        #expect(!FieldFormats.emailIsValid("demo.example.com"))
        #expect(!FieldFormats.emailIsValid("demo@example"))
        #expect(!FieldFormats.emailIsValid("demo@example."))
        #expect(!FieldFormats.emailIsValid("demo @example.com"))
        #expect(!FieldFormats.emailIsValid("a@b@c.com"))
    }

    @Test func agNumaraninIlkHanelerindenBulunur() {
        #expect(FieldFormats.cardNetwork("4111111111111111") == .visa)
        #expect(FieldFormats.cardNetwork("5500000000000004") == .mastercard)
        #expect(FieldFormats.cardNetwork("2221000000000009") == .mastercard)
        #expect(FieldFormats.cardNetwork("378282246310005") == .amex)
        #expect(FieldFormats.cardNetwork("9792000000000001") == .troy)
        #expect(FieldFormats.cardNetwork("6011000000000004") == .discover)
        #expect(FieldFormats.cardNetwork("36000000000008") == .diners)
        #expect(FieldFormats.cardNetwork("3530111333300000") == .jcb)
        #expect(FieldFormats.cardNetwork("") == .unknown)
        #expect(FieldFormats.cardNetwork("9999") == .unknown)
        #expect(FieldFormats.cardNetwork("4") == .visa)
        #expect(FieldFormats.cardNetwork("37") == .amex)
        #expect(FieldFormats.cardNetwork("55") == .mastercard)
    }

    @Test func gruplamaAginKendiDuzeniniKullanir() {
        #expect(FieldFormats.groupCard("378282246310005", network: .amex) == "3782 822463 10005")
        #expect(FieldFormats.groupCard("36000000000008", network: .diners) == "3600 000000 0008")
        #expect(FieldFormats.groupCard("4111111111111111", network: .visa) == "4111 1111 1111 1111")
        #expect(FieldFormats.group("4111111111111111") == "4111 1111 1111 1111")
    }
}

struct PasswordGeneratorTests {

    @Test func herSecilenSiniftanEnAzBirKarakterVeIstenenUzunluk() {
        var o = PasswordGenerator.Options()
        for uzunluk in [4, 12, 20, 64] {
            o.length = uzunluk
            for _ in 0..<50 {
                let p = PasswordGenerator.generate(o)
                #expect(p.count == uzunluk)
                #expect(p.contains { $0.isLowercase })
                #expect(p.contains { $0.isUppercase })
                #expect(p.contains { $0.isNumber })
                #expect(p.contains { PasswordGenerator.symbols.contains($0) })
                #expect(!p.contains { PasswordGenerator.ambiguous.contains($0) })
            }
        }
    }

    @Test func sinifSecilmezseKucukHarfeDuserUzunlukSinirlanir() {
        var o = PasswordGenerator.Options()
        o.lower = false; o.upper = false; o.digits = false; o.symbols = false
        o.length = 2
        let p = PasswordGenerator.generate(o)
        #expect(p.count == 4)
        #expect(p.allSatisfy { $0.isLowercase })
        o.length = 500
        #expect(PasswordGenerator.generate(o).count == 128)
    }

    @Test func entropiAndroidIleAyniHesap() {
        // Varsayılan: 4 sınıf, karışanlar elenmiş → 24+24+8+24 = 80 karakter, 20 hane.
        #expect(PasswordGenerator.entropyBits(PasswordGenerator.Options()) == Int(20 * log2(80.0)))
    }
}
