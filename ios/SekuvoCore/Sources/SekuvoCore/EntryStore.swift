import Foundation
import SQLite3

/// Kayıtların diskteki yeri: SQLite, üçüncü taraf kütüphane olmadan.
///
/// Şema Android'deki Room tablolarının (`entries`, `tags`) karşılığı. Başlık ve
/// bütün hassas alanlar `blob` içinde `dataKey` ile AES-256-GCM'lidir, etiket
/// adı `nameBlob`'da (biçim sürüm 2, `KayitBicimi`); `uid` boş satır sürüm
/// 1'dendir ve başlığı açık durur. Dosya ayrıca iOS veri korumasıyla
/// (`complete`) şifrelidir.
final class EntryStore {

    struct Row: Equatable {
        var id: Int64 = 0
        var type: String
        var title: String
        var blob: Data
        var createdAt: Int64
        var updatedAt: Int64
        var quick: Bool = false
        /// Etiket id'leri, JSON dizi ("[1,3]") — Android'deki `TagIds` biçimi.
        var tags: String = "[]"
        var noteKind: String = "GENEL"
        var sortIndex: Int = 0
        var anchor: Bool = false
        /// Satırın kalıcı, rastgele kimliği (sürüm 2); boş = sürüm 1.
        var uid: String = ""
    }

    struct TagRow: Equatable {
        var id: Int64
        var name: String
        var color: Int32
        var icon: String
        /// Ad mühürünün bağlı olduğu kimlik (sürüm 2); boş = sürüm 1, ad açık.
        var uid: String = ""
        /// Şifreli ad (sürüm 2).
        var nameBlob: Data? = nil
    }

    enum Failure: Error { case open(String), sql(String) }

    private var db: OpaquePointer?
    private static let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

    init(file: URL) throws {
        try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        let bayrak = SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX
        guard sqlite3_open_v2(file.path, &db, bayrak, nil) == SQLITE_OK else {
            throw Failure.open(String(cString: sqlite3_errmsg(db)))
        }
        try exec("""
            CREATE TABLE IF NOT EXISTS entries (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              type TEXT NOT NULL,
              title TEXT NOT NULL,
              blob BLOB NOT NULL,
              createdAt INTEGER NOT NULL,
              updatedAt INTEGER NOT NULL,
              quick INTEGER NOT NULL DEFAULT 0,
              quickBlob BLOB,
              tags TEXT NOT NULL DEFAULT '[]',
              noteKind TEXT NOT NULL DEFAULT 'GENEL',
              sortIndex INTEGER NOT NULL DEFAULT 0,
              anchor INTEGER NOT NULL DEFAULT 0,
              uid TEXT NOT NULL DEFAULT ''
            );
            CREATE TABLE IF NOT EXISTS tags (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              name TEXT NOT NULL,
              color INTEGER NOT NULL,
              icon TEXT NOT NULL DEFAULT '',
              uid TEXT NOT NULL DEFAULT '',
              nameBlob BLOB
            );
            """)
        // 1.3'ten önce açılmış dosyada biçim sürüm 2'nin sütunları yok: eklenir,
        // var olan satırlar `uid = ''` ile sürüm 1 sayılır (veri değişmez).
        if try !sutunlar("entries").contains("uid") {
            try exec("ALTER TABLE entries ADD COLUMN uid TEXT NOT NULL DEFAULT ''")
        }
        let etiketSutunlari = try sutunlar("tags")
        if !etiketSutunlari.contains("uid") { try exec("ALTER TABLE tags ADD COLUMN uid TEXT NOT NULL DEFAULT ''") }
        if !etiketSutunlari.contains("nameBlob") { try exec("ALTER TABLE tags ADD COLUMN nameBlob BLOB") }
        try? FileManager.default.setAttributes([.protectionKey: FileProtectionType.complete], ofItemAtPath: file.path)
    }

    deinit { sqlite3_close(db) }

    // MARK: Kayıtlar

    private static let satirSutunlari =
        "id, type, title, blob, createdAt, updatedAt, quick, tags, noteKind, sortIndex, anchor, uid"

