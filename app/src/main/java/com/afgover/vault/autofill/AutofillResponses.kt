package com.afgover.vault.autofill

import android.content.Context
import android.content.IntentSender
import android.service.autofill.Dataset
import android.service.autofill.FillResponse
import android.service.autofill.SaveInfo
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import com.afgover.vault.R
import com.afgover.vault.data.DecryptedEntry
import com.afgover.vault.data.EntryType

/**
 * Çözülmüş kayıtlardan sistemin otomatik doldurma menüsünde göstereceği
 * [Dataset]'leri ve [FillResponse]'u üretir.
 *
 * Menüde yalnızca kayıt başlığı ve (kart için) maskelenmiş numara görünür;
 * şifreler hiçbir zaman ekranda gösterilmez.
 */
// RemoteViews tabanlı Dataset/setAuthentication API'leri Android 13'te
// "Presentations" lehine deprecated oldu; minSdk 26 olduğu için eski API
// tüm sürümlerde çalışan tek seçenek.
@Suppress("DEPRECATION")
object AutofillResponses {

    /** Kasa kilitliyken: tüm alanlar için "önce kilidi aç" adımı. */
    fun authResponse(
        context: Context,
        parsed: ParsedStructure,
        sender: IntentSender
    ): FillResponse = FillResponse.Builder()
        .setAuthentication(
            parsed.allIds(),
            sender,
            presentation(
                context,
                "🔐 Vault",
                context.getString(R.string.backup_autofill_unlock_to_fill)
            )
        )
        .apply { saveInfo(parsed)?.let { setSaveInfo(it) } }
        .build()

    /** Kasa açıkken: eşleşen kayıtlardan doldurma seçenekleri. */
    fun fillResponse(
        context: Context,
        parsed: ParsedStructure,
        entries: List<DecryptedEntry>
    ): FillResponse? {
        val datasets = datasets(context, parsed, entries)
        val save = saveInfo(parsed)
        if (datasets.isEmpty() && save == null) return null
        return FillResponse.Builder()
            .apply {
                datasets.forEach { addDataset(it) }
                save?.let { setSaveInfo(it) }
            }
            .build()
    }

    fun datasets(
        context: Context,
        parsed: ParsedStructure,
        entries: List<DecryptedEntry>
    ): List<Dataset> = entries
        .filter { it.type != EntryType.NOTE }
        .sortedByDescending { score(it, parsed) }
        .mapNotNull { dataset(context, parsed, it) }
        .take(MAX_DATASETS)

    private fun dataset(
        context: Context,
        parsed: ParsedStructure,
        entry: DecryptedEntry
    ): Dataset? {
        val values = mutableMapOf<FieldKind, String>()
        val d = entry.data

        if (parsed.hasLoginFields) {
            if (d.username.isNotEmpty()) values[FieldKind.USERNAME] = d.username
            if (d.password.isNotEmpty()) values[FieldKind.PASSWORD] = d.password
        }
        if (parsed.hasCardFields) {
            if (d.cardNumber.isNotEmpty()) values[FieldKind.CARD_NUMBER] = d.cardNumber
            if (d.cvv.isNotEmpty()) values[FieldKind.CVV] = d.cvv
            if (d.cardholder.isNotEmpty()) values[FieldKind.CARDHOLDER] = d.cardholder
            if (d.expiry.isNotEmpty()) {
                values[FieldKind.CARD_EXPIRY] = d.expiry
                val parts = d.expiry.split("/", "-", ".").map { it.trim() }
                if (parts.size >= 2) {
                    values[FieldKind.CARD_EXP_MONTH] = parts[0]
                    values[FieldKind.CARD_EXP_YEAR] = parts[1]
                }
            }
        }

        val filled = parsed.fields.filter { values.containsKey(it.kind) }
        if (filled.isEmpty()) return null

        val view = presentation(context, entry.title, subtitle(entry))
        val builder = Dataset.Builder(view)
        filled.forEach { field ->
            builder.setValue(field.id, AutofillValue.forText(values[field.kind]))
        }
        return builder.build()
    }

    private fun subtitle(entry: DecryptedEntry): String? = when (entry.type) {
        EntryType.CARD -> entry.data.cardNumber
            .filter { it.isDigit() }
            .takeIf { it.length >= 4 }
            ?.let { "•••• " + it.takeLast(4) }
        else -> entry.data.username.takeIf { it.isNotEmpty() }
    }

