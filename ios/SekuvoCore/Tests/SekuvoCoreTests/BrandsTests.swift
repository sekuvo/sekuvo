import Foundation
import Testing
@testable import SekuvoCore

/// Kurum logosu eşleşmesi: Android'deki BrandsTest ile aynı değerler.
struct BrandsTests {

    @Test func ortakEslesmeDegerleri() throws {
        let d = try OrtakBicimTests.oku("marka-eslesme.json")
        let durumlar = try #require(d["durumlar"] as? [[String: Any]])
        #expect(durumlar.count > 20)
        for x in durumlar {
            let bulunan = Brands.match(title: x["baslik"] as! String, url: x["adres"] as! String,
                                       choice: x["secim"] as! String, isCard: x["kart"] as! Bool,
                                       cardNumber: x["kartNo"] as! String)
            let beklenen = x["beklenen"] as? String
            #expect(bulunan?.id == beklenen, "\(x["ad"] as! String)")
        }
    }

    @Test func katalogTutarli() {
        let ids = Brands.catalog.map(\.id)
        #expect(Set(ids).count == ids.count)
        for b in Brands.catalog {
            #expect(b.hasLogo || (1...3).contains(b.badge.count), "\(b.id)")
        }
    }

    @Test func secimYedekteTasinir() {
        var e = EntryData(); e.brand = "hetzner"
        #expect(EntryData(json: e.json()).brand == "hetzner")
        #expect(EntryData().json()["brand"] == nil)
    }
}
