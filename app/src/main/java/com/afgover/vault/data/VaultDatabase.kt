package com.afgover.vault.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        EntryEntity::class,
        TagEntity::class,
        UsageLogEntity::class,
        UsageBufferEntity::class
    ],
    version = 7,
    exportSchema = false
)
abstract class VaultDatabase : RoomDatabase() {

    abstract fun entryDao(): EntryDao

    abstract fun tagDao(): TagDao

    abstract fun usageLogDao(): UsageLogDao

    companion object {
        @Volatile
        private var instance: VaultDatabase? = null

        /**
         * Hızlı erişim alanları. Mevcut kayıtlar `quick = 0` ile gelir, yani
         * varsayılan olarak korumalıdır; içerikleri olduğu gibi kalır.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE entries ADD COLUMN quick INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE entries ADD COLUMN quickBlob BLOB DEFAULT NULL")
            }
        }

        /**
         * Etiketler. Mevcut kayıtlar boş etiket listesiyle ("[]") gelir;
         * içerikler olduğu gibi kalır (R-004: yıkıcı geçiş yok).
         */
        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE entries ADD COLUMN tags TEXT NOT NULL DEFAULT '[]'")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tags` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`color` INTEGER NOT NULL)"
                )
            }
        }

        /**
         * Not alt türü ve kullanıcı sırası. İkisi de eklemeli, varsayılanlı:
         * mevcut kayıtlar "Genel not" ve sıra 0 ile gelir (R-004).
         */
        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE entries ADD COLUMN noteKind TEXT NOT NULL DEFAULT 'GENEL'"
                )
                db.execSQL(
                    "ALTER TABLE entries ADD COLUMN sortIndex INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * Kullanım günlüğü ve kilitliyken biriken tamponu. İki tablo da yalnız
         * birincil anahtar + şifreli blob taşır; mevcut veriye dokunulmaz.
         */
        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `usage_log` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`blob` BLOB NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `usage_buffer` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`blob` BLOB NOT NULL)"
                )
            }
        }

        /**
         * Araç çıpası bayrağı. Mevcut kayıtlar `anchor = 0` ile gelir, yani
         * hiçbiri çıpa sayılmaz ve liste eskisi gibi çalışır (R-004).
         */
        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE entries ADD COLUMN anchor INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Etiket ikonu. Boş dizge = ikonsuz, yani mevcut etiketler bugüne dek
         * göründükleri gibi (yalnız renk noktası) kalır; hiçbir etiket kendine
         * ikon uydurmaz.
         */
        internal val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tags ADD COLUMN icon TEXT NOT NULL DEFAULT ''")
            }
        }

        fun get(context: Context): VaultDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    VaultDatabase::class.java,
                    "vault.db"
                )
                    // Yıkıcı geçiş YOK: şema değişince veriler silinmemeli.
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                        MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7
                    )
                    .build()
                    .also { instance = it }
            }
    }
}
