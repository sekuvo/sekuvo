package com.afgover.vault.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Etiket tanımı. Ad ve renk veritabanında ŞİFRESİZ durur: filtreleme liste
 * ekranında çalışır ve başlık/tür ile aynı gerekçeye dayanır (SEC-013).
 * Bilinçli üstveri kabulü: SEC-021. Sır niteliğinde etiket adı kullanılmaz.
 */
@Entity(tableName = "tags")
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** ARGB; palet [TagPalette] içinden seçilir. */
    val color: Int,
    /**
     * İkon anahtarı ([TagIcons]); boş = ikonsuz, yalnız renk noktası.
     *
     * Kaynak kimliği (`R.drawable...`) değil ANAHTAR saklanır: sayı derlemeden
     * derlemeye kayar, isim kalıcıdır — yedekten dönen etiket yıllar sonra da
     * doğru ikonu bulur. Tanınmayan anahtar sessizce ikonsuza düşer.
     */
    val icon: String = ""
)

/**
 * Yedek dosyasında taşınan etiket görünümü: renk + ikon.
 *
 * İkisi tek yerde durur çünkü ikisi de aynı soruya cevap veriyor — "bu etiket
 * nasıl görünüyordu". Ayrı iki harita taşımak, birinin güncellenip diğerinin
 * unutulduğu bir geri yükleme hatasına açık kapı bırakırdı.
 */
data class TagDef(val color: Int, val icon: String = "")

/** Etiket renk paleti (ARGB). Seçim ekranı da içe aktarma da buradan alır. */
object TagPalette {
    val colors: List<Int> = listOf(
        0xFFE57373.toInt(), // kırmızı
        0xFFFFB74D.toInt(), // turuncu
        0xFFFFF176.toInt(), // sarı
        0xFF81C784.toInt(), // yeşil
        0xFF4FC3F7.toInt(), // mavi
        0xFF9575CD.toInt(), // mor
        0xFFF06292.toInt(), // pembe
        0xFF90A4AE.toInt()  // gri
    )

    fun colorFor(index: Int): Int = colors[index.mod(colors.size)]
}

/**
 * Etiket ikonu paleti: yirmi anahtar, kasada gerçekten tekrar eden kümeler için.
 *
 * Sayı bilinçli olarak kapalı tutuluyor — ikon seçimi bir tarama işi değil,
 * bir bakışta tanıma işi. Anahtarlar İngilizce ve KALICI: ekranda çevrilen bir
 * şey değil, veritabanında duran bir kimlik.
 *
 * Görsel eşleme [com.afgover.vault.ui.screens.tagIcon] içinde; veri katmanı
 * Compose'a bağımlı olmasın diye ikonun kendisi burada durmuyor.
 */
object TagIcons {
    /** Palet sırası ekranda göründüğü sıradır. */
    val keys: List<String> = listOf(
        "work", "home", "person", "group",
        "bank", "card", "shopping", "mail",
        "cloud", "server", "code", "key",
        "shield", "wifi", "game", "media",
        "music", "school", "health", "travel"
    )

    /** Bilinmeyen anahtar (eski/ileri sürüm yedeği) ikonsuza düşer. */
    fun normalize(key: String?): String =
        if (key != null && key in keys) key else ""
}

/** Kayıtların `tags` sütunundaki JSON id listesi için ortak çözümleyici. */
object TagIds {
    fun parse(json: String?): List<Long> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            buildList { for (i in 0 until arr.length()) add(arr.getLong(i)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun serialize(ids: List<Long>): String =
        org.json.JSONArray().apply { ids.distinct().forEach { put(it) } }.toString()
}

@Dao
interface TagDao {

    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE")
    suspend fun getAll(): List<TagEntity>

    @Insert
    suspend fun insert(tag: TagEntity): Long

    @Update
    suspend fun update(tag: TagEntity)

    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun deleteById(id: Long)
}
