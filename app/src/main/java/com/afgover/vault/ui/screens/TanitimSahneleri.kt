package com.afgover.vault.ui.screens

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.afgover.vault.bt.BtKurulumAdimi
import com.afgover.vault.bt.HidLayouts
import com.afgover.vault.bt.PcSistemi
import kotlin.math.PI
import kotlin.math.sin

/*
 * Tanıtım ve Bluetooth kurulum sihirbazının sahneleri. Her sahne süs değil,
 * o adımda olanın resmi: telefon nerede, bilgisayar nerede, aradan ne geçiyor.
 * Hepsi süsleyici sayılır (ekran okuyucu metni adımın kendi yazısında).
 *
 * Sistemde animasyonlar kapalıysa ("Animasyon süresi ölçeği: kapalı")
 * sahneler hareketsiz, anlamlı bir kare olarak durur.
 */

/** Sistem animasyonları açık mı? Kapalıysa sahneler sabit kare gösterir. */
@Composable
private fun hareketAcik(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
}

/** 0→1 arasında sürekli dönen zaman; hareket kapalıysa [sabit]. */
@Composable
private fun dongu(sureMs: Int, sabit: Float = 0.6f): Float {
    if (!hareketAcik()) return sabit
    val gecis = rememberInfiniteTransition(label = "sahne")
    val t by gecis.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(sureMs, easing = LinearEasing)),
        label = "t"
    )
    return t
}

/** Sahnelerin ortak çerçevesi: yumuşak zemin, sabit yükseklik. */
@Composable
fun SahneCercevesi(modifier: Modifier = Modifier, icerik: @Composable () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .height(170.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
    ) { icerik() }
}

@Composable
private fun BuyukIkon(ikon: ImageVector, modifier: Modifier = Modifier, boyut: Int = 56) {
    Icon(
        ikon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier.size(boyut.dp)
    )
}

/** Merkezden dışa doğru genişleyip sönen halkalar (yayın / sinyal). */
@Composable
private fun Halkalar(t: Float, modifier: Modifier = Modifier, adet: Int = 3) {
    val renk = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val enBuyuk = size.minDimension / 2f
        repeat(adet) { i ->
            val faz = (t + i.toFloat() / adet) % 1f
            drawCircle(
                color = renk,
                radius = enBuyuk * (0.35f + 0.65f * faz),
                alpha = (1f - faz) * 0.6f,
                style = Stroke(width = 3.dp.toPx())
            )
        }
    }
}

/** İki nokta arasında akan noktalar: telefondan bilgisayara giden veri. */
@Composable
private fun AkanNoktalar(t: Float, modifier: Modifier = Modifier, kesik: Boolean = true) {
    val renk = MaterialTheme.colorScheme.primary
    val soluk = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val y = size.height / 2f
        // Yol: kesikli (henüz bağlantı yok) ya da düz (bağlı).
        if (kesik) {
            var x = 0f
            while (x < size.width) {
                drawLine(soluk, Offset(x, y), Offset((x + 8.dp.toPx()).coerceAtMost(size.width), y), 3.dp.toPx())
                x += 16.dp.toPx()
            }
        } else {
            drawLine(soluk, Offset(0f, y), Offset(size.width, y), 3.dp.toPx())
        }
        repeat(3) { i ->
            val faz = (t + i / 3f) % 1f
            drawCircle(renk, radius = 5.dp.toPx(), center = Offset(size.width * faz, y), alpha = sin(faz * PI).toFloat())
        }
    }
}

private fun PcSistemi?.bilgisayarIkonu(): ImageVector =
    if (this == PcSistemi.WINDOWS || this == PcSistemi.LINUX) Icons.Filled.DesktopWindows else Icons.Filled.Laptop

/**
 * Sihirbazın her adımının sahnesi.
 *
 * @param eslesti eşleştirme adımında eşleşme algılandı mı (kod balonu çıkar)
 */
