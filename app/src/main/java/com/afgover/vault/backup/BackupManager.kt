package com.afgover.vault.backup

import java.util.Base64
import com.afgover.vault.core.Crypto
import com.afgover.vault.data.DecryptedEntry
import com.afgover.vault.data.EntryData
import com.afgover.vault.data.EntryType
import com.afgover.vault.data.TagDef
import com.afgover.vault.data.TagEntity
import com.afgover.vault.data.TagIcons
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream

/**
 * Şifreli yedek dosyası (.vaultbak).
 *
 * Dosya, ana paroladan PBKDF2 ile türetilen bağımsız bir anahtarla AES-256-GCM
 * kullanılarak şifrelenir. Cihaza, Android Keystore'a veya uygulama verisine
 * hiçbir bağımlılığı yoktur: telefon sıfırlansa ya da değişse bile dosya +
 * parola ile her şey geri yüklenir.
 *
 * Biçim (düz JSON zarf, içerik şifreli):
 * {
 *   "app": "vault", "version": 1,
 *   "kdf": {"algo": "PBKDF2WithHmacSHA256", "iterations": N, "salt": b64},
 *   "cipher": "AES-256-GCM",
 *   "data": b64( iv || ciphertext+tag )   // içerik: {"entries":[...]}
 * }
 */
object BackupManager {

    // Mesajlar TEKNİKTİR ve kullanıcıya gösterilmez: arayüz bu istisnaları
    // TİPLE yakalayıp kendi dilinde mesaj kurar (yerelleştirme).
    class WrongPasswordException : Exception("wrong backup password")
    class InvalidFormatException : Exception("invalid backup envelope")

    /**
     * Girdi üst sınırı. Meşru bir yedek (256 KB/kayıt) bunun çok altındadır;
     * amaç yanlış seçilen dev dosyanın (video, ZIP) belleği doldurup uygulamayı
     * çökertmesini engellemek (denetim: OOM). Panodan yapıştırmada 1 MB ayrıca
     * sınırlıdır; bu, dosya yolunun tavanıdır.
     */
    const val MAX_ENVELOPE_BYTES = 16 * 1024 * 1024

    /**
     * PBKDF2 tur sayısı için kabul aralığı. Düşman bir zarf iterations alanına
     * çok büyük bir değer koyup türetmeyi dakikalarca kilitleyebilir (denetim:
     * DoS). Kendi ürettiğimiz zarf her zaman 310.000 kullanır; aralık makul
     * bir tavan bırakır.
     */
    const val MIN_ITERATIONS = 10_000
    const val MAX_ITERATIONS = 1_000_000

    /** İçe aktarma sonucu: kayıtlar + yedekteki etiket renkleri (ad → ARGB). */
    data class ImportResult(
        val entries: List<DecryptedEntry>,
        val tagDefs: Map<String, TagDef>
    )

    /**
     * [tags] cihazdaki etiket tanımlarıdır; kayıtlar etiketleri id ile değil
     * ADLA yedekler (id cihaza özgüdür) ve renkler `tagDefs` altında taşınır.
     * Alanlar eklemeli: eski uygulama yeni yedeği (etiketleri atlayarak),
     * yeni uygulama eski yedeği (etiketsiz) sorunsuz okur; sürüm 1 kalır.
     */
    fun export(
        output: OutputStream,
        password: CharArray,
        entries: List<DecryptedEntry>,
        tags: List<TagEntity> = emptyList()
    ) {
        val tagName = tags.associate { it.id to it.name }
        val payload = JSONObject().put("entries", JSONArray().apply {
            entries.forEach { e ->
                put(
                    JSONObject()
                        .put("type", e.type.name)
                        .put("title", e.title)
                        .put("createdAt", e.createdAt)
                        .put("updatedAt", e.updatedAt)
                        .put("quick", e.quick)
                        .put("sortIndex", e.sortIndex)
                        .put("data", e.data.toJson())
                        .apply {
                            // Araç çıpası yedeğe kendini tanıtmalı; yoksa geri
                            // yüklemede normal bir nota dönüşür ve listede belirir.
                            if (e.anchor) put("anchor", true)
                        }
                        .apply {
                            if (e.noteKind != com.afgover.vault.data.NoteKind.GENEL) {
                                put("noteKind", e.noteKind.name)
                            }
                        }
                        .apply {
                            val names = e.tagIds.mapNotNull(tagName::get)
                            if (names.isNotEmpty()) {
                                put("tags", JSONArray().apply { names.forEach(::put) })
                            }
                        }
                )
            }
        })
        val usedTagIds = entries.flatMap { it.tagIds }.toSet()
        val usedTags = tags.filter { it.id in usedTagIds }
        if (usedTags.isNotEmpty()) {
            payload.put("tagDefs", JSONArray().apply {
                usedTags.forEach {
                    val def = JSONObject().put("name", it.name).put("color", it.color)
                    // İkonsuz etiket için alan hiç yazılmaz: eski sürümün
                    // okuyucusu bilmediği alanı zaten atlar, dosya da
                    // gereksiz büyümez.
                    if (it.icon.isNotEmpty()) def.put("icon", it.icon)
                    put(def)
                }
            })
        }

        val salt = Crypto.randomBytes(16)
        val key = Crypto.deriveKey(password, salt)
        val blob = Crypto.encrypt(key, payload.toString().toByteArray(Charsets.UTF_8))

        val envelope = JSONObject()
            .put("app", "vault")
            .put("version", 1)
            .put(
                "kdf",
                JSONObject()
                    .put("algo", "PBKDF2WithHmacSHA256")
                    .put("iterations", Crypto.KDF_ITERATIONS)
                    .put("salt", Base64.getEncoder().encodeToString(salt))
            )
            .put("cipher", "AES-256-GCM")
            .put("data", Base64.getEncoder().encodeToString(blob))

        output.use { it.write(envelope.toString(2).toByteArray(Charsets.UTF_8)) }
    }

