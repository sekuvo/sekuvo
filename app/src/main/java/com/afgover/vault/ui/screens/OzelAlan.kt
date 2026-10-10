package com.afgover.vault.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.afgover.vault.R
import com.afgover.vault.core.FieldFormats
import com.afgover.vault.data.CustomField
import com.afgover.vault.data.CustomFieldType

/** Türün ikonu: tür menüsünde, formdaki satırda ve detay ekranında aynı. */
fun CustomFieldType.ikon(): ImageVector = when (this) {
    CustomFieldType.TEXT -> Icons.Filled.TextFields
    CustomFieldType.PASSWORD -> Icons.Filled.Key
    CustomFieldType.EMAIL -> Icons.Filled.Email
    CustomFieldType.PHONE -> Icons.Filled.Phone
    CustomFieldType.URL -> Icons.Filled.Language
    CustomFieldType.NUMBER -> Icons.Filled.Numbers
    CustomFieldType.MULTILINE -> Icons.AutoMirrored.Filled.Notes
}

private fun CustomFieldType.klavye(): KeyboardType = when (this) {
    CustomFieldType.TEXT, CustomFieldType.MULTILINE -> KeyboardType.Text
    CustomFieldType.PASSWORD -> KeyboardType.Password
    CustomFieldType.EMAIL -> KeyboardType.Email
    CustomFieldType.PHONE -> KeyboardType.Phone
    CustomFieldType.URL -> KeyboardType.Uri
    CustomFieldType.NUMBER -> KeyboardType.Number
}

/**
 * Formdaki eklenen alanın özeti: ikon · ad · değer. Dokununca düzenleme
 * penceresi açılır. Şifre türünde değer formda da gösterilmez.
 */
@Composable
fun OzelAlanSatiri(alan: CustomField, onDuzenle: () -> Unit, onSil: () -> Unit) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onDuzenle)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp)
        ) {
            Icon(alan.type.ikon(), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    alan.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    if (alan.type == CustomFieldType.PASSWORD && alan.value.isNotEmpty()) "••••••••"
                    else alan.value,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onSil) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.edit_remove_field))
            }
        }
    }
}

/**
 * Alan ekleme/düzenleme penceresi. Form içinde açılan alan, altındaki notlar
 * ve diğer alanlar arasında kayboluyordu; pencere arkasını karartır ve
 * (EditScreen'de) bulanıklaştırır, göz yalnız bu alanda kalır.
 */
@Composable
fun OzelAlanDialog(
    baslangic: CustomField,
    yeni: Boolean,
    onKaydet: (CustomField) -> Unit,
    onKapat: () -> Unit,
    onCopy: (etiket: String, deger: String) -> Unit
) {
    var ad by remember { mutableStateOf(baslangic.label) }
    var deger by remember { mutableStateOf(baslangic.value) }
    var tur by remember { mutableStateOf(baslangic.type) }
    var uretici by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onKapat,
        title = {
            Text(stringResource(if (yeni) R.string.edit_field_dialog_new else R.string.edit_field_dialog_edit))
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = ad,
                    onValueChange = { ad = it },
                    label = { Text(stringResource(R.string.edit_field_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                TurSecici(secili = tur, onSec = { tur = it })
                // Tür, değer alanının nasıl davrandığını belirler: açılan
                // klavye, tek/çok satır ve Şifre'de üretici.
                val epostaHatali = tur == CustomFieldType.EMAIL && !FieldFormats.epostaGecerliMi(deger)
                OutlinedTextField(
                    value = deger,
                    onValueChange = { deger = it },
                    label = { Text(stringResource(R.string.edit_field_value)) },
                    singleLine = tur != CustomFieldType.TEXT && tur != CustomFieldType.MULTILINE,
                    minLines = if (tur == CustomFieldType.MULTILINE) 4 else 1,
                    isError = epostaHatali,
                    supportingText = if (epostaHatali) {
                        { Text(stringResource(R.string.edit_email_invalid)) }
                    } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = tur.klavye()),
                    trailingIcon = if (tur == CustomFieldType.PASSWORD) {
                        {
                            IconButton(onClick = { uretici = true }) {
                                Icon(
                                    Icons.Filled.Casino,
                                    contentDescription = stringResource(R.string.edit_generate_password)
                                )
                            }
                        }
                    } else null,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onKaydet(CustomField(ad.trim(), deger.trim(), tur)) },
                enabled = ad.isNotBlank()
            ) {
                Text(stringResource(if (yeni) R.string.edit_field_add_confirm else R.string.edit_field_done))
            }
        },
        dismissButton = {
            TextButton(onClick = onKapat) { Text(stringResource(R.string.edit_cancel)) }
        }
    )

    if (uretici) {
        // Pano etiketi: alanın adı; adsızsa "Şifre".
        val etiket = ad.trim().ifEmpty { stringResource(R.string.field_password) }
        GeneratorDialog(
            onDismiss = { uretici = false },
            onCopy = { onCopy(etiket, it) },
            onUse = { deger = it }
        )
    }
}

/** "Alan türü: [ikon] Metin ▾" — menüde her türün başında kendi ikonu. */
@Composable
private fun TurSecici(secili: CustomFieldType, onSec: (CustomFieldType) -> Unit) {
    var acik by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.edit_field_type), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(4.dp))
        Box {
            TextButton(onClick = { acik = true }) {
                Icon(secili.ikon(), contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(secili.labelRes))
                Icon(Icons.Filled.ExpandMore, contentDescription = null)
            }
            DropdownMenu(expanded = acik, onDismissRequest = { acik = false }) {
                CustomFieldType.entries.forEach { t ->
                    DropdownMenuItem(
                        text = { Text(stringResource(t.labelRes)) },
                        leadingIcon = { Icon(t.ikon(), contentDescription = null) },
                        onClick = {
                            onSec(t)
                            acik = false
                        }
                    )
                }
            }
        }
    }
}
