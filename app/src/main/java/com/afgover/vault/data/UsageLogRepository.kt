package com.afgover.vault.data

import android.content.Context
import com.afgover.vault.core.Crypto
import com.afgover.vault.core.KeyManager
import com.afgover.vault.core.VaultSession
import javax.crypto.SecretKey

/**
 * Kullanım günlüğü: hangi kaydın hangi alanı, ne zaman, nereye gitti.
 *
 * Yazma yolu kasanın durumuna göre ikiye ayrılır:
 * - **Kilit açık:** olay dataKey ile şifrelenip `usage_log`'a yazılır.
 * - **Kilitli:** dataKey bellekte olmadığı için olay Keystore anahtarıyla
 *   `usage_buffer`'a yazılır; ilk kilit açılışında [foldBuffer] onu günlüğe
 *   taşır. Keystore anahtarı da yoksa olay kaydedilemez — bu boşluk arayüzde
 *   yazılıdır, sessizce "kayıt tam" gibi gösterilmez.
 *
 * Mühürler tablolarına AAD ile bağlıdır ([KayitBicimi.GUNLUK_AAD],
 * [KayitBicimi.TAMPON_AAD]): bir kaydın gövdesi günlüğe, günlük satırı
 * kayıtlara taşınamaz. Eski (AAD'siz) satırlar okunmaya devam eder; kilit ilk
 * açıldığında [VaultRepository.upgradeStorage] onları yeniden mühürler.
 *
 * Günlüğün göremedikleri (kasıtlı ve bilinen): ekrandan okunan, fotoğrafı
 * çekilen ya da elle yazılan değerler; Android'in otomatik doldurmasında
 * kullanıcının hangi kaydı seçtiği (seçilen `Dataset` servise geri dönmüyor).
 */