    /**
     * Panodan gelen metni ayrıştırılabilir hâle getirir. Tarayıcıdan
     * kopyalarken başa BOM ya da görünmez karakter, sona satır sonu
     * gelebiliyor; `trim()` bunların hepsini temizlemez (`\uFEFF` boşluk
     * sayılmaz) ve `JSONObject` tek bir görünmez karaktere takılıp
     * "geçersiz dosya" diyor. Zarf ilk `{` ile son `}` arasından çıkarılır;
     * içerik yine GCM ile doğrulandığı için bu gevşeklik güvenliği
     * etkilemez, yalnız kullanıcıyı gereksiz hatadan kurtarır.
     */
    internal fun normalize(raw: String): String {
        val temiz = raw.filterNot { it == '\uFEFF' || it == '\u200B' || it == '\u200E' }.trim()
        val bas = temiz.indexOf('{')
        val son = temiz.lastIndexOf('}')
        return if (bas >= 0 && son > bas) temiz.substring(bas, son + 1) else temiz
    }

    /**
     * Akıştan en fazla [limit] bayt okur; aşılırsa yedek olamayacak kadar büyük
     * demektir ve açık bir hata fırlatılır (çökme yerine mesaj).
     */
    private fun readCapped(input: InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            total += n
            if (total > limit) throw InvalidFormatException()
            out.write(chunk, 0, n)
        }
        return out.toByteArray()
    }

    fun import(input: InputStream, password: CharArray): ImportResult {
        val bytes = input.use { readCapped(it, MAX_ENVELOPE_BYTES) }
        val text = normalize(bytes.toString(Charsets.UTF_8))
        val envelope = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw InvalidFormatException()
        }
        if (envelope.optString("app") != "vault") throw InvalidFormatException()

        val kdf = envelope.optJSONObject("kdf") ?: throw InvalidFormatException()
        val salt = try {
            Base64.getDecoder().decode(kdf.optString("salt"))
        } catch (e: Exception) {
            throw InvalidFormatException()
        }
        val iterations = kdf.optInt("iterations", Crypto.KDF_ITERATIONS)
        if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) {
            throw InvalidFormatException()
        }
        val blob = try {
            Base64.getDecoder().decode(envelope.optString("data"))
        } catch (e: Exception) {
            throw InvalidFormatException()
        }

        val key = Crypto.deriveKey(password, salt, iterations)
        val plain = Crypto.decrypt(key, blob) ?: throw WrongPasswordException()

        val payload = try {
            JSONObject(String(plain, Charsets.UTF_8))
        } catch (e: Exception) {
            throw InvalidFormatException()
        }
        val array = payload.optJSONArray("entries") ?: throw InvalidFormatException()

        val entries = buildList {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                add(
                    DecryptedEntry(
                        id = 0,
                        type = runCatching { EntryType.valueOf(o.optString("type")) }
                            .getOrDefault(EntryType.NOTE),
                        title = o.optString("title"),
                        data = EntryData.fromJson(o.optJSONObject("data") ?: JSONObject()),
                        createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                        updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                        // Eski yedeklerde alan yok: korumalı kabul edilir.
                        quick = o.optBoolean("quick", false),
                        // Elle kurulan sıra yedeğe girsin: eskiden yazılmıyordu,
                        // geri yüklemede hepsi 0'a düşüyordu (denetim).
                        sortIndex = o.optInt("sortIndex", 0),
                        noteKind = com.afgover.vault.data.NoteKind.of(o.optString("noteKind")),
                        // Eski yedeklerde alan yok: çıpa değil kabul edilir.
                        anchor = o.optBoolean("anchor", false),
                        tagNames = o.optJSONArray("tags")?.let { arr ->
                            buildList { for (j in 0 until arr.length()) add(arr.getString(j)) }
                        } ?: emptyList()
                    )
                )
            }
        }
        val tagDefs = buildMap {
            payload.optJSONArray("tagDefs")?.let { defs ->
                for (i in 0 until defs.length()) {
                    val d = defs.optJSONObject(i) ?: continue
                    val name = d.optString("name")
                    // İkon alanı olmayan (bu sürümden eski) yedekler ikonsuz
                    // döner; tanınmayan anahtar da normalize ile düşer.
                    if (name.isNotEmpty() && d.has("color")) {
                        put(name, TagDef(d.getInt("color"), TagIcons.normalize(d.optString("icon"))))
                    }
                }
            }
        }
        return ImportResult(entries, tagDefs)
    }
}
