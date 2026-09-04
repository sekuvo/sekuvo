package com.afgover.vault.data

import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room 2→3 göçünün, GERÇEK bir v2 veritabanının üstünde veri kaybetmeden
 * koştuğunu doğrular (B-024/P-008.5, R-004). Şema JSON'ları hiç dışa
 * aktarılmadığı için MigrationTestHelper kullanılamıyor; v2 şeması ham SQL
 * ile, üretimde 1→2 göçünün bıraktığı hâliyle kurulur.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "migration-test.db"

    @Before
    fun temizle() {
        context.getDatabasePath(dbName).delete()
    }

    private fun v2VeritabaniKur(): ByteArray {
        val blob = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(dbName), null)
        db.execSQL(
            "CREATE TABLE entries (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "type TEXT NOT NULL, title TEXT NOT NULL, blob BLOB NOT NULL, " +
                "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, " +
                "quick INTEGER NOT NULL DEFAULT 0, quickBlob BLOB DEFAULT NULL)"
        )
        db.execSQL(
            "INSERT INTO entries (type, title, blob, createdAt, updatedAt, quick) " +
                "VALUES ('LOGIN', 'Eski kayıt', x'0102030405060708', 100, 200, 1)"
        )
        db.version = 2
        db.close()
        return blob
    }

    private fun v3VeritabaniKur(): ByteArray {
        val blob = byteArrayOf(9, 8, 7, 6)
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(dbName), null)
        db.execSQL(
            "CREATE TABLE entries (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "type TEXT NOT NULL, title TEXT NOT NULL, blob BLOB NOT NULL, " +
                "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, " +
                "quick INTEGER NOT NULL DEFAULT 0, quickBlob BLOB DEFAULT NULL, " +
                "tags TEXT NOT NULL DEFAULT '[]')"
        )
        db.execSQL(
            "CREATE TABLE tags (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "name TEXT NOT NULL, color INTEGER NOT NULL)"
        )
        db.execSQL(
            "INSERT INTO entries (type, title, blob, createdAt, updatedAt, quick, tags) " +
                "VALUES ('NOTE', 'v3 kaydı', x'09080706', 10, 20, 0, '[5]')"
        )
        db.version = 3
        db.close()
        return blob
    }

    @Test
    fun goc3ten4e_notTuruVeSiraKolonlariniVarsayilanlaEkler() {
        val beklenenBlob = v3VeritabaniKur()

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                    override fun onCreate(db: SupportSQLiteDatabase) =
                        error("v3 veritabanı vardı; onCreate çağrılmamalıydı")

                    override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) {
                        assertEquals(3, old)
                        VaultDatabase.MIGRATION_3_4.migrate(db)
                    }
                })
                .build()
        )

        helper.writableDatabase.use { db ->
            db.query("SELECT title, blob, tags, noteKind, sortIndex FROM entries").use { c ->
                assertEquals(1, c.count)
                c.moveToFirst()
                assertEquals("v3 kaydı", c.getString(0))
                assertArrayEquals(beklenenBlob, c.getBlob(1))
                assertEquals("[5]", c.getString(2))     // etiketler korunur
                assertEquals("GENEL", c.getString(3))   // varsayılan not türü
                assertEquals(0, c.getInt(4))            // varsayılan sıra
            }
        }
    }

    private fun v4VeritabaniKur(): ByteArray {
        val blob = byteArrayOf(4, 4, 4, 4)
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(dbName), null)
        db.execSQL(
            "CREATE TABLE entries (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "type TEXT NOT NULL, title TEXT NOT NULL, blob BLOB NOT NULL, " +
                "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, " +
                "quick INTEGER NOT NULL DEFAULT 0, quickBlob BLOB DEFAULT NULL, " +
                "tags TEXT NOT NULL DEFAULT '[]', " +
                "noteKind TEXT NOT NULL DEFAULT 'GENEL', " +
                "sortIndex INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE tags (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "name TEXT NOT NULL, color INTEGER NOT NULL)"
        )
        db.execSQL(
            "INSERT INTO entries (type, title, blob, createdAt, updatedAt, quick, tags) " +
                "VALUES ('LOGIN', 'v4 kaydı', x'04040404', 1, 2, 0, '[]')"
        )
        db.version = 4
        db.close()
        return blob
    }

    /** Günlük tabloları eklemeli gelir; mevcut kayıt hiç değişmez (R-004). */
    @Test
    fun goc4ten5e_gunlukTablolariniEklerVeKayitlaraDokunmaz() {
        val beklenenBlob = v4VeritabaniKur()

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                    override fun onCreate(db: SupportSQLiteDatabase) =
                        error("v4 veritabanı vardı; onCreate çağrılmamalıydı")

                    override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) {
                        assertEquals(4, old)
                        VaultDatabase.MIGRATION_4_5.migrate(db)
                    }
                })
                .build()
        )

        helper.writableDatabase.use { db ->
            db.query("SELECT title, blob FROM entries").use { c ->
                assertEquals(1, c.count)
                c.moveToFirst()
                assertEquals("v4 kaydı", c.getString(0))
                assertArrayEquals(beklenenBlob, c.getBlob(1))
            }
            db.execSQL("INSERT INTO usage_log (blob) VALUES (x'AABB')")
            db.execSQL("INSERT INTO usage_buffer (blob) VALUES (x'CCDD')")
            db.query("SELECT COUNT(*) FROM usage_log").use { c ->
                c.moveToFirst(); assertEquals(1, c.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM usage_buffer").use { c ->
                c.moveToFirst(); assertEquals(1, c.getInt(0))
            }
        }
    }

    @Test
    fun goc2den3e_veriKaybetmedenTagsKolonuEkler() {
        val beklenenBlob = v2VeritabaniKur()

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: SupportSQLiteDatabase) =
                        error("v2 veritabanı vardı; onCreate çağrılmamalıydı")

                    override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) {
                        assertEquals(2, old)
                        VaultDatabase.MIGRATION_2_3.migrate(db)
                    }
                })
                .build()
        )

        helper.writableDatabase.use { db ->
            // Eski satır aynen duruyor ve yeni sütun varsayılanıyla geldi
            db.query("SELECT type, title, blob, quick, tags FROM entries").use { c ->
                assertEquals(1, c.count)
                c.moveToFirst()
                assertEquals("LOGIN", c.getString(0))
                assertEquals("Eski kayıt", c.getString(1))
                assertArrayEquals(beklenenBlob, c.getBlob(2))
                assertEquals(1, c.getInt(3))
                assertEquals("[]", c.getString(4))
            }
            // tags tablosu kullanılabilir durumda
            db.execSQL("INSERT INTO tags (name, color) VALUES ('finans', -1)")
            db.query("SELECT name FROM tags").use { c ->
                assertEquals(1, c.count)
            }
        }
    }

    /**
     * Sürüm 6 veritabanı — üretimde 5→6 göçünün bıraktığı hâl. Tablo tanımları
     * eski sürümün APK'sinden birebir okundu, elle yazılmadı: göçün sınandığı
     * şema, kullanıcının telefonunda gerçekten duran şema olsun diye.
     */
    private fun v6VeritabaniKur() {
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(dbName), null)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `entries` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`type` TEXT NOT NULL, `title` TEXT NOT NULL, `blob` BLOB NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `quick` INTEGER NOT NULL, " +
                "`quickBlob` BLOB, `tags` TEXT NOT NULL, `noteKind` TEXT NOT NULL, " +
                "`sortIndex` INTEGER NOT NULL, `anchor` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `tags` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `color` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `usage_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`blob` BLOB NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `usage_buffer` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`blob` BLOB NOT NULL)"
        )
        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        // Kimlik karması yükseltme yolunda karşılaştırılmaz — Room göçten sonra
        // kendisi yazar. Satırın VARLIĞI, veritabanının Room'a ait olduğunu
        // göstermeye yeter.
        db.execSQL(
            "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, ?)",
            arrayOf("ab5bbcac67b485d6d6fa24eeb18920e1")
        )
        db.execSQL("INSERT INTO tags (name, color) VALUES ('finans', -1710619)")
        db.execSQL(
            "INSERT INTO entries (type, title, blob, createdAt, updatedAt, quick, tags, " +
                "noteKind, sortIndex, anchor) " +
                "VALUES ('LOGIN', 'v6 kaydı', x'0A0B0C', 1, 2, 0, '[1]', 'GENEL', 7, 0)"
        )
        db.version = 6
        db.close()
    }

    /**
     * 6→7 (etiket ikonu) — bu göç ROOM ÜZERİNDEN açılarak sınanır, ham
     * yardımcıyla değil.
     *
     * Sebep: bu göçteki asıl risk `ALTER TABLE` değil, Room'un göçten sonra
     * yaptığı şema doğrulaması. Sütunu eklemeyi unutmak ya da tipini kaydırmak
     * "Room cannot verify the data integrity" ile uygulamayı AÇILIŞTA
     * öldürür — ve bu, yalnız var olan bir v6 veritabanı Room'la açılırken
     * ortaya çıkar. Ham yardımcı o doğrulamayı hiç çalıştırmaz, yani yeşil
     * kalıp kullanıcının kasasını kilitleyebilirdi.
     */
    @Test
    fun goc6dan7ye_ikonKolonunuEklerVeRoomSemayiDogrular() {
        v6VeritabaniKur()

        val room = androidx.room.Room
            .databaseBuilder(context, VaultDatabase::class.java, dbName)
            .addMigrations(VaultDatabase.MIGRATION_6_7)
            .build()

        try {
            val etiketler = kotlinx.coroutines.runBlocking { room.tagDao().getAll() }
            assertEquals(1, etiketler.size)
            assertEquals("finans", etiketler[0].name)
            assertEquals(-1710619, etiketler[0].color)
            // Yeni sütun boş doğar: mevcut etiketler kendilerine ikon uydurmaz.
            assertEquals("", etiketler[0].icon)

            // Kayıt tablosuna hiç dokunulmadı.
            val kayitlar = kotlinx.coroutines.runBlocking { room.entryDao().getAll() }
            assertEquals(1, kayitlar.size)
            assertEquals("v6 kaydı", kayitlar[0].title)
            assertEquals(7, kayitlar[0].sortIndex)

            // Sütun gerçekten yazılabilir; salt okunur bir yama değil.
            kotlinx.coroutines.runBlocking {
                room.tagDao().update(etiketler[0].copy(icon = "bank"))
                assertEquals("bank", room.tagDao().getAll()[0].icon)
            }
            assertEquals(7, room.openHelper.readableDatabase.version)
        } finally {
            room.close()
        }
    }
}
