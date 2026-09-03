package com.afgover.vault.data

import android.content.Context
import androidx.annotation.StringRes
import com.afgover.vault.R

/**
 * Kayıt sıralaması. Kullanıcı seçer; hem Vault Klavyesi hem ana liste aynı
 * sırayı kullanır — klavyede aradığını bulmak için kurulan alışkanlık
 * uygulamada da geçerli olsun diye.
 *
 * Tercih düz bir ayar dosyasında tutulur (sır değil): klavye ayrı bir süreç
 * gibi davranabildiği için Room'a değil `SharedPreferences`'a yazılır.
 */
enum class EntrySort(@StringRes val labelRes: Int) {
    TITLE_ASC(R.string.sort_title_asc),
    TITLE_DESC(R.string.sort_title_desc),
    UPDATED_DESC(R.string.sort_updated_desc),
    CREATED_DESC(R.string.sort_created_desc),
    TYPE(R.string.sort_type),
    MANUAL(R.string.sort_manual);

    companion object {
        private const val PREFS = "vault_settings"
        private const val KEY = "entry_sort"

        fun read(context: Context): EntrySort {
            val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, null) ?: return TITLE_ASC
            return runCatching { valueOf(name) }.getOrDefault(TITLE_ASC)
        }

        fun write(context: Context, sort: EntrySort) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, sort.name).apply()
        }
    }
}

/** Çözülmüş kayıtları seçili sıraya göre dizer (klavye). */
fun List<DecryptedEntry>.sortedBy(sort: EntrySort): List<DecryptedEntry> = when (sort) {
    EntrySort.TITLE_ASC -> sortedBy { it.title.lowercase() }
    EntrySort.TITLE_DESC -> sortedByDescending { it.title.lowercase() }
    EntrySort.UPDATED_DESC -> sortedByDescending { it.updatedAt }
    EntrySort.CREATED_DESC -> sortedByDescending { it.createdAt }
    EntrySort.TYPE -> sortedWith(compareBy({ it.type.ordinal }, { it.title.lowercase() }))
    EntrySort.MANUAL -> sortedWith(compareBy({ priorityKey(it.sortIndex) }, { it.title.lowercase() }))
}

/**
 * Öncelik sırasının tek kuralı: **numara verilmiş kayıtlar 1, 2, 3… diye
 * önce; numarasızlar en sonda** (aralarında başlığa göre).
 *
 * Numarasızlık `0` ile temsil edilir — ayrı bir "yok" durumu tutmak yerine,
 * çünkü sütun zaten `NOT NULL DEFAULT 0` ve yedekten dönen eski kayıtlar da
 * o değeri taşıyor. Sıfırı olduğu gibi sıralamak numarasız her kaydı listenin
 * BAŞINA toplardı; kullanıcının öne çıkardığı birkaç kaydın tam tersi.
 *
 * Kural burada tek kopya duruyor çünkü iki tüketicisi var: ana liste ve
 * Vault Klavyesi. İkisinin ayrışması, klavyede kurulan alışkanlığın
 * uygulamada bozulması demek olurdu.
 */
fun priorityKey(sortIndex: Int): Int = if (sortIndex <= 0) Int.MAX_VALUE else sortIndex
