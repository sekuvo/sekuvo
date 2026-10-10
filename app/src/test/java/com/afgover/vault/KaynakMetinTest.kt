package com.afgover.vault

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Çeviri dosyalarının derleyicide ya da ekranda SESSİZCE bozulan iki tuzağı.
 * İkisi de derlemeyi kırmaz, uygulamada fark edilmeden yanlış görünür; bu
 * yüzden dosyaların kendisi denetlenir.
 *
 * 1. Kaçışsız düz çift tırnak (`"`): aapt2 onu metnin parçası değil alıntı
 *    işareti sayar ve siler. Almanca, Endonezce, Pidgin ve Vietnamcada 17'şer
 *    metin bu yüzden tırnaksız görünüyordu (ör. `„Ad ist zu groß` — kapanış
 *    yok). Ya `\"` ile kaçışlanmalı ya da dilin kendi tırnağı kullanılmalı.
 * 2. Sağdan sola dillerde (Arapça, Urdu) noktayla başlayan Latin parça
 *    (`.vaultbak`): baştaki nokta yön kuralları gereği parçanın sonuna kayar,
 *    ekranda `vaultbak.` görünür. Parça U+2068…U+2069 yön yalıtımıyla
 *    sarılmalı (kaynakta `\u2068` … `\u2069`).
 */
class KaynakMetinTest {

    private val resKlasoru: File = listOf(File("src/main/res"), File("app/src/main/res"))
        .firstOrNull { it.isDirectory } ?: error("res klasörü bulunamadı (çalışma dizini: ${File(".").absolutePath})")

    private val metinDosyalari: List<File> =
        resKlasoru.listFiles { f -> f.isDirectory && f.name.startsWith("values") }.orEmpty()
            .mapNotNull { File(it, "strings.xml").takeIf(File::isFile) }
            .sortedBy { it.parentFile!!.name }

    @Test
    fun `dosyalar bulundu - denetim bos calismiyor`() {
        // Klasör yanlış çözülürse aşağıdaki testler hiçbir şey denetlemeden geçerdi.
        assertTrue("Yalnız ${metinDosyalari.size} strings.xml bulundu", metinDosyalari.size >= 16)
    }

    @Test
    fun `hicbir dilde kacissiz duz cift tirnak yok`() {
        val sorunlar = metinDosyalari.flatMap { dosya ->
            kacissizTirnaklar(dosya.readText()).map { "${dosya.parentFile!!.name}: $it" }
        }
        if (sorunlar.isNotEmpty()) {
            fail("Kaçışsız düz tırnak derlemede silinir — \\\" ya da dilin tırnağını kullan:\n" +
                sorunlar.joinToString("\n"))
        }
    }

    @Test
    fun `sagdan sola dillerde noktayla baslayan latin parca yalitilmis`() {
        val sorunlar = metinDosyalari
            .filter { it.parentFile!!.name in SAGDAN_SOLA }
            .flatMap { dosya -> yalitilmamisNoktalar(dosya.readText()).map { "${dosya.parentFile!!.name}: $it" } }
        if (sorunlar.isNotEmpty()) {
            fail("Sağdan sola metinde nokta parçanın sonuna kayar — \\u2068…\\u2069 ile sar:\n" +
                sorunlar.joinToString("\n"))
        }
    }

    @Test
    fun `denetimler gercek sorunu yakalar, dogru yazimi gecirir`() {
        assertEquals(listOf("a"), kacissizTirnaklar("""<string name="a">„%1${'$'}s" zu groß</string>"""))
        assertTrue(kacissizTirnaklar("""<string name="b">\"x\" ve “y” ve „z“</string>""").isEmpty())
        assertEquals(listOf("c"), yalitilmamisNoktalar("""<string name="c">نسخة .vaultbak هنا</string>"""))
        // Kaynaktaki kaçış dizisi biçimi ve karakterin kendisi:
        assertTrue(yalitilmamisNoktalar("<string name=\"d\">نسخة \\u2068.vaultbak\\u2069 و aktar.html</string>").isEmpty())
        assertTrue(yalitilmamisNoktalar("<string name=\"e\">نسخة \u2068.vaultbak\u2069 هنا</string>").isEmpty())
    }

    companion object {
        private val SAGDAN_SOLA = setOf("values-ar", "values-ur")
        private val ETIKET = Regex("""<(string|item)\b([^>]*)>(.*?)</\1>""", RegexOption.DOT_MATCHES_ALL)
        private val AD = Regex("""name="([^"]*)"""")
        private val IC_ETIKET = Regex("""<[^>]+>""")
        private val KACISSIZ_TIRNAK = Regex("""(?<!\\)"""")
        /**
         * Harf/rakamdan sonra gelmeyen ve yalıtılmamış ".harf" — `.vaultbak` evet, `aktar.html` hayır.
         * Yalıtım kaynakta kaçış dizisi (`\u2068`) ya da karakterin kendisi olabilir; ikisi de sayılır.
         */
        private val YALITILMAMIS_NOKTA = Regex("""(?<!\\u2068)(?<!\x{2068})(?<![A-Za-z0-9])\.[A-Za-z]""")

        private fun degerler(xml: String): Sequence<Pair<String, String>> =
            ETIKET.findAll(xml).map { m ->
                val ad = AD.find(m.groupValues[2])?.groupValues?.get(1) ?: "(item)"
                ad to m.groupValues[3].replace(IC_ETIKET, "")
            }

        fun kacissizTirnaklar(xml: String): List<String> =
            degerler(xml).filter { (_, v) -> KACISSIZ_TIRNAK.containsMatchIn(v) }.map { it.first }.toList()

        fun yalitilmamisNoktalar(xml: String): List<String> =
            degerler(xml).filter { (_, v) -> YALITILMAMIS_NOKTA.containsMatchIn(v) }.map { it.first }.toList()
    }
}
