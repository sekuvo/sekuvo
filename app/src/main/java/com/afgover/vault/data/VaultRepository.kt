package com.afgover.vault.data

import android.content.Context
import androidx.room.withTransaction
import com.afgover.vault.core.Crypto
import com.afgover.vault.core.KeyManager
import com.afgover.vault.core.VaultSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import javax.crypto.SecretKey

/**
 * Kayıtların şifreli saklanması.
 *
 * Her kaydın aslı — başlığı dahil — [EntryEntity.blob] içinde, ana paroladan
 * türeyen dataKey ile şifrelidir ve satırına bağlıdır (biçim sürüm 2,
 * [KayitBicimi]). "Hızlı erişim" işaretli kayıtların bir de
 * [EntryEntity.quickBlob] kopyası vardır; o kopya Keystore'daki kimlik
 * doğrulama istemeyen anahtarla şifrelenir ve klavyenin kasa kilitliyken
 * okuyabildiği tek şeydir. Etiket adları da dataKey ile şifrelidir.
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

        /**
         * İçe aktarılan kayıtlar → (çıpa dışındakiler, en yeni çıpa). Kasada en
         * çok bir çıpa olur: yedekte birden çoğu varsa yalnız en yenisi alınır.
         * iOS'taki `VaultStore.apply` ile aynı kural.
         */
        fun capayiAyir(entries: List<DecryptedEntry>): Pair<List<DecryptedEntry>, DecryptedEntry?> {
            val (capalar, kayitlar) = entries.partition { it.anchor }
            return kayitlar to capalar.maxByOrNull { it.updatedAt }
        }
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

    /** Liste: çıpa hariç. Başlıklar şifreli; satırı [decrypt] açar. */
    fun observeAll(): Flow<List<EntryEntity>> = dao.observeVisible()

    suspend fun getAll(): List<EntryEntity> = dao.getAll()

    /** Satırı açar (iki biçim sürümü de); açılmazsa null. */
    fun decrypt(entity: EntryEntity, key: SecretKey): DecryptedEntry? =
        KayitBicimi.govdeyiAc(entity, key)?.let { (title, data) -> entity.toDecrypted(title, data) }

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
            KayitBicimi.hizliyiAc(entity, quickKey)?.let { (title, data) -> entity.toDecrypted(title, data) }
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
        val plain = KayitBicimi.acikMetin(title, data)
        checkSize(title, plain)
        val now = System.currentTimeMillis()
        if (id == 0L) {
            val uid = KayitBicimi.yeniUid()
            val newId = dao.insert(
                EntryEntity(
                    type = type.name,
                    title = "",
                    blob = Crypto.encrypt(key, plain, KayitBicimi.govdeAad(uid, type.name, false)),
                    createdAt = now,
                    updatedAt = now,
                    quick = quick,
                    quickBlob = quickCopy(plain, quick, uid, type.name),
                    tags = TagIds.serialize(tagIds),
                    noteKind = noteKind.name,
                    // Öncelik kullanıcının girdiği sayıdır; girmediyse 0 =
                    // numarasız ve kayıt sıranın sonunda kalır (priorityKey).
                    sortIndex = sortIndex,
                    uid = uid
                )
            )
            usage.record(UsageEvent(newId, UsageKind.OLUSTURULDU, now))
        } else {
            val existing = dao.getById(id) ?: return
            // Sürüm 1 satırı düzenlenince sürüm 2'ye geçer (uid verilir).
            val uid = existing.uid.ifEmpty { KayitBicimi.yeniUid() }
            dao.update(
                existing.copy(
                    type = type.name,
                    title = "",
                    blob = Crypto.encrypt(key, plain, KayitBicimi.govdeAad(uid, type.name, existing.anchor)),
                    updatedAt = now,
                    quick = quick,
                    quickBlob = quickCopy(plain, quick, uid, type.name),
                    tags = TagIds.serialize(tagIds),
                    noteKind = noteKind.name,
                    sortIndex = sortIndex,
                    uid = uid
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
        val plain = KayitBicimi.acikMetin(ANCHOR_TITLE, EntryData(notes = value))
        val now = System.currentTimeMillis()
        val existing = dao.getAnchor()
        val uid = existing?.uid?.ifEmpty { null } ?: KayitBicimi.yeniUid()
        val type = existing?.type ?: EntryType.NOTE.name
        val blob = Crypto.encrypt(key, plain, KayitBicimi.govdeAad(uid, type, true))
        if (existing == null) {
            dao.insert(
                EntryEntity(
                    type = type,
                    title = "",
                    blob = blob,
                    createdAt = now,
                    updatedAt = now,
                    noteKind = NoteKind.PARMAK_IZI.name,
                    anchor = true,
                    uid = uid
                )
            )
        } else {
            dao.update(existing.copy(title = "", blob = blob, updatedAt = now, uid = uid))
        }
    }

    /** Detay ekranından hızlı erişimi açıp kapatmak için; içerik değişmez. */
    suspend fun setQuick(id: Long, quick: Boolean, key: SecretKey) {
        val existing = dao.getById(id) ?: return
        val (title, data) = KayitBicimi.govdeyiAc(existing, key) ?: return
        dao.update(muhurle(existing, title, data, key, quick))
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
            if (KayitBicimi.hizliyiAc(entity, quickKey) != null) return@forEach
            val (title, data) = KayitBicimi.govdeyiAc(entity, key) ?: return@forEach
            dao.update(muhurle(entity, title, data, key))
        }
    }

    /**
     * Biçim sürüm 1 → 2 (1.3); kilit açıldığında, onarımdan önce çağrılır.
     * Eski satırlar (uid boş) tek işlemde taşınır: başlık ve etiket adı
     * şifreli veriye girer, gövdeler satırlarına bağlanır, kullanım günlüğü
     * AAD'li yeniden mühürlenir. Yarıda kesilirse hiçbir şey değişmemiş olur
     * ve sonraki açılışta yeniden denenir; açılamayan satır olduğu gibi kalır.
     *
     * Açık metinler sütunlardan silindikten sonra veritabanı dosyası yeniden
     * yazılır (VACUUM, WAL boşaltılır): yoksa eski başlıklar dosyanın boş
     * sayfalarında okunabilir kalırdı. Taşınacak satır yoksa hiçbir şey
     * yapılmaz.
     *
     * @return taşınan satır sayısı (kayıt + etiket + günlük)
     */
    suspend fun upgradeStorage(key: SecretKey): Int {
        val quickKey = keys.quickKey()
        val tasinan = db.withTransaction {
            val kayitlar = dao.getAll().mapNotNull { KayitBicimi.tasi(it, key, quickKey) }
            if (kayitlar.isNotEmpty()) dao.updateAll(kayitlar)
            val etiketler = tagDao.getAll().mapNotNull { KayitBicimi.etiketiTasi(it, key) }
            if (etiketler.isNotEmpty()) tagDao.updateAll(etiketler)
            val gunluk = usageLogDao.getAll().mapNotNull { satir ->
                KayitBicimi.gunluguTasi(satir.blob, key)?.let { satir.copy(blob = it) }
            }
            gunluk.forEach { usageLogDao.update(it) }
            kayitlar.size + etiketler.size + gunluk.size
        }
        if (tasinan > 0) bosSayfalariSil()
        return tasinan
    }

    /**
     * Silinen açık metni dosyadan da çıkarır. VACUUM yeni dosyayı önce WAL'e
     * yazar; ana dosyaya ancak checkpoint'le geçer, o zamana kadar eski
     * sayfalar (eski başlıklar dahil) ana dosyada durur. Checkpoint okuyucu
     * varken yarım kalabilir; birkaç kez denenir.
     *
     * PRAGMA bir sorgudur ve Android'in imleci ilerletilmeden koşmaz:
     * `query(...).close()` hiçbir şey yapmaz (emülatörde ölçüldü).
     */
    private suspend fun bosSayfalariSil() {
        runCatching {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("VACUUM")
            repeat(5) { deneme ->
                val mesgul = sql.query("PRAGMA wal_checkpoint(TRUNCATE)").use { c ->
                    c.moveToFirst() && c.getInt(0) != 0
                }
                if (!mesgul) return@runCatching
                delay(200L * (deneme + 1))
            }
        }
    }

    /** Kayıt silinince günlüğü de silinir; sahipsiz olay okunmaz veridir. */
    suspend fun delete(id: Long) {
        VaultSession.key()?.let { usage.deleteFor(id, it) }
        dao.deleteById(id)
    }

    // ---- Etiketler ----
    //
    // Ad şifreli durur (biçim sürüm 2): depo, kilit açıkken adı çözüp
    // TagEntity.name'e koyarak döndürür ve sırayı çözülmüş adla kurar.
    // Kilitliyken liste boştur; etiket arayüzü zaten yalnız kasa açıkken var.

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeTags(): Flow<List<TagEntity>> =
        combine(tagDao.observeAll(), VaultSession.unlocked) { satirlar, acik -> satirlar to acik }
            .mapLatest { (satirlar, acik) ->
                val key = if (acik) VaultSession.key() else null
                if (key == null) emptyList() else acikEtiketler(satirlar, key)
            }
            .flowOn(Dispatchers.Default)

    suspend fun addTag(name: String, color: Int, icon: String = "", key: SecretKey): Long =
        tagDao.insert(
            KayitBicimi.etiketiMuhurle(
                TagEntity(name = "", color = color, icon = TagIcons.normalize(icon)), name.trim(), key
            )
        )

    /** [tag].name çözülmüş (yeni) addır; yeniden mühürlenir. */
    suspend fun updateTag(tag: TagEntity, key: SecretKey) =
        tagDao.update(KayitBicimi.etiketiMuhurle(tag, tag.name.trim(), key))

    /** Açılamayan etiket (bozuk mühür) listeden düşer. */
    private fun acikEtiketler(satirlar: List<TagEntity>, key: SecretKey): List<TagEntity> =
        satirlar.mapNotNull { t -> KayitBicimi.etiketAdi(t, key)?.let { t.copy(name = it) } }
            .sortedBy { it.name.lowercase() }

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
     *
     * Çıpadan başka kayıt taşımayan zarf boştur: hiçbir şey yapılmaz. Yoksa
     * yalnız çıpa taşıyan bir yedek kasayı boşaltırdı (ikinci tur denetim;
     * iOS'ta daha önce kapanmıştı).
     *
     * @return yazılan kayıt sayısı; çıpa sayılmaz (kullanıcı onu listede görmez)
     */
    suspend fun replaceAll(entries: List<DecryptedEntry>, key: SecretKey, tagDefs: Map<String, TagDef> = emptyMap()): Int {
        val (kayitlar, capa) = capayiAyir(entries)
        if (kayitlar.isEmpty()) return 0
        val resolve = tagNameResolver(kayitlar, tagDefs, key)
        val hazir = (kayitlar + listOfNotNull(capa)).map { it.toEntity(key, resolve(it)) }
        db.withTransaction {
            dao.deleteAll()
            // Eski kasanın kullanım günlüğü de gitmeli: kayıtlar silinince
            // olaylar sahipsiz kalıyordu (denetim). delete() bunu tek kayıt
            // için yapıyor; 'tümünü değiştir' hepsini yapmalı.
            usageLogDao.clear()
            usageLogDao.clearBuffer()
            dao.insertAll(hazir)
        }
        return kayitlar.size
    }

    /**
     * İçe aktarma: yedektekileri mevcut kayıtların yanına ekler.
     *
     * Çıpa eklenmez, yerine yazılır: kasadakinden yeniyse onun yerine geçer,
     * değilse alınmaz. Eskiden ikinci bir çıpa satırı açılıyor, çıpa ekranı
     * eskisini göstermeye devam ediyordu. Çıpadan başka kayıt yoksa zarf
     * boştur ([replaceAll] ile aynı kural).
     *
     * @return eklenen kayıt sayısı; çıpa sayılmaz
     */
    suspend fun addAll(entries: List<DecryptedEntry>, key: SecretKey, tagDefs: Map<String, TagDef> = emptyMap()): Int {
        val (kayitlar, capa) = capayiAyir(entries)
        if (kayitlar.isEmpty()) return 0
        val resolve = tagNameResolver(kayitlar, tagDefs, key)
        val hazir = kayitlar.map { it.toEntity(key, resolve(it)) }
        val capaSatiri = capa?.toEntity(key, emptyList())
        db.withTransaction {
            dao.insertAll(hazir)
            if (capaSatiri != null) {
                val mevcut = dao.getAnchor()
                when {
                    mevcut == null -> dao.insert(capaSatiri)
                    capaSatiri.updatedAt >= mevcut.updatedAt -> dao.update(capaSatiri.copy(id = mevcut.id))
                }
            }
        }
        return kayitlar.size
    }

    /**
     * Yedekteki etiket ADLARINI bu cihazın id'lerine çözer; olmayan etiket
     * oluşturulur (id'ler cihaza özgüdür, yedek ad taşır). Renk ve ikon
     * yedekteki tanımdan ([BackupManager] tagDefs), renk yoksa paletten gelir.
     */
    private suspend fun tagNameResolver(
        entries: List<DecryptedEntry>,
        tagDefs: Map<String, TagDef>,
        key: SecretKey
    ): (DecryptedEntry) -> List<Long> {
        val wanted = entries.flatMap { it.tagNames }.distinct()
        if (wanted.isEmpty()) return { it.tagIds }
        val byName = getAllTags(key).associateBy { it.name.lowercase() }.toMutableMap()
        wanted.forEach { name ->
            if (name.lowercase() !in byName) {
                val def = tagDefs[name]
                val color = def?.color ?: TagPalette.colorFor(byName.size)
                val icon = TagIcons.normalize(def?.icon)
                val id = tagDao.insert(
                    KayitBicimi.etiketiMuhurle(TagEntity(name = "", color = color, icon = icon), name, key)
                )
                byName[name.lowercase()] = TagEntity(id, name, color, icon)
            }
        }
        return { e -> e.tagNames.mapNotNull { byName[it.lowercase()]?.id } }
    }

    /** Etiketler, adları çözülmüş ve ada göre sıralı. */
    suspend fun getAllTags(key: SecretKey): List<TagEntity> = acikEtiketler(tagDao.getAll(), key)

    // ---- Yardımcılar ----

    /**
     * Hızlı erişim kopyası: kaydın aynı düz metni (başlık dahil), Keystore
     * anahtarıyla ve satırına bağlı. Keystore erişilemiyorsa null döner;
     * kayıt o an kilitliyken görünmez olur.
     */
    private fun quickCopy(plain: ByteArray, quick: Boolean, uid: String, type: String): ByteArray? {
        if (!quick) return null
        val quickKey = keys.quickKey() ?: return null
        return Crypto.encryptWithGeneratedIv(quickKey, plain, KayitBicimi.hizliAad(uid, type))
    }

    /** Satırı verilen başlık ve veriyle sürüm 2 olarak yeniden mühürler. */
    private fun muhurle(
        e: EntryEntity,
        title: String,
        data: EntryData,
        key: SecretKey,
        quick: Boolean = e.quick
    ): EntryEntity {
        val uid = e.uid.ifEmpty { KayitBicimi.yeniUid() }
        val plain = KayitBicimi.acikMetin(title, data)
        return e.copy(
            uid = uid,
            title = "",
            quick = quick,
            blob = Crypto.encrypt(key, plain, KayitBicimi.govdeAad(uid, e.type, e.anchor)),
            quickBlob = quickCopy(plain, quick, uid, e.type)
        )
    }

    private fun EntryEntity.toDecrypted(title: String, data: EntryData): DecryptedEntry {
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
        val plain = KayitBicimi.acikMetin(title, data)
        checkSize(title, plain)
        val uid = KayitBicimi.yeniUid()
        return EntryEntity(
            type = type.name,
            title = "",
            blob = Crypto.encrypt(key, plain, KayitBicimi.govdeAad(uid, type.name, anchor)),
            createdAt = createdAt,
            updatedAt = updatedAt,
            quick = quick,
            quickBlob = quickCopy(plain, quick, uid, type.name),
            tags = TagIds.serialize(resolvedTagIds),
            noteKind = noteKind.name,
            sortIndex = sortIndex,
            // Çıpa bayrağı geri yüklemede de taşınmalı; yoksa yedekten dönen
            // çıpa normal bir kayda dönüşüp listede belirirdi.
            anchor = anchor,
            uid = uid
        )
    }
}
