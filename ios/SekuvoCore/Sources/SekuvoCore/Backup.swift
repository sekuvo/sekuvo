import Foundation

/// Şifreli yedek dosyası (`.vaultbak`) ve aktarım zarfı.
///
/// Android'deki `backup/BackupManager.kt`'nin karşılığı. Biçim
/// (`bicim/README.md` §3):
///
/// ```
/// { "app": "vault", "version": 1,
///   "kdf": {"algo": "PBKDF2WithHmacSHA256", "iterations": N, "salt": b64},
///   "cipher": "AES-256-GCM",
///   "data": b64( iv || ciphertext+tag ) }   // içerik: {"entries":[…], "tagDefs":[…]}
/// ```
///
/// Dosya, paroladan PBKDF2 ile türetilen bağımsız bir anahtarla şifrelenir;
/// cihaza, Keychain'e ya da uygulama verisine bağlı değildir. Android'in
/// yazdığı yedek burada, buranın yazdığı Android'de açılır.
public enum Backup {

    /// Hatalar TEKNİKTİR; arayüz tipe bakıp kendi dilinde mesaj kurar.
    public enum Failure: Error, Equatable {
        case wrongPassword
        case invalidFormat
    }

    /// Girdi tavanı: yanlış seçilen dev bir dosya belleği doldurmasın.
    public static let maxEnvelopeBytes = 16 * 1024 * 1024
    /// Düşman bir zarf tur sayısını şişirip türetmeyi kilitleyemesin.
    public static let minIterations = 10_000
    public static let maxIterations = 1_000_000

    public struct ImportResult: Equatable, Sendable {
        public var entries: [DecryptedEntry]
        /// Yedekteki etiket tanımları: ad → renk + ikon.
        public var tagDefs: [String: TagDef]
    }

    /// Etiket adlarıyla birlikte dışa aktarılacak etiket (id cihaza özgüdür).
    public struct Tag: Equatable, Sendable {
        public var id: Int64
        public var name: String
        public var color: Int32
        public var icon: String

        public init(id: Int64, name: String, color: Int32, icon: String = "") {
            self.id = id; self.name = name; self.color = color; self.icon = icon
        }
    }

    // MARK: Dışa aktarma

    public static func export(password: String, entries: [DecryptedEntry], tags: [Tag] = []) throws -> Data {
        let ad = Dictionary(tags.map { ($0.id, $0.name) }, uniquingKeysWith: { a, _ in a })
        var kayitlar: [[String: Any]] = []
        for e in entries {
            var o: [String: Any] = [
                "type": e.type.rawValue, "title": e.title,
                "createdAt": e.createdAt, "updatedAt": e.updatedAt,
                "quick": e.quick, "sortIndex": e.sortIndex,
                "data": e.data.json(),
            ]
            // Araç çıpası yedeğe kendini tanıtmalı; yoksa geri yüklemede normal not olur.
            if e.anchor { o["anchor"] = true }
            if e.noteKind != .genel { o["noteKind"] = e.noteKind.rawValue }
            let adlar = e.tagIds.compactMap { ad[$0] }
            if !adlar.isEmpty { o["tags"] = adlar }
            kayitlar.append(o)
        }
        var icerik: [String: Any] = ["entries": kayitlar]
        let kullanilan = Set(entries.flatMap(\.tagIds))
        let tanimlar = tags.filter { kullanilan.contains($0.id) }.map { t -> [String: Any] in
            var d: [String: Any] = ["name": t.name, "color": t.color]
            if !t.icon.isEmpty { d["icon"] = t.icon }
            return d
        }
        if !tanimlar.isEmpty { icerik["tagDefs"] = tanimlar }
        return try seal(payload: JSONSerialization.data(withJSONObject: icerik), password: password)
    }

    /// İçeriği zarfa koyar: yeni tuz, 310.000 tur, rastgele IV.
    public static func seal(payload: Data, password: String) throws -> Data {
        let tuz = Crypto.randomBytes(16)
        let anahtar = Crypto.deriveKey(password: password, salt: tuz)
        let blob = try Crypto.encrypt(payload, key: anahtar)
        let zarf: [String: Any] = [
            "app": "vault",
            "version": 1,
            "kdf": ["algo": "PBKDF2WithHmacSHA256", "iterations": Crypto.kdfIterations,
                    "salt": tuz.base64EncodedString()] as [String: Any],
            "cipher": "AES-256-GCM",
            "data": blob.base64EncodedString(),
        ]
        return try JSONSerialization.data(withJSONObject: zarf, options: [.prettyPrinted, .sortedKeys])
    }

