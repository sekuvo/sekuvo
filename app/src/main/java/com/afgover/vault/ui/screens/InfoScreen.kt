package com.afgover.vault.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.afgover.vault.BuildConfig
import com.afgover.vault.R

/** Ayarlardan açılan bilgi sayfaları. */
enum class InfoKind { ABOUT, PRIVACY, GUIDE }

/**
 * Hakkında / Gizlilik / Rehber.
 *
 * Bu içerik sitede de var ama uygulamanın İÇİNDE olması bir tercih değil,
 * tutarlılık meselesi: Sekuvo'nun `INTERNET` izni yok ve bütün vaadi çevrimdışı
 * çalışmak. Gizlilik politikasını okumak için tarayıcı açtıran bir uygulama
 * kendi iddiasıyla çelişirdi. Metinler kaynaklarda tutuluyor, yani uygulama
 * diliyle birlikte çevriliyor.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoScreen(kind: InfoKind, onBack: () -> Unit) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            when (kind) {
                                InfoKind.ABOUT -> R.string.info_about_title
                                InfoKind.PRIVACY -> R.string.info_privacy_title
                                InfoKind.GUIDE -> R.string.info_guide_title
                            }
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            when (kind) {
                InfoKind.ABOUT -> AboutBody(
                    onOpen = { url ->
                        // INTERNET izni gerektirmez: adresi tarayıcı açar.
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                    }
                )
                InfoKind.PRIVACY -> Bolumler(gizlilikBolumleri())
                InfoKind.GUIDE -> Bolumler(rehberBolumleri())
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Başlık + gövde çiftlerini çerçeveli kartlar hâlinde dizer. */
@Composable
private fun Bolumler(bolumler: List<Pair<Int, Int>>) {
    bolumler.forEach { (baslikRes, govdeRes) ->
        OutlinedCard(
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    stringResource(baslikRes),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
                Text(stringResource(govdeRes), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun AboutBody(onOpen: (String) -> Unit) {
    OutlinedCard(
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.unlock_slogan),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(10.dp))
            SatirBilgi(R.string.info_about_version, BuildConfig.VERSION_NAME)
            SatirBilgi(R.string.info_about_package, BuildConfig.APPLICATION_ID)
            SatirBilgi(R.string.info_about_license, "GPLv3")
            SatirBilgi(R.string.info_about_developer, "Ahmet Govercile")
        }
    }
    Spacer(Modifier.height(10.dp))

    Bolumler(listOf(R.string.info_about_what_title to R.string.info_about_what_body))

    OutlinedButton(
        onClick = { onOpen("https://sekuvo.com") },
        modifier = Modifier.fillMaxWidth()
    ) { Text(stringResource(R.string.info_about_website)) }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = { onOpen("https://github.com/sekuvo/sekuvo") },
        modifier = Modifier.fillMaxWidth()
    ) { Text(stringResource(R.string.info_about_source)) }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = { onOpen("mailto:contact@sekuvo.com") },
        modifier = Modifier.fillMaxWidth()
    ) { Text(stringResource(R.string.info_about_contact)) }
}

@Composable
private fun SatirBilgi(labelRes: Int, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            stringResource(labelRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(0.42f)
        )
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

private fun gizlilikBolumleri() = listOf(
    R.string.privacy_s1_title to R.string.privacy_s1_body,
    R.string.privacy_s2_title to R.string.privacy_s2_body,
    R.string.privacy_s3_title to R.string.privacy_s3_body,
    R.string.privacy_s4_title to R.string.privacy_s4_body,
    R.string.privacy_s5_title to R.string.privacy_s5_body,
    R.string.privacy_s6_title to R.string.privacy_s6_body,
)

private fun rehberBolumleri() = listOf(
    R.string.guide_s1_title to R.string.guide_s1_body,
    R.string.guide_s2_title to R.string.guide_s2_body,
    R.string.guide_s3_title to R.string.guide_s3_body,
    R.string.guide_s4_title to R.string.guide_s4_body,
    R.string.guide_s5_title to R.string.guide_s5_body,
    R.string.guide_s6_title to R.string.guide_s6_body,
    R.string.guide_s7_title to R.string.guide_s7_body,
    R.string.guide_s8_title to R.string.guide_s8_body,
)