@Composable
fun BtKurulumSahnesi(adim: BtKurulumAdimi, sistem: PcSistemi?, eslesti: Boolean = false) {
    SahneCercevesi {
        when (adim) {
            BtKurulumAdimi.SISTEM -> SistemSahnesi()
            BtKurulumAdimi.HAZIRLIK -> HazirlikSahnesi()
            BtKurulumAdimi.ESLESTIRME -> EslestirmeSahnesi(sistem, eslesti)
            BtKurulumAdimi.DENEME -> YazmaSahnesi(sistem)
            BtKurulumAdimi.BITTI -> BittiSahnesi(sistem)
            BtKurulumAdimi.DESTEKLENMIYOR -> BuyukIkon(Icons.Filled.Bluetooth, Modifier.alpha(0.4f))
        }
    }
}

/** Dizüstü ve masaüstü sırayla belirir: "bilgisayarın hangisi?" */
@Composable
private fun SistemSahnesi() {
    val t = dongu(3000)
    Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
        BuyukIkon(Icons.Filled.Laptop, Modifier.alpha(0.35f + 0.65f * sin(t * PI).toFloat()))
        BuyukIkon(Icons.Filled.DesktopWindows, Modifier.alpha(0.35f + 0.65f * sin(((t + 0.5f) % 1f) * PI).toFloat()))
    }
}

/** Telefon kendini klavye olarak duyuruyor: Bluetooth halkaları. */
@Composable
private fun HazirlikSahnesi() {
    val t = dongu(1800)
    Box(contentAlignment = Alignment.Center) {
        Halkalar(t, Modifier.size(150.dp))
        BuyukIkon(Icons.Filled.Smartphone)
        Icon(
            Icons.Filled.Bluetooth,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(MaterialTheme.colorScheme.primary)
                .padding(3.dp)
        )
    }
}

/** Telefon sinyal yayıyor, bilgisayar arıyor; eşleşince iki tarafta aynı kod. */
@Composable
private fun EslestirmeSahnesi(sistem: PcSistemi?, eslesti: Boolean) {
    val t = dongu(1600)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 24.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (eslesti) KodBalonu()
            Box(contentAlignment = Alignment.Center) {
                if (!eslesti) Halkalar(t, Modifier.size(96.dp), adet = 2)
                BuyukIkon(Icons.Filled.Smartphone)
            }
        }
        AkanNoktalar(t, Modifier.weight(1f).height(24.dp).padding(horizontal = 8.dp), kesik = !eslesti)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (eslesti) KodBalonu()
            BuyukIkon(sistem.bilgisayarIkonu())
        }
    }
}

/** Eşleşmede iki ekranda da görünen kodun temsili (gerçek kod değil). */
@Composable
private fun KodBalonu() {
    Text(
        "••• •••",
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
    Spacer(Modifier.height(4.dp))
}

/**
 * Tuşlar telefondan bilgisayara akar, bilgisayarın ekranında metin harf
 * harf belirir. Gösterilen metin gerçek deneme yazısının başıdır.
 */
@Composable
private fun YazmaSahnesi(sistem: PcSistemi?) {
    val t = dongu(4000)
    val metin = HidLayouts.LAYOUT_TEST_TEXT.take(14)
    val gorunen = metin.take((metin.length * t).toInt().coerceIn(0, metin.length))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 24.dp)
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            BuyukIkon(Icons.Filled.Smartphone)
            Icon(
                Icons.Filled.Keyboard,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(2.dp)
            )
        }
        UcanTuslar(t, Modifier.weight(1f).height(40.dp).padding(horizontal = 8.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                gorunen.ifEmpty { " " },
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                maxLines = 1,
                modifier = Modifier
                    .width(110.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
            Spacer(Modifier.height(4.dp))
            BuyukIkon(sistem.bilgisayarIkonu(), boyut = 48)
        }
    }
}

/** Aradan geçen küçük tuş kareleri. */
@Composable
private fun UcanTuslar(t: Float, modifier: Modifier) {
    val yogunluk = LocalDensity.current
    BoxWithConstraints(modifier, contentAlignment = Alignment.CenterStart) {
        val genislikPx = with(yogunluk) { maxWidth.toPx() }
        repeat(3) { i ->
            val faz = ((t * 3f) + i / 3f) % 1f
            Box(
                Modifier
                    .graphicsLayer {
                        translationX = (genislikPx - 18.dp.toPx()) * faz
                        alpha = sin(faz * PI).toFloat()
                    }
                    .size(18.dp)
                    .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
            )
        }
    }
}