    private static func satir(_ s: OpaquePointer?) -> Row {
        Row(id: sqlite3_column_int64(s, 0), type: metin(s, 1), title: metin(s, 2), blob: veri(s, 3),
            createdAt: sqlite3_column_int64(s, 4), updatedAt: sqlite3_column_int64(s, 5),
            quick: sqlite3_column_int(s, 6) != 0, tags: metin(s, 7), noteKind: metin(s, 8),
            sortIndex: Int(sqlite3_column_int(s, 9)), anchor: sqlite3_column_int(s, 10) != 0, uid: metin(s, 11))
    }

    func all() throws -> [Row] {
        try query("SELECT \(Self.satirSutunlari) FROM entries", { _ in }, Self.satir)
    }

    func get(id: Int64) throws -> Row? {
        try query("SELECT \(Self.satirSutunlari) FROM entries WHERE id = ?", { sqlite3_bind_int64($0, 1, id) }, Self.satir).first
    }

    @discardableResult
    func insert(_ r: Row) throws -> Int64 {
        try run("""
            INSERT INTO entries (type, title, blob, createdAt, updatedAt, quick, tags, noteKind, sortIndex, anchor, uid)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """) { s in bagla(s, r) }
        return sqlite3_last_insert_rowid(db)
    }

    func update(_ r: Row) throws {
        try run("""
            UPDATE entries SET type = ?, title = ?, blob = ?, createdAt = ?, updatedAt = ?, quick = ?,
                   tags = ?, noteKind = ?, sortIndex = ?, anchor = ?, uid = ? WHERE id = ?
            """) { s in
            bagla(s, r)
            sqlite3_bind_int64(s, 12, r.id)
        }
    }

    func delete(id: Int64) throws {
        try run("DELETE FROM entries WHERE id = ?") { s in sqlite3_bind_int64(s, 1, id) }
    }

    func deleteAllEntries() throws { try exec("DELETE FROM entries") }

    // MARK: Etiketler

    func allTags() throws -> [TagRow] {
        try query("SELECT id, name, color, icon, uid, nameBlob FROM tags ORDER BY id", { _ in }) { s in
            TagRow(id: sqlite3_column_int64(s, 0), name: metin(s, 1),
                   color: Int32(truncatingIfNeeded: sqlite3_column_int64(s, 2)), icon: metin(s, 3),
                   uid: metin(s, 4), nameBlob: sqlite3_column_type(s, 5) == SQLITE_NULL ? nil : veri(s, 5))
        }
    }

    /// Yeni etiket; `t.id` yok sayılır.
    @discardableResult
    func insertTag(_ t: TagRow) throws -> Int64 {
        try run("INSERT INTO tags (name, color, icon, uid, nameBlob) VALUES (?, ?, ?, ?, ?)") { s in
            Self.etiketBagla(s, t)
        }
        return sqlite3_last_insert_rowid(db)
    }

    func updateTag(_ t: TagRow) throws {
        try run("UPDATE tags SET name = ?, color = ?, icon = ?, uid = ?, nameBlob = ? WHERE id = ?") { s in
            Self.etiketBagla(s, t)
            sqlite3_bind_int64(s, 6, t.id)
        }
    }

    private static func etiketBagla(_ s: OpaquePointer?, _ t: TagRow) {
        sqlite3_bind_text(s, 1, t.name, -1, transient)
        sqlite3_bind_int64(s, 2, Int64(t.color))
        sqlite3_bind_text(s, 3, t.icon, -1, transient)
        sqlite3_bind_text(s, 4, t.uid, -1, transient)
        if let b = t.nameBlob {
            _ = b.withUnsafeBytes { sqlite3_bind_blob(s, 5, $0.baseAddress, Int32(b.count), transient) }
        } else {
            sqlite3_bind_null(s, 5)
        }
    }

    func deleteTag(id: Int64) throws {
        try run("DELETE FROM tags WHERE id = ?") { s in sqlite3_bind_int64(s, 1, id) }
    }

    /// Yalnız kaydın etiket listesini yazar; şifreli gövde ve tarihler aynen kalır.
    func setTags(entry id: Int64, tags: String) throws {
        try run("UPDATE entries SET tags = ? WHERE id = ?") { s in
            sqlite3_bind_text(s, 1, tags, -1, Self.transient)
            sqlite3_bind_int64(s, 2, id)
        }
    }

