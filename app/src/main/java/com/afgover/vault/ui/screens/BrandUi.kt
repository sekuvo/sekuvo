package com.afgover.vault.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.afgover.vault.R
import com.afgover.vault.core.Brand
import com.afgover.vault.core.Brands
import com.afgover.vault.ui.brandLogoRes

/**
 * Kurum logosu: marka renginde kapsül; logolu markada beyaz (açık renkli
 * markada koyu) logo, logosuzda 1–3 harflik rozet. [TypeBadge] ile aynı ölçü
 * ve köşe, liste satırında onun yerine geçer.
 */
@Composable
fun BrandBadge(brand: Brand, size: Int = 42) {
    val zemin = Color(brand.color)
    val on = if (zemin.luminance() > 0.6f) Color(0xFF1B1B1B) else Color.White
    val sekil = RoundedCornerShape(size.dp / 3)
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(sekil)
            .background(zemin)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), sekil),
        contentAlignment = Alignment.Center
    ) {
        val logo = if (brand.hasLogo) brandLogoRes(brand.id) else 0
        if (logo != 0) {
            Icon(painterResource(logo), contentDescription = brand.name, tint = on, modifier = Modifier.size((size * 0.52f).dp))
        } else {
            val olcek = when (brand.badge.length) { 1 -> 0.46f; 2 -> 0.36f; else -> 0.28f }
            Text(brand.badge, color = on, fontWeight = FontWeight.Bold, fontSize = (size * olcek).sp, maxLines = 1)
        }
    }
}

/**
 * Düzenleme ekranındaki "Logo" satırı: önizleme + seçim özeti; dokununca seçici.
 * [choice]: "" otomatik, [Brands.NONE] yok, ya da katalog kimliği. [auto]:
 * otomatikte bulunan marka (başlık ve adresten, kaydedilmemiş hâliyle).
 */
@Composable
fun BrandRow(choice: String, auto: Brand?, onChoose: (String) -> Unit) {
    var acik by remember { mutableStateOf(false) }
    val secili = Brands.byId(choice)
    val gosterilen = when {
        choice == Brands.NONE -> null
        secili != null -> secili
        else -> auto
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable { acik = true }
            .padding(vertical = 6.dp)
    ) {
        if (gosterilen != null) BrandBadge(gosterilen, size = 36) else BosRozet(36)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.edit_brand_title))
            Text(
                when {
                    choice == Brands.NONE -> stringResource(R.string.edit_brand_none)
                    secili != null -> secili.name
                    auto != null -> stringResource(R.string.edit_brand_auto_found, auto.name)
                    else -> stringResource(R.string.edit_brand_auto_none)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    if (acik) BrandPickerDialog(choice, auto, onDismiss = { acik = false }) { onChoose(it); acik = false }
}

@Composable
private fun BosRozet(size: Int) {
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(size.dp / 3))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Filled.Block, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size((size * 0.5f).dp))
    }
}

/** Arama kutulu marka listesi: Otomatik, Logo yok, sonra katalog (ada göre). */
@Composable
fun BrandPickerDialog(choice: String, auto: Brand?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var arama by remember { mutableStateOf("") }
    val q = Brands.normalize(arama)
    val liste = remember(q) {
        Brands.all
            .filter { b -> q.isEmpty() || Brands.normalize(b.name).contains(q) || b.domains.any { it.contains(arama.trim().lowercase()) } }
            .sortedBy { Brands.normalize(it.name) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_brand_choose)) },
        text = {
            Column {
                OutlinedTextField(
                    value = arama, onValueChange = { arama = it }, singleLine = true,
                    label = { Text(stringResource(R.string.edit_brand_search)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    if (q.isEmpty()) {
                        item {
                            SeciciSatir(
                                secili = choice.isEmpty() || (choice != Brands.NONE && Brands.byId(choice) == null),
                                onClick = { onPick("") },
                                rozet = {
                                    if (auto != null) BrandBadge(auto, size = 32) else Box(
                                        Modifier.size(32.dp).clip(RoundedCornerShape(11.dp))
                                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                        contentAlignment = Alignment.Center
                                    ) { Icon(Icons.Filled.AutoAwesome, null, Modifier.size(16.dp)) }
                                },
                                metin = auto?.let { stringResource(R.string.edit_brand_auto_found, it.name) }
                                    ?: stringResource(R.string.edit_brand_auto)
                            )
                        }
                        item {
                            SeciciSatir(choice == Brands.NONE, { onPick(Brands.NONE) }, { BosRozet(32) },
                                stringResource(R.string.edit_brand_none))
                        }
                    }
                    items(liste, key = { it.id }) { b ->
                        SeciciSatir(choice == b.id, { onPick(b.id) }, { BrandBadge(b, size = 32) }, b.name)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.edit_brand_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } }
    )
}

@Composable
private fun SeciciSatir(secili: Boolean, onClick: () -> Unit, rozet: @Composable () -> Unit, metin: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp)
    ) {
        rozet()
        Spacer(Modifier.width(12.dp))
        Text(metin, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (secili) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
}
