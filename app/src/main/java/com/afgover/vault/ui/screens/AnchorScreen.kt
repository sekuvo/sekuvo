package com.afgover.vault.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.afgover.vault.R
import com.afgover.vault.ui.VaultViewModel

/**
 * Araç çıpası: bilgisayar tarafındaki `aktar.html` dosyasının SHA-256'sı.
 *
 * Bu bir sır DEĞİL — indirdiğin dosyanın doğru dosya olduğunu ölçtüğün
 * referans. Bu yüzden maskelenmiyor, klavyede görünmüyor ve "parolasız
 * kullan" anahtarı yok: klavyeye yazılacak bir değer değil, karşılaştıracağın
 * bir değer. Kasada normal bir not olarak durup listede kaybolmasın diye
 * kendi ekranı ve üst çubukta kendi düğmesi var.
 *
 * Güncellemesi QR ile: yeni bir aktar.html sürümü çıktığında, yeni özeti
 * bilgisayarda şifreli bir zarfa koyup telefona okutursun — değer hiçbir
 * noktada düz metin olarak dolaşmaz.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnchorScreen(viewModel: VaultViewModel, onBack: () -> Unit) {
    val anchor = viewModel.anchor
    var duzenleniyor by remember { mutableStateOf(false) }
    var taslak by remember { mutableStateOf("") }
    var qrTarama by remember { mutableStateOf(false) }
    var zarf by remember { mutableStateOf<String?>(null) }
    var zarfParolasi by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { viewModel.loadAnchor() }

    if (qrTarama) {
        QrScanScreen(
            onEnvelope = { metin -> qrTarama = false; zarf = metin; zarfParolasi = "" },
            onCancel = { qrTarama = false }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.anchor_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.detail_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                stringResource(R.string.anchor_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
            )
            Spacer(Modifier.height(14.dp))

            OutlinedCard(
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        stringResource(R.string.anchor_value_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(6.dp))
                    val deger = anchor?.data?.notes.orEmpty()
                    if (deger.isEmpty()) {
                        Text(
                            stringResource(R.string.anchor_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                deger,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f)
                            )
                            val etiket = stringResource(R.string.anchor_value_label)
                            IconButton(onClick = { viewModel.copyToClipboard(etiket, deger) }) {
                                Icon(
                                    Icons.Filled.ContentCopy,
                                    contentDescription = stringResource(R.string.detail_copy)
                                )
                            }
                        }
                    }
                }
            }

            // Eklenme/değiştirilme tarihleri kalsın dendi: çıpanın ne zaman
            // tazelendiğini bilmek, aracın hangi sürümüne baktığını bilmektir.
            anchor?.let { a ->
                val df = remember {
                    java.text.SimpleDateFormat("d MMM yyyy HH:mm", java.util.Locale.getDefault())
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(
                        R.string.detail_dates,
                        df.format(java.util.Date(a.createdAt)),
                        df.format(java.util.Date(a.updatedAt))
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            Spacer(Modifier.height(16.dp))
            Button(onClick = { qrTarama = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                Spacer(Modifier.padding(4.dp))
                Text(stringResource(R.string.anchor_update_qr))
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { taslak = anchor?.data?.notes.orEmpty(); duzenleniyor = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.anchor_edit)) }

            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.anchor_verify_hint),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (duzenleniyor) {
        AlertDialog(
            onDismissRequest = { duzenleniyor = false },
            title = { Text(stringResource(R.string.anchor_edit)) },
            text = {
                OutlinedTextField(
                    value = taslak,
                    onValueChange = { taslak = it },
                    label = { Text(stringResource(R.string.anchor_value_label)) },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = taslak.isNotBlank(),
                    onClick = { viewModel.saveAnchor(taslak) { duzenleniyor = false } }
                ) { Text(stringResource(R.string.edit_save)) }
            },
            dismissButton = {
                TextButton(onClick = { duzenleniyor = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            }
        )
    }

    // QR okundu: zarfın parolası sorulur, sonra içinden TEK değer alınır.
    zarf?.let { metin ->
        AlertDialog(
            onDismissRequest = { zarf = null },
            title = { Text(stringResource(R.string.anchor_update_qr)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.anchor_qr_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = zarfParolasi,
                        onValueChange = { zarfParolasi = it },
                        label = { Text(stringResource(R.string.paste_label_backup_password)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    viewModel.error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = zarfParolasi.isNotEmpty() && !viewModel.busy,
                    onClick = {
                        viewModel.readFromEnvelope(
                            text = metin,
                            password = zarfParolasi,
                            // Çıpa bir not olarak yazılır; parola alanına
                            // konmuşsa da kabul et — insanlar aracı iki türlü
                            // dolduruyor, ikisinde de doğru şeyi al.
                            sec = { it.data.notes.ifEmpty { it.data.password } }
                        ) { deger ->
                            viewModel.saveAnchor(deger) { zarf = null }
                        }
                    }
                ) { Text(stringResource(R.string.paste_btn_import)) }
            },
            dismissButton = {
                TextButton(onClick = { zarf = null }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            }
        )
    }
}
