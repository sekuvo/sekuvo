package com.afgover.vault.data

import com.afgover.vault.core.Crypto
import org.json.JSONObject
import javax.crypto.SecretKey

/**
 * Cihazdaki kayıt biçimi, sürüm 2 (1.3).
 *
 * Sürüm 1'de kaydın başlığı ve etiket adları veritabanında açık duruyordu ve
 * şifreli gövdeler satırlarına bağlı değildi: veritabanına yazabilen biri iki
 * satırın gövdesini takas edebiliyordu ("Netflix" başlığının altında banka
 * parolası). Sürüm 2'de:
 *
 * - başlık kaydın şifreli gövdesine girer (verinin JSON'unda `title`);
 *   veritabanındaki `title` sütunu boş kalır;
 * - etiket adı `nameBlob`'da şifreli durur, `name` sütunu boş kalır;
 * - her mühür yerine ilişkili veriyle (AAD) bağlanır: başka satıra, başka
 *   sütuna ya da başka tabloya taşınan mühür açılmaz.
 *
 * Satırın sürümü `uid` sütunundan okunur: boşsa sürüm 1, doluysa sürüm 2.
 * Kasanın kilidi ilk açıldığında [VaultRepository.upgradeStorage] eski
 * satırları tek işlemde taşır. Yedek zarfı (`.vaultbak`) bu biçimden
 * bağımsızdır ve değişmedi. Ortak tarif ve iOS karşılığı: `bicim/README.md` §6.
 */
object KayitBicimi {

    private const val ONEK = "sekuvo/v2"

    /** Satırın kalıcı, rastgele kimliği: 16 bayt, 32 onaltılı karakter. */
    fun yeniUid(): String = Crypto.randomBytes(16).joinToString("") { "%02x".format(it) }

    /** Kaydın gövdesi: satır kimliği, tür ve çıpa bayrağı. */
    fun govdeAad(uid: String, type: String, anchor: Boolean): ByteArray =
        "$ONEK/entries/$uid/blob/$type/${if (anchor) 1 else 0}".toByteArray(Charsets.UTF_8)

    /** Kaydın hızlı erişim kopyası (Keystore anahtarıyla). */
    fun hizliAad(uid: String, type: String): ByteArray =
        "$ONEK/entries/$uid/quick/$type".toByteArray(Charsets.UTF_8)

    /** Etiketin adı. */
    fun etiketAad(uid: String): ByteArray =
        "$ONEK/tags/$uid/name".toByteArray(Charsets.UTF_8)

    /** Kullanım günlüğü ve kilitliyken biriken tampon: yalnız tablo ayrımı. */
    val GUNLUK_AAD: ByteArray = "$ONEK/usage_log".toByteArray(Charsets.UTF_8)
    val TAMPON_AAD: ByteArray = "$ONEK/usage_buffer".toByteArray(Charsets.UTF_8)

    /** Sürüm 2 gövdesinin düz metni: verinin JSON'u ve `title`. */
    fun acikMetin(title: String, data: EntryData): ByteArray =
        data.toJson().put("title", title).toString().toByteArray(Charsets.UTF_8)

    /** Gövde düz metni → (başlık, veri). Sürüm 1 gövdesinde başlık yoktur: "". */
    fun coz(plain: ByteArray): Pair<String, EntryData>? = try {
        val o = JSONObject(String(plain, Charsets.UTF_8))
        o.optString("title") to EntryData.fromJson(o)
    } catch (e: Exception) {
        null
    }

    // ── Okuma: iki sürüm ────────────────────────────────────────────────

    /** Kaydın gövdesi → (başlık, veri), satırın sürümüne göre; açılmazsa null. */
    fun govdeyiAc(e: EntryEntity, key: SecretKey): Pair<String, EntryData>? =
        if (e.uid.isEmpty()) {
            Crypto.decrypt(key, e.blob)?.let(::coz)?.let { e.title to it.second }
        } else {
            Crypto.decrypt(key, e.blob, govdeAad(e.uid, e.type, e.anchor))?.let(::coz)
        }

    /** Hızlı erişim kopyası → (başlık, veri); kopya yoksa ya da açılmazsa null. */
    fun hizliyiAc(e: EntryEntity, quickKey: SecretKey): Pair<String, EntryData>? {
        val blob = e.quickBlob ?: return null
        return if (e.uid.isEmpty()) {
            Crypto.decrypt(quickKey, blob)?.let(::coz)?.let { e.title to it.second }
        } else {
            Crypto.decrypt(quickKey, blob, hizliAad(e.uid, e.type))?.let(::coz)
        }
    }

    /** Etiketin adı; sürüm 1'de açık sütundur. Açılmazsa null. */
    fun etiketAdi(t: TagEntity, key: SecretKey): String? =
        if (t.uid.isEmpty()) t.name
        else t.nameBlob?.let { Crypto.decrypt(key, it, etiketAad(t.uid)) }?.toString(Charsets.UTF_8)

    /** Günlük ya da tampon mühürü: önce sürüm 2, olmazsa sürüm 1. */
    fun gunluguAc(key: SecretKey, blob: ByteArray, aad: ByteArray): ByteArray? =
        Crypto.decrypt(key, blob, aad) ?: Crypto.decrypt(key, blob)

    // ── Yazma: hep sürüm 2 ──────────────────────────────────────────────

    /** Etiketi verilen adla mühürler; uid yoksa verilir. */
    fun etiketiMuhurle(t: TagEntity, ad: String, key: SecretKey): TagEntity {
        val uid = t.uid.ifEmpty { yeniUid() }
        return t.copy(
            uid = uid,
            name = "",
            nameBlob = Crypto.encrypt(key, ad.toByteArray(Charsets.UTF_8), etiketAad(uid))
        )
    }

    // ── Geçiş: sürüm 1 → 2 ──────────────────────────────────────────────

    /**
     * Sürüm 1 kaydını sürüm 2'ye taşır: yeni uid, başlık gövdeye, gövde ve
     * hızlı erişim kopyası AAD'li, açık başlık silinir. Sürüm 2 satırı ya da
     * açılamayan gövde için null (satıra dokunulmaz). [quickKey] yoksa kopya
     * düşer; kilit açılışındaki onarım onu kaydın aslından yeniden yazar.
     */
    fun tasi(e: EntryEntity, key: SecretKey, quickKey: SecretKey?): EntryEntity? {
        if (e.uid.isNotEmpty()) return null
        val (_, data) = Crypto.decrypt(key, e.blob)?.let(::coz) ?: return null
        val uid = yeniUid()
        val plain = acikMetin(e.title, data)
        return e.copy(
            uid = uid,
            title = "",
            blob = Crypto.encrypt(key, plain, govdeAad(uid, e.type, e.anchor)),
            quickBlob = if (e.quick && quickKey != null) {
                Crypto.encryptWithGeneratedIv(quickKey, plain, hizliAad(uid, e.type))
            } else null
        )
    }

    /** Sürüm 1 etiketini mühürler; sürüm 2 ise null. */
    fun etiketiTasi(t: TagEntity, key: SecretKey): TagEntity? =
        if (t.uid.isNotEmpty()) null else etiketiMuhurle(t, t.name, key)

    /** Sürüm 1 günlük satırını AAD'li yeniden mühürler; zaten sürüm 2 ya da açılmıyorsa null. */
    fun gunluguTasi(blob: ByteArray, key: SecretKey): ByteArray? {
        if (Crypto.decrypt(key, blob, GUNLUK_AAD) != null) return null
        val plain = Crypto.decrypt(key, blob) ?: return null
        return Crypto.encrypt(key, plain, GUNLUK_AAD)
    }
}
