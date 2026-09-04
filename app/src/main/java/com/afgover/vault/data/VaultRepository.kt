package com.afgover.vault.data

import android.content.Context
import androidx.room.withTransaction
import com.afgover.vault.core.Crypto
import com.afgover.vault.core.KeyManager
import com.afgover.vault.core.VaultSession
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import javax.crypto.SecretKey

/**
 * Kayıtların şifreli saklanması.
 *
 * Her kaydın aslı [EntryEntity.blob] içinde, ana paroladan türeyen dataKey ile
 * şifrelidir. "Hızlı erişim" işaretli kayıtların bir de [EntryEntity.quickBlob]
 * kopyası vardır; o kopya Keystore'daki kimlik doğrulama istemeyen anahtarla
 * şifrelenir ve klavyenin kasa kilitliyken okuyabildiği tek şeydir.
 */
class VaultRepository(
    context: Context,
    private val keys: KeyManager,
    private val usage: UsageLogRepository = UsageLogRepository(context, keys)
) {

    companion object {
        /**
         * Tek kaydın şifrelenmemiş içeriği için üst sınır. Gerçek tavan
         * Android'in CursorWindow'u (varsayılan 2 MB): satıra sığmayan kayıt
         * yazılırken değil, SONRAKİ liste sorgusunda "Row too big" ile çöker
         * ve kayıt erişilmez olur. Hızlı erişim kopyası (quickBlob) satırı
         * ikiye katladığı için sınır tavanın dörtte birinin de altında
         * tutuluyor. (vault_takip B-040, A-2026-08-21-004)
         */
        const val MAX_PLAIN_BYTES = 256 * 1024

        /**
         * Araç çıpasının başlığı. Bilinçli olarak ÇEVRİLMEZ: bu bir etiket
         * değil, doğruladığın dosyanın adı — her dilde aynı dosyadır.
         */
        const val ANCHOR_TITLE = "aktar.html"
    }

    /**
     * Kayıt [MAX_PLAIN_BYTES] sınırını aşarsa fırlatılır. Mesaj METİN değil
     * VERİ taşır: arayüz onu kendi dilinde kurar (yerelleştirme).
     */
    class EntryTooLargeException(val title: String, val bytes: Int) : Exception(
        "entry too large: $title ${bytes / 1024}KB"
    ) {
        val kb: Int get() = bytes / 1024
        val limitKb: Int get() = MAX_PLAIN_BYTES / 1024
    }

    private fun checkSize(title: String, plain: ByteArray) {
        if (plain.size > MAX_PLAIN_BYTES) throw EntryTooLargeException(title, plain.size)
    }

    private val db = VaultDatabase.get(context)
    private val dao = db.entryDao()
    private val tagDao = db.tagDao()
    private val usageLogDao = db.usageLogDao()

    /** Liste: çıpa hariç. */
    fun observeAll(): Flow<List<EntryEntity>> = dao.observeVisible()

    suspend fun getAll(): List<EntryEntity> = dao.getAll()

    fun decrypt(entity: EntryEntity, key: SecretKey): DecryptedEntry? =
        entity.toDecrypted(Crypto.decrypt(key, entity.blob))

    suspend fun getDecrypted(id: Long, key: SecretKey): DecryptedEntry? =
        dao.getById(id)?.let { decrypt(it, key) }

    /** Yedek için: çıpa DAHİL hepsi. */
    suspend fun getAllDecrypted(key: SecretKey): List<DecryptedEntry> =
        dao.getAll().mapNotNull { decrypt(it, key) }

    /** Klavye için: çıpa hariç — çıpa bir sır değil, orada işi yok. */
    suspend fun getVisibleDecrypted(key: SecretKey): List<DecryptedEntry> =
        dao.getVisible().mapNotNull { decrypt(it, key) }

    /**
     * Kasa kilitliyken görülebilen tek küme: hızlı erişim işaretli kayıtlar.
     * Keystore anahtarı yoksa ya da kopya çözülemiyorsa kayıt atlanır —
     * kilitli kasadan asla ana blob okunmaz.
     */
    suspend fun getQuickDecrypted(): List<DecryptedEntry> {
        val quickKey = keys.quickKey() ?: return emptyList()
        return dao.getQuick().mapNotNull { entity ->
            val blob = entity.quickBlob ?: return@mapNotNull null
            entity.toDecrypted(Crypto.decrypt(quickKey, blob))
        }
    }

    suspend fun save(
        id: Long,
        type: EntryType,
        title: String,
        data: EntryData,
        quick: Boolean,
        key: SecretKey,
        tagIds: List<Long> = emptyList(),
        noteKind: NoteKind = NoteKind.GENEL,
        sortIndex: Int = 0
    ) {
        val plain = data.bytes()
        checkSize(title, plain)
        val blob = Crypto.encrypt(key, plain)
        val quickBlob = quickCopy(plain, quick)
        val now = System.currentTimeMillis()
        if (id == 0L) {
            val newId = dao.insert(
                EntryEntity(
                    type = type.name,
                    title = title,
                    blob = blob,
                    createdAt = now,
                    updatedAt = now,
                    quick = quick,
                    quickBlob = quickBlob,
                    tags = TagIds.serialize(tagIds),
                    noteKind = noteKind.name,
                    // Öncelik kullanıcının girdiği sayıdır; girmediyse 0 =
                    // numarasız ve kayıt sıranın sonunda kalır (priorityKey).
                    sortIndex = sortIndex
                )
            )
            usage.record(UsageEvent(newId, UsageKind.OLUSTURULDU, now))
        } else {
            val existing = dao.getById(id) ?: return
            dao.update(
                existing.copy(
                    type = type.name,
                    title = title,
                    blob = blob,
                    updatedAt = now,
                    quick = quick,
                    quickBlob = quickBlob,
                    tags = TagIds.serialize(tagIds),
                    noteKind = noteKind.name,
                    sortIndex = sortIndex
                )
            )
            usage.record(UsageEvent(id, UsageKind.DEGISTIRILDI, now))
        }
    }


    // ── Araç çıpası ────────────────────────────────────────────────────────
    //
    // Kasada en çok bir tane bulunur ve normal kayıt akışının dışındadır:
    // listede/klavyede görünmez, hızlı erişimi ve etiketi yoktur. Değeri
    // (SHA-256) gövdedeki `notes` alanında durur; başlık aracın dosya adıdır
    // ve bilinçli olarak çevrilmez — "aktar.html" her dilde aynı dosyadır.

    /** Kurulu çıpa; henüz yoksa null. */
    suspend fun getAnchorDecrypted(key: SecretKey): DecryptedEntry? =
        dao.getAnchor()?.let { decrypt(it, key) }

    /**
     * Çıpayı kurar ya da günceller. Kayıt zaten varsa ÜZERİNE yazılır —
     * "güncel çıpa" tek bir değerdir, kopya biriktirmez. Oluşturma tarihi
     * ilk kurulumdan kalır, güncelleme tarihi her yazımda tazelenir.
     */
    suspend fun saveAnchor(value: String, key: SecretKey) {
        val plain = EntryData(notes = value).bytes()
        val blob = Crypto.encrypt(key, plain)
        val now = System.currentTimeMillis()
        val existing = dao.getAnchor()
        if (existing == null) {
            dao.insert(
                EntryEntity(
                    type = EntryType.NOTE.name,
                    title = ANCHOR_TITLE,
                    blob = blob,
                    createdAt = now,
                    updatedAt = now,
                    noteKind = NoteKind.PARMAK_IZI.name,
                    anchor = true
                )
            )
        } else {
            dao.update(existing.copy(blob = blob, updatedAt = now))
        }
    }

    /** Detay ekranından hızlı erişimi açıp kapatmak için; içerik değişmez. */
    suspend fun setQuick(id: Long, quick: Boolean, key: SecretKey) {
        val existing = dao.getById(id) ?: return
        val plain = Crypto.decrypt(key, existing.blob) ?: return
        dao.update(existing.copy(quick = quick, quickBlob = quickCopy(plain, quick)))
        usage.record(
            UsageEvent(
                id,
                if (quick) UsageKind.HIZLI_ERISIM_ACILDI else UsageKind.HIZLI_ERISIM_KAPATILDI,
                System.currentTimeMillis()
            )
        )
    }

    /**
     * Kilit açıldığında çağrılır: Keystore anahtarı değiştiyse ya da kopya hiç
     * üretilememişse hızlı erişim kopyalarını yeniden yazar. Kaydın aslı hep
     * dataKey ile şifreli olduğu için bu işlem veriyi kaynağından üretir.
     */
    suspend fun repairQuickCopies(key: SecretKey) {
        val quickKey = keys.quickKey() ?: return
        dao.getQuick().forEach { entity ->
            val usable = entity.quickBlob?.let { Crypto.decrypt(quickKey, it) } != null
            if (usable) return@forEach
            val plain = Crypto.decrypt(key, entity.blob) ?: return@forEach
            dao.update(entity.copy(quickBlob = Crypto.encryptWithGeneratedIv(quickKey, plain)))
        }
    }

    /** Kayıt silinince günlüğü de silinir; sahipsiz olay okunmaz veridir. */
    suspend fun delete(id: Long) {
        VaultSession.key()?.let { usage.deleteFor(id, it) }
        dao.deleteById(id)
    }

    // ---- Etiketler ----

    fun observeTags(): Flow<List<TagEntity>> = tagDao.observeAll()

    suspend fun addTag(name: String, color: Int, icon: String = ""): Long =
        tagDao.insert(TagEntity(name = name.trim(), color = color, icon = TagIcons.normalize(icon)))

    suspend fun updateTag(tag: TagEntity) = tagDao.update(tag)

    /** Etiketi siler ve taşıyan her kaydın listesinden düşürür. */
    suspend fun deleteTag(id: Long) {
        tagDao.deleteById(id)
        dao.getAll().forEach { entity ->
            val ids = TagIds.parse(entity.tags)
            if (id in ids) {
                dao.update(entity.copy(tags = TagIds.serialize(ids - id)))
            }
        }
    }

    /**
     * İçe aktarma: mevcut kayıtları silip yenilerini yazar.
     *
     * Şifreleme ve boyut denetimi (toEntity → checkSize) silmeden ÖNCE koşar:
     * yedekteki bir kayıt hatalıysa mevcut kasa hiç silinmez. Silme ve yazma
     * da tek transaction'dadır; yarıda kesilen bir içe aktarma kasayı boş
     * bırakamaz (denetim: kritik veri kaybı yolu).
     */
    suspend fun replaceAll(entries: List<DecryptedEntry>, key: SecretKey, tagDefs: Map<String, TagDef> = emptyMap()) {
        val resolve = tagNameResolver(entries, tagDefs)
        val hazir = entries.map { it.toEntity(key, resolve(it)) }
        db.withTransaction {
            dao.deleteAll()
            // Eski kasanın kullanım günlüğü de gitmeli: kayıtlar silinince
            // olaylar sahipsiz kalıyordu (denetim). delete() bunu tek kayıt
            // için yapıyor; 'tümünü değiştir' hepsini yapmalı.
            usageLogDao.clear()
            usageLogDao.clearBuffer()
            dao.insertAll(hazir)
        }
    }

    /** İçe aktarma: yedektekileri mevcut kayıtların yanına ekler. */
    suspend fun addAll(entries: List<DecryptedEntry>, key: SecretKey, tagDefs: Map<String, TagDef> = emptyMap()) {
        val resolve = tagNameResolver(entries, tagDefs)
        dao.insertAll(entries.map { it.toEntity(key, resolve(it)) })
    }

    /**
     * Yedekteki etiket ADLARINI bu cihazın id'lerine çözer; olmayan etiket
     * oluşturulur (id'ler cihaza özgüdür, yedek ad taşır). Renk ve ikon
     * yedekteki tanımdan ([BackupManager] tagDefs), renk yoksa paletten gelir.
     */
    private suspend fun tagNameResolver(
        entries: List<DecryptedEntry>,
        tagDefs: Map<String, TagDef>
    ): (DecryptedEntry) -> List<Long> {
        val wanted = entries.flatMap { it.tagNames }.distinct()
        if (wanted.isEmpty()) return { it.tagIds }
        val byName = tagDao.getAll().associateBy { it.name.lowercase() }.toMutableMap()
        wanted.forEach { name ->
            if (name.lowercase() !in byName) {
                val def = tagDefs[name]
                val color = def?.color ?: TagPalette.colorFor(byName.size)
                val icon = TagIcons.normalize(def?.icon)
                val id = tagDao.insert(TagEntity(name = name, color = color, icon = icon))
                byName[name.lowercase()] = TagEntity(id, name, color, icon)
            }
        }
        return { e -> e.tagNames.mapNotNull { byName[it.lowercase()]?.id } }
    }

    suspend fun getAllTags(): List<TagEntity> = tagDao.getAll()

    // ---- Yardımcılar ----

    private fun EntryData.bytes(): ByteArray =
        toJson().toString().toByteArray(Charsets.UTF_8)

    /** Keystore erişilemiyorsa null döner; kayıt o an kilitliyken görünmez olur. */
    private fun quickCopy(plain: ByteArray, quick: Boolean): ByteArray? {
        if (!quick) return null
        val quickKey = keys.quickKey() ?: return null
        return Crypto.encryptWithGeneratedIv(quickKey, plain)
    }

    private fun EntryEntity.toDecrypted(plain: ByteArray?): DecryptedEntry? {
        if (plain == null) return null
        val data = try {
            EntryData.fromJson(JSONObject(String(plain, Charsets.UTF_8)))
        } catch (e: Exception) {
            return null
        }
        return DecryptedEntry(
            id = id,
            type = runCatching { EntryType.valueOf(type) }.getOrDefault(EntryType.NOTE),
            title = title,
            data = data,
            createdAt = createdAt,
            updatedAt = updatedAt,
            quick = quick,
            tagIds = TagIds.parse(tags),
            noteKind = NoteKind.of(noteKind),
            sortIndex = sortIndex,
            anchor = anchor
        )
    }

    private fun DecryptedEntry.toEntity(key: SecretKey, resolvedTagIds: List<Long>): EntryEntity {
        val plain = data.bytes()
        checkSize(title, plain)
        return EntryEntity(
            type = type.name,
            title = title,
            blob = Crypto.encrypt(key, plain),
            createdAt = createdAt,
            updatedAt = updatedAt,
            quick = quick,
            quickBlob = quickCopy(plain, quick),
            tags = TagIds.serialize(resolvedTagIds),
            noteKind = noteKind.name,
            sortIndex = sortIndex,
            // Çıpa bayrağı geri yüklemede de taşınmalı; yoksa yedekten dönen
            // çıpa normal bir kayda dönüşüp listede belirirdi.
            anchor = anchor
        )
    }
}
