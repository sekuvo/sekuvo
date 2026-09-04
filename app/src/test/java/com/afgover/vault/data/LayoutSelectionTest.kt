package com.afgover.vault.data

import com.afgover.vault.bt.HidLayouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kullanıcının ayarlardan seçtiği klavye düzenlerinin sözleşmesi
 * (S-2026-08-24-dil-ve-duzen-secimi).
 */
class LayoutSelectionTest {

    /**
     * Hiç dokunulmamış kurulumda yalnız ABD İngilizcesi açıktır (kullanıcı
     * kararı): yirmi bir düzen açık gelince liste ilk kullanımda okunmuyordu.
     * Kullanıcı listeye bir kez dokunduğunda tercihi yazılır — hepsini açmak
     * boş dizge olarak saklanır ve "hiç dokunulmamış"tan ayrılır, yoksa
     * "hepsini aç" bir sonraki açılışta tek düzene geri düşerdi.
     */
    @Test
    fun `tercih yokken yalniz US acik - dokunulmus tercih aynen korunur`() {
        assertEquals(listOf(HidLayouts.Layout.US), HidLayouts.enabledLayouts(null))
        assertEquals(HidLayouts.Layout.entries, HidLayouts.enabledLayouts(""))
    }

    /**
     * Seçili düzenin varsayılanı da US: açık liste US'e daralmışken seçim
     * başka bir düzende kalırsa, kullanıcı o düzeni sonradan açtığı anda
     * seçim kendiliğinden oraya kayardı.
     */
    @Test
    fun `secili duzen varsayilani US`() {
        assertEquals(HidLayouts.Layout.US, HidLayouts.readLayout(null))
        assertEquals(
            HidLayouts.Layout.US,
            HidLayouts.readLayout(null, HidLayouts.enabledLayouts(null))
        )
        // Eski sürümlerin "TR" adı hâlâ Türkçe Q'ya çözülür: bir kez seçilmiş
        // tercih varsayılan değişti diye kaybolmamalı.
        assertEquals(
            HidLayouts.Layout.TR_Q,
            HidLayouts.readLayout("TR", HidLayouts.Layout.entries)
        )
    }

    @Test
    fun `kapatilanlar dislanir`() {
        val acik = HidLayouts.enabledLayouts("FR,IT")
        assertFalse(HidLayouts.Layout.FR in acik)
        assertFalse(HidLayouts.Layout.IT in acik)
        assertTrue(HidLayouts.Layout.US in acik)
        assertEquals(HidLayouts.Layout.entries.size - 2, acik.size)
    }

    /**
     * Sıra her zaman enum sırasıdır: aç/kapa geçmişine göre kayan bir liste,
     * çipleri her açılışta başka sırada gösterir ve geri düşüşü değiştirirdi.
     */
    @Test
    fun `sira her zaman enum sirasi - acma kapama gecmisine gore kaymaz`() {
        val a = HidLayouts.enabledLayouts("DE")
        val b = HidLayouts.enabledLayouts("DE")
        assertEquals(a, b)
        assertEquals(a, a.sortedBy { it.ordinal })
    }

    @Test
    fun `bilinmeyen ad yok sayilir`() {
        assertEquals(HidLayouts.Layout.entries, HidLayouts.enabledLayouts("OLMAYAN,DUZEN"))
    }

    @Test
    fun `gidis donus - saklanan metin geri okunur`() {
        val kume = listOf(HidLayouts.Layout.TR_Q, HidLayouts.Layout.IT)
        assertEquals(kume, HidLayouts.enabledLayouts(HidLayouts.storeDisabled(kume)))
    }

    @Test
    fun `hicbiri acik degilse hepsine duser - aktarim ekrani kullanilamaz kalmaz`() {
        assertEquals(
            HidLayouts.Layout.entries,
            HidLayouts.enabledLayouts(HidLayouts.storeDisabled(emptyList()))
        )
    }

    /**
     * KAPATILANLAR saklanıyor: ileride eklenen bir düzen mevcut kullanıcıda
     * kendiliğinden görünmeli. İzin listesi saklansaydı gizli kalırdı.
     */
    @Test
    fun `yeni eklenen bir duzen mevcut kullanicida gorunur`() {
        // Kullanıcı yalnız FR'yi kapatmış; kalan her şey (sonradan eklenenler
        // dahil) açık kalmalı.
        val acik = HidLayouts.enabledLayouts("FR")
        assertEquals(HidLayouts.Layout.entries.filter { it != HidLayouts.Layout.FR }, acik)
    }

    /**
     * Kullanıcı seçili düzeni ayarlardan kapatırsa aktarım ekranında görünmeyen
     * bir düzen seçili kalırdı — sır yanlış düzende yazılırdı.
     */
    @Test
    fun `kapatilan duzen seciliyse acik olanlardan birine duser`() {
        val acik = listOf(HidLayouts.Layout.US, HidLayouts.Layout.DE)
        assertEquals(HidLayouts.Layout.US, HidLayouts.readLayout("FR", acik))
        assertTrue(HidLayouts.readLayout("FR", acik) in acik)
    }

    @Test
    fun `acik duzen seciliyse korunur`() {
        val acik = listOf(HidLayouts.Layout.US, HidLayouts.Layout.DE)
        assertEquals(HidLayouts.Layout.DE, HidLayouts.readLayout("DE", acik))
    }

    /** Eski sürümlerin "TR" tercihi TR-Q'ya taşınmalı (geri uyumluluk). */
    @Test
    fun `eski TR tercihi TR-Q olarak okunur`() {
        assertEquals(HidLayouts.Layout.TR_Q, HidLayouts.readLayout("TR"))
    }
}