class UsageLogRepository(
    context: Context,
    private val keys: KeyManager
) {

    companion object {
        /** Kayıt başına saklanan en fazla olay; aşınca en eskisi düşer. */
        const val MAX_PER_ENTRY = 50

        /** Toplam üst sınır; süpürme yalnız bu aşıldığında koşar. */
        const val MAX_EVENTS = 2_000

        /** Kilitliyken biriken tamponun üst sınırı. */
        const val MAX_BUFFERED = 200
    }

    private val dao = VaultDatabase.get(context).usageLogDao()

    /**
     * Olayı kaydeder. Günlük tutulamaması hiçbir zaman asıl işi (kopyalama,
     * yazma) engellemez; başarısızlık `false` döner ve çağıran devam eder.
     */
    suspend fun record(event: UsageEvent): Boolean {
        val key = VaultSession.key()
        return try {
            if (key != null) {
                dao.insert(UsageLogEntity(blob = Crypto.encrypt(key, event.toBytes(), KayitBicimi.GUNLUK_AAD)))
                if (dao.count() > MAX_EVENTS) prune(key)
                true
            } else {
                val quickKey = keys.quickKey() ?: return false
                dao.insertBuffered(
                    UsageBufferEntity(
                        blob = Crypto.encryptWithGeneratedIv(quickKey, event.toBytes(), KayitBicimi.TAMPON_AAD)
                    )
                )
                pruneBuffer()
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Kilit açılışında çağrılır: tampondaki olayları dataKey ile yeniden
     * şifreleyip günlüğe taşır ve tamponu boşaltır. Çözülemeyen satır (Keystore
     * anahtarı değişmişse) atılır — okunamayan bir olayı taşımanın anlamı yok.
     */
    suspend fun foldBuffer(key: SecretKey): Int {
        val buffered = dao.getBuffered()
        if (buffered.isEmpty()) return 0
        val quickKey = keys.quickKey()
        var moved = 0
        buffered.forEach { row ->
            val plain = quickKey?.let { KayitBicimi.gunluguAc(it, row.blob, KayitBicimi.TAMPON_AAD) }
            if (plain != null && UsageEvent.fromBytes(plain) != null) {
                dao.insert(UsageLogEntity(blob = Crypto.encrypt(key, plain, KayitBicimi.GUNLUK_AAD)))
                moved++
            }
        }
        // Yalnız BU turda okunan satırları sil: getBuffered() ile silme arasında
        // kilitliyken yeni bir olay eklenebilir; clearBuffer() onu taşınmadan
        // silerdi (denetim: yarış). id listesiyle silmek o pencereyi kapatır.
        dao.deleteBufferedByIds(buffered.map { it.id })
        if (dao.count() > MAX_EVENTS) prune(key)
        return moved
    }

    /** Tüm günlük, en yeni önce. Kilit açık değilse boş döner. */
    suspend fun events(key: SecretKey): List<UsageEvent> =
        dao.getAll().mapNotNull { row ->
            ac(key, row.blob)?.let { UsageEvent.fromBytes(it) }
        }

    /** Tek kaydın olayları, en yeni önce. */
    suspend fun eventsFor(entryId: Long, key: SecretKey): List<UsageEvent> =
        events(key).filter { it.entryId == entryId }

    /** Kilitliyken bekleyen olay sayısı — arayüz "günlük tam değil" diyebilsin. */
    suspend fun pendingCount(): Int = try {
        dao.bufferCount()
    } catch (e: Exception) {
        0
    }

    /** Kayıt silinince günlüğü de silinir: sahibi olmayan olay artık okunamaz. */
    suspend fun deleteFor(entryId: Long, key: SecretKey) {
        val ids = dao.getAll().filter { row ->
            ac(key, row.blob)
                ?.let { UsageEvent.fromBytes(it) }
                ?.entryId == entryId
        }.map { it.id }
        if (ids.isNotEmpty()) dao.deleteByIds(ids)
    }

    /** Kullanıcı isteğiyle: günlüğün tamamı (tampon dahil) silinir. */
    suspend fun clearAll() {
        dao.clear()
        dao.clearBuffer()
    }

    /**
     * Kayıt başına [MAX_PER_ENTRY] olayı korur; hâlâ [MAX_EVENTS] üstündeyse
     * en eski satırları atar. Süpürme her yazmada değil yalnız tavan aşılınca
     * koşar — tarama tüm satırları çözmeyi gerektiriyor.
     */
    private suspend fun prune(key: SecretKey) {
        val rows = dao.getAll() // en yeni önce
        val perEntry = mutableMapOf<Long, Int>()
        val doomed = mutableListOf<Long>()
        rows.forEach { row ->
            val event = ac(key, row.blob)?.let { UsageEvent.fromBytes(it) }
            if (event == null) {
                doomed.add(row.id) // çözülemeyen satır günlüğü büyütmekten başka iş görmez
                return@forEach
            }
            val seen = (perEntry[event.entryId] ?: 0) + 1
            perEntry[event.entryId] = seen
            if (seen > MAX_PER_ENTRY) doomed.add(row.id)
        }
        val kalan = rows.size - doomed.size
        if (kalan > MAX_EVENTS) {
            // En eskiler listenin sonunda; gerekli kadarını oradan al.
            rows.asReversed()
                .asSequence()
                .map { it.id }
                .filter { it !in doomed }
                .take(kalan - MAX_EVENTS)
                .forEach { doomed.add(it) }
        }
        if (doomed.isNotEmpty()) dao.deleteByIds(doomed)
    }

    /** Günlük satırı: önce AAD'li (sürüm 2), olmazsa eski biçim. */
    private fun ac(key: SecretKey, blob: ByteArray): ByteArray? =
        KayitBicimi.gunluguAc(key, blob, KayitBicimi.GUNLUK_AAD)

    private suspend fun pruneBuffer() {
        val rows = dao.getBuffered() // en eski önce
        val fazla = rows.size - MAX_BUFFERED
        if (fazla > 0) dao.deleteBufferedByIds(rows.take(fazla).map { it.id })
    }
}
