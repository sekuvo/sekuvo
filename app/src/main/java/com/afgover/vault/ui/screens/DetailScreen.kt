package com.afgover.vault.ui.screens

import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.afgover.vault.core.FieldFormats
import com.afgover.vault.data.FieldKey
import com.afgover.vault.R
import com.afgover.vault.core.Fingerprint
import com.afgover.vault.data.DecryptedEntry
import com.afgover.vault.data.EntryType
import com.afgover.vault.data.UsageEvent
import com.afgover.vault.data.UsageKind
import com.afgover.vault.ui.VaultViewModel

/** Bilgisayara yazılacak alan: ekrandaki adı, kararlı adı ve değeri. */
data class BtHedef(val gosterilen: String, val kararli: String, val deger: String)

// Maskeleme METİN karşılaştırmasıyla değil kimlikle yapılır
// ([EntryField.hidden]): "Şifre" metnine bakan eski kontrol, etiket
// çevrildiği anda şifreyi maskesiz gösterirdi (yerelleştirme tuzağı).

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    viewModel: VaultViewModel,
    id: Long,
    onEdit: (Long, EntryType) -> Unit,
    onBack: () -> Unit,
    /** 💻 ekranındaki "kurulum sihirbazı" bağlantısı. */
    onBtKurulum: () -> Unit = {}
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var entry by remember { mutableStateOf<DecryptedEntry?>(null) }
    val tags by viewModel.tags.collectAsState()
    var btField by remember { mutableStateOf<BtHedef?>(null) }
    var usage by remember { mutableStateOf<List<UsageEvent>>(emptyList()) }
    var usageTick by remember { mutableStateOf(0) }
    var usageExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(id) {
        entry = viewModel.loadEntry(id)
    }
    LaunchedEffect(id, usageTick) {
        usage = viewModel.usageFor(id)
    }

    val e = entry
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(e?.title ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.detail_back)
                        )
                    }
                },
                actions = {
                    if (e != null) {
                        IconButton(onClick = { onEdit(e.id, e.type) }) {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = stringResource(R.string.detail_edit)
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (e == null) return@Scaffold
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            val entryTags = tags.filter { it.id in e.tagIds }
            if (entryTags.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    entryTags.forEach { tag ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TagDot(tag.color, tag.icon, size = 13)
                            Spacer(Modifier.padding(2.dp))
                            Text(tag.name, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            e.data.fields().forEach { alan ->
                val gosterilen = alan.customLabel ?: stringResource(alan.key!!.labelRes)
                FieldCard(
                    label = gosterilen,
                    value = alan.value,
                    // Kart ve IBAN okunmak için gruplu, kopyalanmak için ham
                    // gösterilir; ikisi ayrı parametre olduğu için panoya ve
                    // Bluetooth'a giden değere boşluk sızmıyor.
                    displayValue = when (alan.key) {
                        FieldKey.CARD_NUMBER -> FieldFormats.kartGrupla(
                            alan.value, FieldFormats.cardNetwork(alan.value)
                        )
                        FieldKey.IBAN -> FieldFormats.grupla(alan.value)
                        FieldKey.EXPIRY -> FieldFormats.expiryGoster(alan.value)
                        else -> alan.value
                    },
                    // Kart ağı etiketin yanında: hangi kart olduğu, numarayı
                    // okumadan görünsün.
                    labelSuffix = if (alan.key == FieldKey.CARD_NUMBER) {
                        FieldFormats.cardNetwork(alan.value).gorunenAd
                    } else "",
                    labelIcon = alan.customType?.ikon(),
                    hiddenByDefault = alan.hidden,
                    onCopy = {
                        // Panoya ve günlüğe KARARLI ad gider, ekrandaki çeviri değil.
                        viewModel.copyToClipboard(gosterilen, alan.value, e.id, alan.stableName)
                        usageTick++
                    },
                    onTypeToPc = { btField = BtHedef(gosterilen, alan.stableName, alan.value) },
                    onCopyFingerprint = { viewModel.copyToClipboard(gosterilen, it) }
                )
                Spacer(Modifier.padding(4.dp))
            }

            Spacer(Modifier.padding(8.dp))
            OutlinedCard(
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.detail_quick_title),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            if (e.quick) {
                                stringResource(R.string.detail_quick_on)
                            } else {
                                stringResource(R.string.detail_quick_off)
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = e.quick,
                        onCheckedChange = { value ->
                            entry = e.copy(quick = value)
                            viewModel.setQuick(e.id, value)
                            usageTick++
                        }
                    )
                }
            }

            Spacer(Modifier.padding(6.dp))
            // Tarih biçimi cihazın diline uyar: "tr" sabiti İngilizce arayüzde
            // Türkçe ay adı gösteriyordu.
            val df = remember {
                java.text.SimpleDateFormat("d MMM yyyy HH:mm", java.util.Locale.getDefault())
            }
            // Tarihler ve kullanım günlüğü de kendi kartlarında: alanlar kartlı,
            // altındaki bölümler serbest metin olunca ekran iç içe görünüyordu.
            val tarihSatiri = stringResource(
                R.string.detail_dates,
                df.format(java.util.Date(e.createdAt)),
                df.format(java.util.Date(e.updatedAt))
            )
            val sifreSatiri = if (e.data.passwordChangedAt > 0) {
                "\n" + stringResource(
                    R.string.detail_password_changed,
                    df.format(java.util.Date(e.data.passwordChangedAt))
                )
            } else {
                ""
            }
            OutlinedCard(
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    tarihSatiri + sifreSatiri,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }

            // Eski parolalar: parola değiştiğinde eskisi silinmiyor, tarihiyle
            // burada kalıyor. "Sızıntı duyuldu, o tarihte hangi parolayı
            // kullanıyordum" sorusunun tek cevabı bu liste.
            if (e.data.passwordHistory.isNotEmpty()) {
                Spacer(Modifier.padding(8.dp))
                OutlinedCard(
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(
                            stringResource(R.string.old_passwords_title),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            stringResource(R.string.old_passwords_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Spacer(Modifier.padding(2.dp))
                        val bilinmiyor = stringResource(R.string.old_passwords_unknown_date)
                        val parolaEtiketi = stringResource(R.string.field_password)
                        e.data.passwordHistory.forEachIndexed { i, eski ->
                            OldPasswordRow(
                                old = eski,
                                dateText = if (eski.changedAt > 0) {
                                    df.format(java.util.Date(eski.changedAt))
                                } else {
                                    bilinmiyor
                                },
                                // Günlüğe kayıt id'siz yazılır: bu güncel değer
                                // değil, geçmişten bir kopya.
                                onCopy = { viewModel.copyToClipboard(parolaEtiketi, eski.value) },
                                showDivider = i < e.data.passwordHistory.lastIndex
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.padding(8.dp))
            OutlinedCard(
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(
                stringResource(R.string.detail_usage_title),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                stringResource(R.string.detail_usage_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(Modifier.padding(2.dp))
            if (usage.isEmpty()) {
                Text(
                    stringResource(R.string.detail_usage_empty),
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                (if (usageExpanded) usage else usage.take(5)).forEach { ev ->
                    Text(
                        buildString {
                            append(df.format(java.util.Date(ev.at)))
                            append(" · ").append(stringResource(ev.kind.labelRes))
                            ev.fieldLabel?.let { append(" (").append(it).append(")") }
                            ev.target?.let { h ->
                                usageTargetLabel(ctx, h)?.let { append(" → ").append(it) }
                            }
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (usage.size > 5) {
                    TextButton(onClick = { usageExpanded = !usageExpanded }) {
                        Text(
                            if (usageExpanded) {
                                stringResource(R.string.detail_show_less)
                            } else {
                                stringResource(R.string.detail_show_all, usage.size)
                            }
                        )
                    }
                }
            }
            }
            }
            Spacer(Modifier.padding(8.dp))
        }
    }

    btField?.let { hedef ->
        BtTypeDialog(
            label = hedef.gosterilen,
            value = hedef.deger,
            onDismiss = { btField = null },
            onKurulum = {
                btField = null
                onBtKurulum()
            },
            onTyped = { target ->
                entry?.let {
                    viewModel.logUsage(it.id, UsageKind.BT_YAZILDI, hedef.kararli, target)
                }
                usageTick++
            }
        )
    }
}

@Composable
private fun FieldCard(
    label: String,
    value: String,
    displayValue: String = value,
    labelSuffix: String = "",
    /** Eklenen alanın tür ikonu; yerleşik alanlarda yok. */
    labelIcon: ImageVector? = null,
    hiddenByDefault: Boolean,
    onCopy: () -> Unit,
    onTypeToPc: () -> Unit,
    onCopyFingerprint: (String) -> Unit
) {
    var visible by remember { mutableStateOf(!hiddenByDefault) }
    var fullFingerprint by remember { mutableStateOf(false) }
    // Uzun/çok satırlı değerlerde (.pem, anahtar) parmak izi kendiliğinden
    // görünür; kısa değerlerde istek üzerine — bir SHA-256 özeti, değer
    // zayıfsa kaba kuvvetle geri çözülebilir (tuzsuz ve hızlı özet).
    val uzun = value.length >= 100 || value.contains('\n')
    var showShortFp by remember { mutableStateOf(false) }
    val fpVisible = uzun || showShortFp
    val hex = remember(value, fpVisible) {
        if (fpVisible) Fingerprint.sha256Hex(value) else ""
    }

    // Çerçeveli kart. Zemin beyaz olduğu için dolgu rengiyle ayrışma
    // kalmadı; sınırı çizgi taşıyor — her bilgi kendi çerçevesinde.
    OutlinedCard(
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            // Başlık ve eylemler üstte: değer artık tam genişlikte akıyor,
            // düğmeler metnin ortasında yer kaplamıyor.
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (labelIcon != null) {
                    Icon(
                        labelIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    if (labelSuffix.isEmpty()) label else "$label · $labelSuffix",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                if (hiddenByDefault) {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(
                            if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (visible) {
                                stringResource(R.string.detail_hide)
                            } else {
                                stringResource(R.string.detail_show)
                            }
                        )
                    }
                }
                IconButton(onClick = onCopy) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = stringResource(R.string.detail_copy)
                    )
                }
                IconButton(onClick = onTypeToPc) {
                    Icon(
                        Icons.Filled.Computer,
                        contentDescription = stringResource(R.string.detail_type_to_pc)
                    )
                }
            }
            Text(
                text = if (visible) displayValue else "••••••••",
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp, bottom = if (uzun) 6.dp else 0.dp)
            )
            if (!uzun && !showShortFp && visible) {
                Text(
                    stringResource(R.string.detail_show_fingerprint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { showShortFp = true }
                        .padding(top = 6.dp)
                )
            }
            if (fpVisible && visible) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { fullFingerprint = !fullFingerprint }
                        .padding(top = 6.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (uzun) {
                                stringResource(R.string.detail_fingerprint_len, value.length)
                            } else {
                                stringResource(
                                    R.string.detail_fingerprint_len_short,
                                    value.length
                                )
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            if (fullFingerprint) Fingerprint.grouped(hex)
                            else Fingerprint.short(hex),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                        if (!uzun) {
                            Text(
                                stringResource(R.string.detail_fingerprint_warning),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        if (fullFingerprint) {
                            // printf'in "%s"i biçim argümanı olarak geçiyor: kaynak
                            // dizesinde ham % bırakırsak aapt/lint biçim uyuşmazlığı
                            // sanıyor, %% yazarsak da ekranda %% görünüyor.
                            Text(
                                stringResource(R.string.detail_fingerprint_verify, "%s"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                    IconButton(onClick = { onCopyFingerprint(hex) }) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = stringResource(R.string.detail_copy_fingerprint),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}
