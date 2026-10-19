package com.afgover.vault.data

import androidx.annotation.StringRes
import com.afgover.vault.R
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * EVERYDAY: gündelik kullanımda lazım olan bilgiler (ad soyad, telefon,
 * e-posta, adres). Bu türde açılan yeni kayıtlar varsayılan olarak klavyede
 * parolasız kullanılabilir işaretiyle gelir.
 */
enum class EntryType { LOGIN, EVERYDAY, CARD, NOTE }

/**
 * Kaydın başlığı ve bütün hassas alanları [blob] içinde AES-256-GCM ile
 * şifrelidir (biçim sürüm 2, [KayitBicimi]); açık duranlar tür, not türü,
 * bayraklar, sıra, etiket kimlikleri ve tarihlerdir. [uid] boşsa satır
 * sürüm 1'dendir: başlık [title] sütununda açık durur ve kilit ilk açıldığında
 * taşınır.
 *
 * [quick] işaretli kayıtlar klavyede kasa kilitliyken de kullanılabilir.
 * Bunun için aynı içeriğin ikinci bir kopyası [quickBlob] içinde, Keystore'daki
 * kimlik doğrulama istemeyen anahtarla şifrelenir. Aslı ([blob]) her zaman ana
 * paroladan türeyen dataKey ile şifreli kalır — Keystore kaybolsa bile veri
 * kaybı olmaz, kopyalar yeniden üretilir.
 */
@Entity(tableName = "entries")
data class EntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val title: String,
    val blob: ByteArray,
    val createdAt: Long,
    val updatedAt: Long,
    val quick: Boolean = false,
    val quickBlob: ByteArray? = null,
    /** Etiket id'leri, JSON dizi ("[1,3]"). Adlar tags tablosunda (SEC-021). */
    val tags: String = "[]",
    /**
     * Güvenli not alt türü ([NoteKind]); yalnız NOTE kayıtlarında anlamlı.
     * Türle aynı gerekçeyle şifresiz: liste ikonunu çizmek için kilit
     * açmadan okunabilmeli (SEC-013/SEC-021 ailesi).
     */
    val noteKind: String = NoteKind.GENEL.name,
    /** Kullanıcı sırası ([EntrySort.MANUAL]); küçükten büyüğe. */
    val sortIndex: Int = 0,
    /**
     * Araç çıpası mı? Kasada **en çok bir** tane bulunur: bilgisayar tarafı
     * aracının (aktar.html) SHA-256'sı. Kayıt listesinde ve klavyede
     * görünmez, üst çubuktaki kendi düğmesinden açılır — o bir sır değil,
     * indirdiğin dosyanın doğru dosya olduğunu ölçtüğün referanstır.
     *
     * Tür gibi şifresiz: hangi satırın çıpa olduğu kilit açmadan bilinmeli
     * ki liste sorgusu onu dışarıda bırakabilsin (SEC-013/SEC-021 ailesi).
     */
    val anchor: Boolean = false,
    /**
     * Satırın kalıcı, rastgele kimliği (sürüm 2). Şifreli gövde ve hızlı
     * erişim kopyası buna AAD ile bağlıdır: başka satıra taşınan gövde
     * açılmaz. Boş = sürüm 1 satırı.
     */
    val uid: String = ""
) {
    override fun equals(other: Any?): Boolean =
        other is EntryEntity && other.id == id && other.updatedAt == updatedAt

    override fun hashCode(): Int = (id * 31 + updatedAt).toInt()
}

/** Güvenli notun ne taşıdığı — ikon ve düzenleme ipucu bundan türer. */
enum class NoteKind(@StringRes val labelRes: Int) {
    GENEL(R.string.note_kind_general),
    BETIK(R.string.note_kind_script),
    ANAHTAR(R.string.note_kind_key),
    PARMAK_IZI(R.string.note_kind_fingerprint),
    KURTARMA(R.string.note_kind_recovery),
    YAPILANDIRMA(R.string.note_kind_config);

    companion object {
        fun of(name: String?): NoteKind =
            runCatching { valueOf(name ?: "") }.getOrDefault(GENEL)
    }
}