    // MARK: İşlem

    /// Gövde hata atarsa her şey geri alınır: yarıda kesilen bir "tümünü
    /// değiştir" kasayı boş bırakamaz.
    func transaction(_ govde: () throws -> Void) throws {
        try exec("BEGIN IMMEDIATE")
        do {
            try govde()
            try exec("COMMIT")
        } catch {
            try? exec("ROLLBACK")
            throw error
        }
    }

    /// Silinen açık metni dosyadan da çıkarır: dosya baştan yazılır. Bir işlemin
    /// içinde çağrılamaz.
    func vacuum() throws { try exec("VACUUM") }

    /// Tablonun sütun adları (`PRAGMA table_info`).
    private func sutunlar(_ tablo: String) throws -> Set<String> {
        Set(try query("PRAGMA table_info(\(tablo))", { _ in }) { s in metin(s, 1) })
    }

    // MARK: SQLite yardımcıları

    private func bagla(_ s: OpaquePointer?, _ r: Row) {
        sqlite3_bind_text(s, 1, r.type, -1, Self.transient)
        sqlite3_bind_text(s, 2, r.title, -1, Self.transient)
        _ = r.blob.withUnsafeBytes { sqlite3_bind_blob(s, 3, $0.baseAddress, Int32(r.blob.count), Self.transient) }
        sqlite3_bind_int64(s, 4, r.createdAt)
        sqlite3_bind_int64(s, 5, r.updatedAt)
        sqlite3_bind_int(s, 6, r.quick ? 1 : 0)
        sqlite3_bind_text(s, 7, r.tags, -1, Self.transient)
        sqlite3_bind_text(s, 8, r.noteKind, -1, Self.transient)
        sqlite3_bind_int(s, 9, Int32(r.sortIndex))
        sqlite3_bind_int(s, 10, r.anchor ? 1 : 0)
        sqlite3_bind_text(s, 11, r.uid, -1, Self.transient)
    }

    private func exec(_ sql: String) throws {
        var hata: UnsafeMutablePointer<CChar>?
        guard sqlite3_exec(db, sql, nil, nil, &hata) == SQLITE_OK else {
            defer { sqlite3_free(hata) }
            throw Failure.sql(hata.map { String(cString: $0) } ?? "?")
        }
    }

    private func run(_ sql: String, _ bind: (OpaquePointer?) -> Void) throws {
        var s: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &s, nil) == SQLITE_OK else { throw Failure.sql(String(cString: sqlite3_errmsg(db))) }
        defer { sqlite3_finalize(s) }
        bind(s)
        guard sqlite3_step(s) == SQLITE_DONE else { throw Failure.sql(String(cString: sqlite3_errmsg(db))) }
    }

    private func query<T>(_ sql: String, _ bind: (OpaquePointer?) -> Void, _ satir: (OpaquePointer?) -> T) throws -> [T] {
        var s: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &s, nil) == SQLITE_OK else { throw Failure.sql(String(cString: sqlite3_errmsg(db))) }
        defer { sqlite3_finalize(s) }
        bind(s)
        var sonuc: [T] = []
        while sqlite3_step(s) == SQLITE_ROW { sonuc.append(satir(s)) }
        return sonuc
    }
}

private func metin(_ s: OpaquePointer?, _ i: Int32) -> String {
    sqlite3_column_text(s, i).map { String(cString: $0) } ?? ""
}

private func veri(_ s: OpaquePointer?, _ i: Int32) -> Data {
    guard let p = sqlite3_column_blob(s, i) else { return Data() }
    return Data(bytes: p, count: Int(sqlite3_column_bytes(s, i)))
}

/// Android'deki `TagIds`: `[1,3]` biçimli etiket listesi.
enum TagIds {
    static func parse(_ json: String) -> [Int64] {
        guard let dizi = (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [Any] else { return [] }
        return dizi.compactMap { ($0 as? NSNumber)?.int64Value }
    }

    static func serialize(_ ids: [Int64]) -> String {
        var gorulen = Set<Int64>()
        return "[" + ids.filter { gorulen.insert($0).inserted }.map(String.init).joined(separator: ",") + "]"
    }
}
