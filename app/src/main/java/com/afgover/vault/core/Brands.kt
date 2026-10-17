package com.afgover.vault.core

import java.text.Normalizer
import java.util.Locale

/**
 * Kurum logosu: katalogdaki bir marka. Logosu olan markanın çizimi
 * `ui/BrandLogos.kt`'dedir; olmayanlar [badge] metniyle, marka renginde
 * rozet olarak çizilir. Katalog `markalar/katalog.json`'dan üretilir.
 */
data class Brand(
    val id: String,
    val name: String,
    /** ARGB. */
    val color: Int,
    /** Logosuz markada rozet metni (1–3 karakter); logolu markada boş. */
    val badge: String,
    val hasLogo: Boolean,
    val domains: List<String>,
    val keywords: List<String>
)

/**
 * Bir kaydın hangi markaya ait olduğu. iOS'taki `Brands.match` ile aynı kural;
 * ikisi de `bicim/vektorler/marka-eslesme.json`'daki değerlerle sınanır.
 *
 * 1. Kullanıcının seçimi: `"none"` → logo yok; katalogdaki bir kimlik → o marka.
 *    Bilinmeyen kimlik (eski katalog) otomatiğe düşer.
 * 2. Site adresi: alan adı markanın alan adlarından biriyse ya da onun alt
 *    alan adıysa; birden çoğu tutarsa en uzun alan adı (mail.google.com →
 *    Gmail, google.com → Google).
 * 3. Başlık: Türkçe harfler katlanmış başlıkta markanın anahtar sözcüklerinden
 *    biri **tam sözcük** olarak geçiyorsa; en uzun anahtar kazanır ("Amazon Web
 *    Services" → AWS, "Amazon" değil). "ing" gibi kısa anahtarlar "Swing" ya da
 *    "İngilizce" ile eşleşmez.
 * 4. Kart kaydı, başka eşleşme yoksa: kart numarasının ağı (Visa, Mastercard, Amex).
 */
object Brands {

    const val NONE = "none"

    val all: List<Brand> get() = BRAND_CATALOG
    private val byId by lazy { BRAND_CATALOG.associateBy { it.id } }
    private val anahtarlar by lazy {
        BRAND_CATALOG.flatMap { b -> b.keywords.map { normalize(it) }.filter { it.isNotEmpty() }.map { it to b } }
    }

    fun byId(id: String): Brand? = byId[id]

    fun match(title: String, url: String, choice: String = "", isCard: Boolean = false, cardNumber: String = ""): Brand? {
        if (choice == NONE) return null
        byId[choice]?.let { return it }
        host(url)?.let { h ->
            BRAND_CATALOG.flatMap { b -> b.domains.filter { h == it || h.endsWith(".$it") }.map { it to b } }
                .maxByOrNull { it.first.length }?.let { return it.second }
        }
        val t = " ${normalize(title)} "
        anahtarlar.filter { t.contains(" ${it.first} ") }.maxByOrNull { it.first.length }?.let { return it.second }
        if (isCard) cardNetwork(cardNumber)?.let { return byId[it] }
        return null
    }

    /** Arama ve eşleşme için: küçük harf, aksan ve Türkçe harf katlanır, harf/rakam dışı boşluk olur. */
    fun normalize(s: String): String {
        val kucuk = s.lowercase(Locale.ROOT)
        val acik = Normalizer.normalize(kucuk, Normalizer.Form.NFKD).replace(Regex("\\p{Mn}+"), "")
        return acik.replace('ı', 'i').replace(Regex("[^a-z0-9]+"), " ").trim()
    }

    /** Adresin alan adı: şema, kullanıcı, kapı, yol ve "www." atılır; yoksa null. */
    fun host(url: String): String? {
        var h = url.trim().lowercase(Locale.ROOT)
        val sema = h.indexOf("://")
        if (sema >= 0) h = h.substring(sema + 3)
        h = h.substringBefore('/').substringBefore('?').substringBefore('#')
        h = h.substringAfterLast('@').substringBefore(':').trimEnd('.')
        if (h.startsWith("www.")) h = h.removePrefix("www.")
        return h.takeIf { it.contains('.') && it.none { c -> c.isWhitespace() } }
    }

    /** Kart numarasının ağı: 4 → Visa, 34/37 → Amex, 51–55 ve 2221–2720 → Mastercard. */
    fun cardNetwork(number: String): String? {
        val d = number.filter { it.isDigit() }
        if (d.length < 4) return null
        val iki = d.take(2).toInt()
        val dort = d.take(4).toInt()
        return when {
            d[0] == '4' -> "visa"
            iki == 34 || iki == 37 -> "amex"
            iki in 51..55 || dort in 2221..2720 -> "mastercard"
            else -> null
        }
    }
}
