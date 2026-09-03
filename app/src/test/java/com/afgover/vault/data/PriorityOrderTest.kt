package com.afgover.vault.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Öncelik sırasının sözleşmesi. Kural bir kullanıcı isteğinden doğdu:
 * sürükleyerek sıralamak çok kayıtla kullanışsızdı, yerine kayda ait bir
 * numara geldi. Buradaki asıl sınav **numarasız** kayıtların yeri: sıfırı
 * olduğu gibi sıralamak onları listenin başına toplardı, yani kullanıcının
 * öne çıkardığı birkaç kaydın tam tersini yapardı.
 */
class PriorityOrderTest {

    private fun sirala(vararg kayit: Pair<Int, String>): List<String> =
        kayit.toList()
            .sortedWith(compareBy({ priorityKey(it.first) }, { it.second.lowercase() }))
            .map { it.second }

    @Test
    fun `numaralilar kucukten buyuge`() {
        assertEquals(listOf("a", "b", "c"), sirala(3 to "c", 1 to "a", 2 to "b"))
    }

    @Test
    fun `numarasizlar sonda ve alfabetik`() {
        assertEquals(
            listOf("banka", "elma", "zeytin"),
            sirala(0 to "zeytin", 0 to "elma", 1 to "banka")
        )
    }

    @Test
    fun `ayni numara basliga gore ayrisir`() {
        assertEquals(listOf("ali", "veli"), sirala(2 to "veli", 2 to "ali"))
    }

    /** Negatif değer arayüzden gelemez ama yedekten dönebilir: numarasız sayılır. */
    @Test
    fun `negatif deger numarasiz sayilir`() {
        assertEquals(listOf("numarali", "negatif"), sirala(-5 to "negatif", 9 to "numarali"))
    }

    @Test
    fun `numarasizlik en buyuk anahtardir`() {
        assertEquals(Int.MAX_VALUE, priorityKey(0))
        assertEquals(1, priorityKey(1))
    }
}