    /** Kullanıcının yeni girdiği bilgiyi Vault'a kaydetmeyi teklif eder. */
    fun saveInfo(parsed: ParsedStructure): SaveInfo? {
        val passwords = parsed.idsOf(FieldKind.PASSWORD)
        val usernames = parsed.idsOf(FieldKind.USERNAME)
        val cardNumbers = parsed.idsOf(FieldKind.CARD_NUMBER)

        return when {
            passwords.isNotEmpty() -> SaveInfo.Builder(
                SaveInfo.SAVE_DATA_TYPE_PASSWORD or SaveInfo.SAVE_DATA_TYPE_USERNAME,
                passwords.toTypedArray()
            ).apply {
                if (usernames.isNotEmpty()) setOptionalIds(usernames.toTypedArray())
            }.build()

            cardNumbers.isNotEmpty() -> SaveInfo.Builder(
                SaveInfo.SAVE_DATA_TYPE_CREDIT_CARD,
                cardNumbers.toTypedArray()
            ).apply {
                val optional = parsed.fields
                    .filter { it.kind.isCard && it.kind != FieldKind.CARD_NUMBER }
                    .map { it.id }
                if (optional.isNotEmpty()) setOptionalIds(optional.toTypedArray())
            }.build()

            else -> null
        }
    }

    fun presentation(context: Context, title: String, subtitle: String?): RemoteViews =
        RemoteViews(context.packageName, R.layout.autofill_item).apply {
            setTextViewText(R.id.autofill_title, title)
            if (subtitle.isNullOrEmpty()) {
                setViewVisibility(R.id.autofill_subtitle, android.view.View.GONE)
            } else {
                setViewVisibility(R.id.autofill_subtitle, android.view.View.VISIBLE)
                setTextViewText(R.id.autofill_subtitle, subtitle)
            }
        }

    /**
     * Kaydın istenen uygulama/siteye ne kadar uyduğu. Eşleşme bulunamazsa da
     * kayıt listelenir (0 puanla en sona düşer) — kullanıcı yine seçebilsin.
     */
    private fun score(entry: DecryptedEntry, parsed: ParsedStructure): Int {
        val entryHost = host(entry.data.url)
        val target = host(parsed.webDomain)
        if (entryHost != null && target != null) {
            if (entryHost == target) return 100
            if (entryHost.endsWith(".$target") || target.endsWith(".$entryHost")) return 90
        }
        val title = entry.title.lowercase()
        if (target != null) {
            val core = cekirdek(target)
            if (core != null && core.length >= 3 && (title.contains(core) || core.contains(title))) return 80
        }
        val tokens = parsed.packageName.split('.')
            .filter { it.length >= 3 && it !in IGNORED_PACKAGE_TOKENS }
        if (tokens.any { title.contains(it) || (entryHost?.contains(it) == true) }) return 60
        return 0
    }

    private fun host(url: String?): String? {
        val raw = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val withoutScheme = raw.substringAfter("://")
        return withoutScheme
            .substringBefore('/')
            .substringBefore('?')
            .substringAfter('@')
            .substringBefore(':')
            .removePrefix("www.")
            .lowercase()
            .takeIf { it.isNotEmpty() }
    }

    /**
     * Alan adının kayıtlı adı: `garanti.com.tr` → "garanti", `bbc.co.uk` →
     * "bbc", `accounts.google.com` → "google". Ülke uzantısının önündeki `com`,
     * `gov`, `co` gibi ikinci düzey uzantı da atlanır. Eskiden yalnız son
     * parça atılıyordu: `.com.tr` adreslerinde çekirdek "com" çıkıyor, başlığında
     * "com" geçen her kayıt 80 puanla öne geçiyordu (ikinci tur denetim).
     */
    internal fun cekirdek(host: String): String? {
        val parcalar = host.split('.').filter { it.isNotEmpty() }
        if (parcalar.size < 2) return null
        var i = parcalar.size - 2
        if (parcalar.size >= 3 && parcalar.last().length == 2 && parcalar[i] in IKINCI_DUZEY) i--
        return parcalar[i]
    }

    /** Ülke uzantılarının önünde görülen ikinci düzey uzantılar (com.tr, co.uk, gov.tr…). */
    private val IKINCI_DUZEY = setOf(
        "com", "co", "org", "net", "gov", "edu", "ac", "k12", "gen", "biz", "info", "web",
        "av", "bel", "pol", "tsk", "dr", "bbs", "tel", "name", "tv", "mil", "or", "ne", "go"
    )

    private val IGNORED_PACKAGE_TOKENS =
        setOf("com", "org", "net", "android", "app", "apps", "mobile", "www", "www2", "tr", "io")

    private const val MAX_DATASETS = 20
}
