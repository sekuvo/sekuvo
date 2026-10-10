package com.afgover.vault.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.afgover.vault.R
import kotlinx.coroutines.launch

/** Tanıtım sayfası: sahne + başlık + açıklama. Sıra, gösterim sırasıdır. */
private data class Sayfa(val sahne: TanitimSayfasi, val baslikRes: Int, val metinRes: Int)

private val SAYFALAR = listOf(
    Sayfa(TanitimSayfasi.KASA, R.string.tour_vault_title, R.string.tour_vault_body),
    Sayfa(TanitimSayfasi.KAYITLAR, R.string.tour_entries_title, R.string.tour_entries_body),
    Sayfa(TanitimSayfasi.KLAVYE, R.string.tour_keyboard_title, R.string.tour_keyboard_body),
    Sayfa(TanitimSayfasi.YEDEK, R.string.tour_backup_title, R.string.tour_backup_body),
    // Bluetooth sonda: tanıtımın tek "şimdi yap" adımı, doğrudan canlı
    // kurulum sihirbazına geçer.
    Sayfa(TanitimSayfasi.BLUETOOTH, R.string.tour_bt_title, R.string.tour_bt_body),
)

/**
 * Uygulamanın kısa tanıtımı. Kasa ilk kurulduğunda bir kez kendiliğinden
 * açılır; Ayarlar'dan her zaman yeniden açılabilir.
 *
 * @param onBtKurulum son sayfadaki "şimdi kur": Bluetooth sihirbazını açar
 */
@Composable
fun TanitimEkrani(onBitir: () -> Unit, onBtKurulum: () -> Unit) {
    val pager = rememberPagerState { SAYFALAR.size }
    val kapsam = rememberCoroutineScope()
    val sonSayfa = pager.currentPage == SAYFALAR.lastIndex

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (!sonSayfa) {
                    TextButton(onClick = onBitir) { Text(stringResource(R.string.tour_skip)) }
                } else {
                    Spacer(Modifier.height(48.dp))
                }
            }
            HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { i ->
                val sayfa = SAYFALAR[i]
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    TanitimSahnesi(sayfa.sahne)
                    Spacer(Modifier.height(24.dp))
                    Text(
                        stringResource(sayfa.baslikRes),
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(sayfa.metinRes),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                }
            }
            SayfaNoktalari(adet = SAYFALAR.size, secili = pager.currentPage)
            Spacer(Modifier.height(16.dp))
            if (sonSayfa) {
                Button(onClick = onBtKurulum, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.tour_bt_setup_now))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onBitir, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.tour_later))
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(
                        onClick = { kapsam.launch { pager.animateScrollToPage(pager.currentPage - 1) } },
                        enabled = pager.currentPage > 0
                    ) { Text(stringResource(R.string.tour_back)) }
                    Button(onClick = { kapsam.launch { pager.animateScrollToPage(pager.currentPage + 1) } }) {
                        Text(stringResource(R.string.tour_next))
                    }
                }
            }
        }
    }
}

@Composable
private fun SayfaNoktalari(adet: Int, secili: Int) {
    val aciklama = stringResource(R.string.setup_step_of, secili + 1, adet)
    Row(
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = aciklama }
    ) {
        repeat(adet) { i ->
            Box(
                Modifier
                    .padding(horizontal = 4.dp)
                    .size(if (i == secili) 10.dp else 8.dp)
                    .clip(CircleShape)
                    .background(
                        if (i == secili) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant
                    )
            )
        }
    }
}