@Composable
private fun BittiSahnesi(sistem: PcSistemi?) {
    val t = dongu(1500, sabit = 1f)
    // Bir kez büyüyüp yerine oturan onay; döngünün geri kalanında sabit.
    val olcek = (t * 3f).coerceAtMost(1f)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        BuyukIkon(Icons.Filled.Smartphone, boyut = 44)
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(64.dp)
                .graphicsLayer { scaleX = 0.6f + 0.4f * olcek; scaleY = 0.6f + 0.4f * olcek }
        )
        BuyukIkon(sistem.bilgisayarIkonu(), boyut = 44)
    }
}

/** Genel tanıtımın sayfaları; her biri kendi sahnesiyle. */
enum class TanitimSayfasi { KASA, KAYITLAR, KLAVYE, BLUETOOTH, YEDEK }

@Composable
fun TanitimSahnesi(sayfa: TanitimSayfasi) {
    SahneCercevesi {
        when (sayfa) {
            TanitimSayfasi.KASA -> KasaSahnesi()
            TanitimSayfasi.KAYITLAR -> KayitlarSahnesi()
            TanitimSayfasi.KLAVYE -> KlavyeSahnesi()
            TanitimSayfasi.BLUETOOTH -> YazmaSahnesi(null)
            TanitimSayfasi.YEDEK -> YedekSahnesi()
        }
    }
}

/** Kilit ve çevresinde dönen halkalar; köşede "internet yok". */
@Composable
private fun KasaSahnesi() {
    val t = dongu(2200)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Halkalar(t, Modifier.size(150.dp))
        BuyukIkon(Icons.Filled.Lock, boyut = 60)
        Icon(
            Icons.Filled.CloudOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(14.dp)
                .size(26.dp)
        )
    }
}

/** Dört kayıt türü sırayla belirir. */
@Composable
private fun KayitlarSahnesi() {
    val t = dongu(3200, sabit = 1f)
    val ikonlar = listOf(
        Icons.Filled.Key, Icons.Filled.Badge, Icons.Filled.CreditCard, Icons.AutoMirrored.Filled.StickyNote2
    )
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        ikonlar.forEachIndexed { i, ikon ->
            // Her ikon sırası gelince belirir, döngünün sonunda hepsi birlikte söner.
            val gorunurluk = ((t * 5f) - i).coerceIn(0f, 1f) * (if (t > 0.9f) (1f - t) * 10f else 1f)
            BuyukIkon(
                ikon,
                Modifier.graphicsLayer {
                    alpha = gorunurluk
                    translationY = (1f - gorunurluk) * 24f
                },
                boyut = 44
            )
        }
    }
}

/** Klavyeden alana doğrudan yazım: alan noktalarla dolar, panoya uğramaz. */
@Composable
private fun KlavyeSahnesi() {
    val t = dongu(3000)
    val nokta = (t * 10).toInt().coerceAtMost(8)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "•".repeat(nokta).ifEmpty { " " },
            fontFamily = FontFamily.Monospace,
            fontSize = 18.sp,
            modifier = Modifier
                .width(170.dp)
                .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        )
        Spacer(Modifier.height(14.dp))
        BuyukIkon(Icons.Filled.Keyboard, boyut = 52)
    }
}

/** Şifreli dosya telefondan karta/klasöre gider. */
@Composable
private fun YedekSahnesi() {
    val t = dongu(2600)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 32.dp)
    ) {
        BuyukIkon(Icons.Filled.Smartphone, boyut = 48)
        BoxWithConstraints(Modifier.weight(1f).height(48.dp), contentAlignment = Alignment.CenterStart) {
            val genislik = maxWidth
            val yogunluk = LocalDensity.current
            Box(
                Modifier.graphicsLayer {
                    translationX = with(yogunluk) { (genislik - 32.dp).toPx() } * t
                    alpha = sin(t * PI).toFloat()
                }
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.InsertDriveFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(top = 6.dp)
                        .size(14.dp)
                )
            }
        }
        BuyukIkon(Icons.Filled.SdCard, boyut = 48)
    }
}
