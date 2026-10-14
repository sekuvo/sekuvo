import Testing
@testable import SekuvoCore

/// Düzenleme ekranının kaydetme kuralları — Android'deki `EditScreen.kaydet`.
struct DuzenlemeTests {

    @Test func kaydederkenParolaVeNotDisindakiAlanlarKirpilir() {
        var d = EntryData()
        d.username = "  deniz@example.com \n"; d.url = " github.com "; d.cardholder = " DENIZ "
        d.cardNumber = " 4242 "; d.expiry = " 0829 "; d.cvv = " 123 "; d.iban = " TR00 "
        d.fullName = " Deniz "; d.phone = " +90 "; d.email = "a@b.co "; d.address = "\tKadıköy\n"
        d.password = " boşluklu parola "
        d.notes = "  girintili\n  not  "
        let k = d.trimmedForSave()
        #expect(k.username == "deniz@example.com" && k.url == "github.com" && k.cardholder == "DENIZ")
        #expect(k.cardNumber == "4242" && k.expiry == "0829" && k.cvv == "123" && k.iban == "TR00")
        #expect(k.fullName == "Deniz" && k.phone == "+90" && k.email == "a@b.co" && k.address == "Kadıköy")
        #expect(k.password == " boşluklu parola ")
        #expect(k.notes == "  girintili\n  not  ")
    }

    @Test func ekAlanlarKirpilirAdsizOlanDuser() {
        var d = EntryData()
        d.custom = [CustomField(label: " SSID ", value: " EvAgi "),
                    CustomField(label: "   ", value: "düşer"),
                    CustomField(label: "PIN", value: " 1234 ", type: .password)]
        #expect(d.trimmedForSave().custom == [CustomField(label: "SSID", value: "EvAgi"),
                                              CustomField(label: "PIN", value: "1234", type: .password)])
    }
}