    // MARK: İçe aktarma

    /// Panodan ya da tarayıcıdan gelen metni ayrıştırılabilir hâle getirir:
    /// BOM ve görünmez karakterler atılır, zarf ilk `{` ile son `}` arasından
    /// çıkarılır. İçerik zaten GCM ile doğrulandığı için bu gevşeklik güvenliği
    /// etkilemez, yalnız kullanıcıyı gereksiz hatadan kurtarır.
    public static func normalize(_ raw: String) -> String {
        let atilan: Set<Character> = ["\u{FEFF}", "\u{200B}", "\u{200E}"]
        let temiz = String(raw.filter { !atilan.contains($0) }).trimmingCharacters(in: .whitespacesAndNewlines)
        guard let bas = temiz.firstIndex(of: "{"), let son = temiz.lastIndex(of: "}"), bas < son else { return temiz }
        return String(temiz[bas...son])
    }

    /// Zarfı açar ve içeriği (çözülmüş JSON baytları) döndürür.
    public static func open(envelope: Data, password: String) throws -> Data {
        guard envelope.count <= maxEnvelopeBytes else { throw Failure.invalidFormat }
        guard let metin = String(data: envelope, encoding: .utf8) else { throw Failure.invalidFormat }
        let normal = normalize(metin)
        guard let zarf = (try? JSONSerialization.jsonObject(with: Data(normal.utf8))) as? [String: Any],
              (zarf["app"] as? String) == "vault",
              let kdf = zarf["kdf"] as? [String: Any],
              let tuz = base64(JSON.string(kdf, "salt"))
        else { throw Failure.invalidFormat }
        let tur = JSON.int(kdf, "iterations", Crypto.kdfIterations)
        guard (minIterations...maxIterations).contains(tur),
              let blob = base64(JSON.string(zarf, "data"))
        else { throw Failure.invalidFormat }
        let anahtar = Crypto.deriveKey(password: password, salt: tuz, iterations: tur)
        guard let acik = Crypto.decrypt(blob, key: anahtar) else { throw Failure.wrongPassword }
        return acik
    }

    public static func `import`(_ envelope: Data, password: String) throws -> ImportResult {
        let acik = try open(envelope: envelope, password: password)
        guard let icerik = (try? JSONSerialization.jsonObject(with: acik)) as? [String: Any],
              let dizi = icerik["entries"] as? [Any]
        else { throw Failure.invalidFormat }
        let simdi = Int64(Date().timeIntervalSince1970 * 1000)
        let kayitlar = dizi.compactMap { $0 as? [String: Any] }.map { o in
            DecryptedEntry(
                type: .of(o["type"] as? String),
                title: JSON.string(o, "title"),
                data: EntryData(json: o["data"] as? [String: Any] ?? [:]),
                createdAt: JSON.long(o, "createdAt", simdi),
                updatedAt: JSON.long(o, "updatedAt", simdi),
                // Eski yedeklerde alan yok: korumalı kabul edilir.
                quick: JSON.bool(o, "quick", false),
                tagNames: (o["tags"] as? [Any])?.compactMap { $0 as? String } ?? [],
                noteKind: .of(o["noteKind"] as? String),
                sortIndex: JSON.int(o, "sortIndex", 0),
                anchor: JSON.bool(o, "anchor", false)
            )
        }
        var tanimlar: [String: TagDef] = [:]
        for d in JSON.objects(icerik["tagDefs"]) {
            let ad = JSON.string(d, "name")
            guard !ad.isEmpty, d["color"] != nil else { continue }
            tanimlar[ad] = TagDef(color: Int32(truncatingIfNeeded: JSON.long(d, "color")),
                                  icon: TagIcons.normalize(d["icon"] as? String))
        }
        return ImportResult(entries: kayitlar, tagDefs: tanimlar)
    }

    /// Standart base64; dolgusuz girdi de kabul edilir (Java'nın çözücüsü gibi).
    static func base64(_ s: String) -> Data? {
        guard !s.isEmpty else { return nil }
        let eksik = (4 - s.count % 4) % 4
        return Data(base64Encoded: s + String(repeating: "=", count: eksik))
    }
}
