package com.afgover.vault.data

import androidx.annotation.StringRes
import com.afgover.vault.R
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import org.json.JSONObject

/**
 * Kullanım günlüğünde tutulan olay türleri.
 *
 * Günlük **ne yapıldığını** yazar, ne kullanıldığını değil: hiçbir olayda
 * değerin kendisi, bir parçası ya da uzunluğu bulunmaz. Günlüğün amacı merak
 * değil rotasyon listesidir — bir bilgisayar ele geçtiğinde "oraya hangi
 * sırlar gitti" sorusunun cevabı (vault_takip SEC-023).
 */
enum class UsageKind(@StringRes val labelRes: Int) {
    OLUSTURULDU(R.string.usage_created),
    DEGISTIRILDI(R.string.usage_updated),
    KOPYALANDI(R.string.usage_copied),
    BT_YAZILDI(R.string.usage_bt_typed),
    KLAVYE_YAZILDI(R.string.usage_keyboard_typed),
    HIZLI_ERISIM_ACILDI(R.string.usage_quick_on),
    HIZLI_ERISIM_KAPATILDI(R.string.usage_quick_off);

    companion object {
        fun of(name: String?): UsageKind? =
            runCatching { valueOf(name ?: "") }.getOrNull()
    }
}

/**
 * Tek kullanım olayı. [fieldLabel] hangi alanın kullanıldığı ("Şifre",
 * "Kart no"), [target] nereye gittiği (bilgisayarın ya da uygulamanın adı) —
 * ikisi de yalnızca **ad**, asla değer.
 */
data class UsageEvent(
    val entryId: Long,
    val kind: UsageKind,
    val at: Long,
    val fieldLabel: String? = null,
    val target: String? = null
) {
    fun toBytes(): ByteArray = JSONObject().apply {
        put("entryId", entryId)
        put("kind", kind.name)
        put("at", at)
        fieldLabel?.let { put("field", it) }
        target?.let { put("target", it) }
    }.toString().toByteArray()

    companion object {
        fun fromBytes(bytes: ByteArray): UsageEvent? = runCatching {
            val o = JSONObject(String(bytes))
            val kind = UsageKind.of(o.optString("kind"))
            if (kind == null) null else UsageEvent(
                entryId = o.getLong("entryId"),
                kind = kind,
                at = o.getLong("at"),
                fieldLabel = o.optString("field").ifBlank { null },
                target = o.optString("target").ifBlank { null }
            )
        }.getOrNull()
    }
}

/**
 * Günlük satırı. Satırda açık duran tek şey birincil anahtardır: olayın
 * kendisi — hangi kayıt, ne yapıldı, ne zaman, nereye — dataKey ile şifreli
 * [blob] içindedir.
 *
 * Kasa kilitliyken günlük "kaç olay var" dışında hiçbir şey söylemez. Başlık
 * ve türün açık durduğu kayıt tablosunun (SEC-013) yanına ikinci bir açık
 * metadata yığını konmadı: "hangi sır ne zaman kullanıldı" listesi, sırların
 * kendisi kadar anlatıcıdır.
 */
@Entity(tableName = "usage_log")
data class UsageLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val blob: ByteArray
) {
    override fun equals(other: Any?): Boolean = other is UsageLogEntity && other.id == id
    override fun hashCode(): Int = id.hashCode()
}

/**
 * Kasa kilitliyken oluşan olaylar. O anda dataKey bellekte olmadığı için olay
 * Keystore'un kimlik doğrulama istemeyen anahtarıyla ([com.afgover.vault.core.KeyManager.quickKey])
 * yazılır, ilk kilit açılışında günlüğe taşınır ve buradan silinir.
 *
 * Tampon, hızlı erişim kopyalarıyla **aynı güven sınıfındadır**: güvenliği
 * kasa parolasına değil telefonun ekran kilidine dayanır. Bu yüzden yalnızca
 * kilitliyken zaten kullanılabilen kayıtların olayları buraya düşer.
 */
@Entity(tableName = "usage_buffer")
data class UsageBufferEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val blob: ByteArray
) {
    override fun equals(other: Any?): Boolean = other is UsageBufferEntity && other.id == id
    override fun hashCode(): Int = id.hashCode()
}

@Dao
interface UsageLogDao {

    @Insert
    suspend fun insert(row: UsageLogEntity)

    @androidx.room.Update
    suspend fun update(row: UsageLogEntity)

    @Query("SELECT * FROM usage_log ORDER BY id DESC")
    suspend fun getAll(): List<UsageLogEntity>

    @Query("SELECT COUNT(*) FROM usage_log")
    suspend fun count(): Int

    @Query("DELETE FROM usage_log WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM usage_log")
    suspend fun clear()

    @Insert
    suspend fun insertBuffered(row: UsageBufferEntity)

    @Query("SELECT * FROM usage_buffer ORDER BY id")
    suspend fun getBuffered(): List<UsageBufferEntity>

    @Query("SELECT COUNT(*) FROM usage_buffer")
    suspend fun bufferCount(): Int

    @Query("DELETE FROM usage_buffer WHERE id IN (:ids)")
    suspend fun deleteBufferedByIds(ids: List<Long>)

    @Query("DELETE FROM usage_buffer")
    suspend fun clearBuffer()
}
