package com.afgover.vault.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface EntryDao {

    /**
     * Listenin ve klavyenin gördüğü küme: araç çıpası HARİÇ. Çıpa kasa
     * içeriği değil, indirdiğin aracın kimlik ölçüsüdür; kendi ekranından
     * açılır. Yedek ise [getAll] kullanır — çıpa da yedeğe girmeli.
     *
     * Başlık şifreli olduğu için (biçim sürüm 2) sıra burada değil, çözülmüş
     * başlıkla bellekte kurulur ([EntrySort]).
     */
    @Query("SELECT * FROM entries WHERE anchor = 0 ORDER BY id")
    fun observeVisible(): Flow<List<EntryEntity>>

    @Query("SELECT * FROM entries WHERE anchor = 0 ORDER BY id")
    suspend fun getVisible(): List<EntryEntity>

    /**
     * Kasadaki tek araç çıpası; henüz kurulmadıysa null. Eski sürümlerin
     * "ekle" ile içe aktarmada açtığı fazladan çıpa satırları olabilir: en
     * yenisi döner (çıpa ekranı ve güncelleme onu kullanır).
     */
    @Query("SELECT * FROM entries WHERE anchor = 1 ORDER BY updatedAt DESC, id DESC LIMIT 1")
    suspend fun getAnchor(): EntryEntity?

    /** Yedek ve toplam sayım için: çıpa dahil HER ŞEY. */
    @Query("SELECT * FROM entries ORDER BY id")
    suspend fun getAll(): List<EntryEntity>

    @Query("SELECT * FROM entries WHERE id = :id")
    suspend fun getById(id: Long): EntryEntity?

    /** Kasa kilitliyken klavyenin görebildiği tek küme. */
    @Query("SELECT * FROM entries WHERE quick = 1 AND anchor = 0 ORDER BY id")
    suspend fun getQuick(): List<EntryEntity>

    @Insert
    suspend fun insert(entry: EntryEntity): Long

    @Insert
    suspend fun insertAll(entries: List<EntryEntity>)

    @Update
    suspend fun update(entry: EntryEntity)

    @Update
    suspend fun updateAll(entries: List<EntryEntity>)

    @Query("DELETE FROM entries WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM entries")
    suspend fun deleteAll()
}
