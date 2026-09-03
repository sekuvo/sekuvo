package com.afgover.vault.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Note
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.afgover.vault.data.NoteKind
import com.afgover.vault.data.EntrySort
import com.afgover.vault.data.EntryType
import com.afgover.vault.ui.EntryListItem
import com.afgover.vault.ui.VaultViewModel
import androidx.compose.ui.res.stringResource
import com.afgover.vault.R

fun EntryType.icon(): ImageVector = when (this) {
    EntryType.LOGIN -> Icons.Filled.Key
    EntryType.EVERYDAY -> Icons.Filled.Badge
    EntryType.CARD -> Icons.Filled.CreditCard
    EntryType.NOTE -> Icons.AutoMirrored.Filled.Note
}

@Composable
fun EntryType.label(): String = stringResource(
    when (this) {
        EntryType.LOGIN -> R.string.type_login
        EntryType.EVERYDAY -> R.string.type_everyday
        EntryType.CARD -> R.string.type_card
        EntryType.NOTE -> R.string.type_note
    }
)

/** Tür kimlik rengi: liste ve detayda ikon kapsülünü boyar, taramayı hızlandırır. */
@Composable
fun EntryType.renk(): Color = when (this) {
    EntryType.LOGIN -> MaterialTheme.colorScheme.primary
    EntryType.EVERYDAY -> Color(0xFF63C7B2)
    EntryType.CARD -> MaterialTheme.colorScheme.tertiary
    EntryType.NOTE -> Color(0xFFB08FE0)
}

/** Güvenli not alt türünün ikonu — kartta ne olduğu bir bakışta görünür. */
fun NoteKind.icon(): ImageVector = when (this) {
    NoteKind.GENEL -> Icons.AutoMirrored.Filled.Note
    NoteKind.BETIK -> Icons.Filled.Terminal
    NoteKind.ANAHTAR -> Icons.Filled.VpnKey
    NoteKind.PARMAK_IZI -> Icons.Filled.Fingerprint
    NoteKind.KURTARMA -> Icons.Filled.HealthAndSafety
    NoteKind.YAPILANDIRMA -> Icons.Filled.DataObject
}

/** Yumuşak renkli kapsül içinde tür ikonu. */
@Composable
fun TypeBadge(type: EntryType, kind: NoteKind = NoteKind.GENEL, size: Int = 42) {
    val renk = type.renk()
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(size.dp / 3))
            .background(renk.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            if (type == EntryType.NOTE) kind.icon() else type.icon(),
            contentDescription = null,
            tint = renk,
            modifier = Modifier.size((size * 0.55).dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: VaultViewModel,
    onOpen: (Long) -> Unit,
    onAdd: (EntryType) -> Unit,
    onSettings: () -> Unit,
    onAnchor: () -> Unit
) {
    val entries by viewModel.entries.collectAsState()
    val tags by viewModel.tags.collectAsState()
    var query by remember { mutableStateOf("") }
    var addMenuOpen by remember { mutableStateOf(false) }
    var showGenerator by remember { mutableStateOf(false) }
    var filterType by remember { mutableStateOf<EntryType?>(null) }
    val filterTagIds = remember { mutableStateListOf<Long>() }
    var manageTags by remember { mutableStateOf(false) }
    var showPasteImport by remember { mutableStateOf(false) }
    var qrTarama by remember { mutableStateOf(false) }
    var qrZarf by remember { mutableStateOf("") }
    var showTransferWizard by remember { mutableStateOf(false) }
    var sortMenuOpen by remember { mutableStateOf(false) }

    // Seçimler daraltarak birleşir: tür VE seçili etiketlerin tamamı.
    // Sıra kullanıcının seçtiği düzendir (klavyeyle aynı — EntrySort).
    val filtered = entries.filter { item ->
        (query.isBlank() || item.title.contains(query, ignoreCase = true)) &&
            (filterType == null || item.type == filterType) &&
            filterTagIds.all { it in item.tagIds }
    }.let { list ->
        when (viewModel.sort) {
            EntrySort.TITLE_ASC -> list.sortedBy { it.title.lowercase() }
            EntrySort.TITLE_DESC -> list.sortedByDescending { it.title.lowercase() }
            EntrySort.UPDATED_DESC -> list.sortedByDescending { it.updatedAt }
            EntrySort.CREATED_DESC -> list.sortedByDescending { it.createdAt }
            EntrySort.TYPE -> list.sortedWith(
                compareBy({ it.type.ordinal }, { it.title.lowercase() })
            )
            EntrySort.MANUAL -> list.sortedWith(
                compareBy({ it.sortIndex }, { it.title.lowercase() })
            )
        }
    }

    // Etiket satırı seçili türe göre daralır: bir tür seçiliyken yalnız O
    // TÜRDEKİ kayıtlarda geçen etiketler görünür, seçim yokken hepsi. Kaynak
    // arama sonucu değil `entries` — aramaya göre de daraltmak, kullanıcı
    // yazarken çip satırının altından kaymasına yol açardı.
    val gorunenEtiketler = if (filterType == null) tags else {
        val kullanilan = entries
            .filter { it.type == filterType }
            .flatMapTo(mutableSetOf()) { it.tagIds }
        tags.filter { it.id in kullanilan }
    }

    // Görünmez süzgeç bırakma: seçili bir etiket tür değişince listeden
    // düşerse seçimi de düşer. Aksi hâlde liste boşalır ve boşaltan çip
    // ekranda olmadığı için kullanıcı onu geri alamaz.
    LaunchedEffect(gorunenEtiketler) {
        filterTagIds.retainAll { id -> gorunenEtiketler.any { it.id == id } }
    }

    // Kullanıcı sırasında satırlar sürüklenebilir: sürükleme boyunca liste
    // yerelde tutulur (her hareket veritabanına yazılmaz), parmak kalkınca
    // yeni sıra bir kez kaydedilir.
    val manuel = viewModel.sort == EntrySort.MANUAL &&
        query.isBlank() && filterType == null && filterTagIds.isEmpty()
    var surukleniyor by remember { mutableStateOf<Long?>(null) }
    var yerelSira by remember { mutableStateOf<List<EntryListItem>>(emptyList()) }
    LaunchedEffect(filtered, manuel) { if (manuel) yerelSira = filtered }
    val gosterilen = if (manuel && yerelSira.isNotEmpty()) yerelSira else filtered

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.app_name))
                        Box {
                            IconButton(onClick = { sortMenuOpen = true }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Sort,
                                    contentDescription = stringResource(
                                        R.string.home_sort_cd,
                                        stringResource(viewModel.sort.labelRes)
                                    )
                                )
                            }
                            DropdownMenu(
                                expanded = sortMenuOpen,
                                onDismissRequest = { sortMenuOpen = false }
                            ) {
                                EntrySort.entries.forEach { secenek ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(secenek.labelRes)) },
                                        leadingIcon = {
                                            if (viewModel.sort == secenek) {
                                                Icon(
                                                    Icons.Filled.Check,
                                                    contentDescription =
                                                        stringResource(R.string.home_selected)
                                                )
                                            }
                                        },
                                        onClick = {
                                            viewModel.selectSort(secenek)
                                            sortMenuOpen = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                },
                actions = {
                    // Araç çıpası: kasa içeriği değil, indirdiğin aracın kimlik
                    // ölçüsü. Kendi düğmesi var çünkü listede aranacak bir şey
                    // değil — lazım olduğunda doğrudan gidilir.
                    IconButton(onClick = onAnchor) {
                        Icon(
                            Icons.Filled.Fingerprint,
                            contentDescription = stringResource(R.string.anchor_open_cd)
                        )
                    }
                    IconButton(onClick = { showTransferWizard = true }) {
                        Icon(
                            Icons.Filled.SwapHoriz,
                            contentDescription = stringResource(R.string.home_transfer_cd)
                        )
                    }
                    IconButton(onClick = { showGenerator = true }) {
                        Icon(
                            Icons.Filled.Casino,
                            contentDescription = stringResource(R.string.home_generator_cd)
                        )
                    }
                    IconButton(onClick = { viewModel.lock() }) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = stringResource(R.string.home_lock_cd)
                        )
                    }
                    IconButton(onClick = onSettings) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.home_settings_cd)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                DropdownMenu(
                    expanded = addMenuOpen,
                    onDismissRequest = { addMenuOpen = false }
                ) {
                    EntryType.entries.forEach { type ->
                        DropdownMenuItem(
                            text = { Text(type.label()) },
                            leadingIcon = { Icon(type.icon(), contentDescription = null) },
                            onClick = {
                                addMenuOpen = false
                                onAdd(type)
                            }
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.home_import_qr)) },
                        leadingIcon = {
                            Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                        },
                        onClick = {
                            addMenuOpen = false
                            qrZarf = ""
                            qrTarama = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.home_import_text)) },
                        leadingIcon = {
                            Icon(Icons.Filled.ContentPaste, contentDescription = null)
                        },
                        onClick = {
                            addMenuOpen = false
                            showPasteImport = true
                        }
                    )
                }
                FloatingActionButton(
                    onClick = { addMenuOpen = true },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = stringResource(R.string.home_add_cd)
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.home_search_hint)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth()
            )
            // Süzgeçler iki SABİT satır: üstte türler, altta etiketler
            // (kullanıcı kararı — serbest sarmalı FlowRow uzun etiket
            // listesinde ekranı kaplıyordu). Satırlar sarmaz, yatay kayar;
            // uzun çip kenarda kesik görünür ve kesik görüntü kayabildiğinin
            // işaretidir. Satır arası boşluk bilinçli olarak sıkı.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                EntryType.entries.forEach { t ->
                    FilterChip(
                        selected = filterType == t,
                        onClick = { filterType = if (filterType == t) null else t },
                        label = { Text(t.label()) }
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                gorunenEtiketler.forEach { tag ->
                    TagChip(
                        tag = tag,
                        selected = tag.id in filterTagIds,
                        onToggle = {
                            if (tag.id in filterTagIds) filterTagIds.remove(tag.id)
                            else filterTagIds.add(tag.id)
                        }
                    )
                }
                IconButton(onClick = { manageTags = true }) {
                    Icon(
                        Icons.Filled.Sell,
                        contentDescription = stringResource(R.string.home_manage_tags_cd)
                    )
                }
            }
            Spacer(Modifier.height(4.dp))

            if (manuel) {
                Text(
                    stringResource(R.string.home_manual_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            if (filtered.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 56.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        if (entries.isEmpty()) Icons.Filled.Lock else Icons.Filled.Search,
                        contentDescription = null,
                        modifier = Modifier.size(44.dp),
                        tint = MaterialTheme.colorScheme.outlineVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = if (entries.isEmpty())
                            stringResource(R.string.home_empty_vault)
                        else stringResource(R.string.home_empty_filter),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn {
                    items(gosterilen, key = { it.id }) { item ->
                        val aktif = surukleniyor == item.id
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (aktif)
                                    MaterialTheme.colorScheme.surfaceContainerHighest
                                else MaterialTheme.colorScheme.surfaceContainer
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 5.dp)
                                .clip(MaterialTheme.shapes.medium)
                                .clickable { onOpen(item.id) }
                        ) {
                            Row(
                                modifier = Modifier.padding(
                                    start = if (manuel) 4.dp else 14.dp,
                                    end = 14.dp, top = 12.dp, bottom = 12.dp
                                ),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (manuel) {
                                    Icon(
                                        Icons.Filled.DragHandle,
                                        contentDescription = stringResource(R.string.home_drag_cd),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .padding(end = 6.dp)
                                            .size(24.dp)
                                            .pointerInput(item.id, gosterilen.size) {
                                                var birikim = 0f
                                                detectDragGestures(
                                                    onDragStart = { surukleniyor = item.id },
                                                    onDragEnd = {
                                                        surukleniyor = null
                                                        viewModel.saveManualOrder(
                                                            yerelSira.map { it.id }
                                                        )
                                                    },
                                                    onDragCancel = { surukleniyor = null }
                                                ) { change, drag ->
                                                    change.consume()
                                                    birikim += drag.y
                                                    val satir = 76.dp.toPx()
                                                    if (kotlin.math.abs(birikim) >= satir) {
                                                        val yon = if (birikim > 0) 1 else -1
                                                        birikim -= yon * satir
                                                        val mevcut = yerelSira
                                                            .indexOfFirst { it.id == item.id }
                                                        val hedef = mevcut + yon
                                                        if (mevcut >= 0 && hedef in yerelSira.indices) {
                                                            yerelSira = yerelSira.toMutableList()
                                                                .apply { add(hedef, removeAt(mevcut)) }
                                                        }
                                                    }
                                                }
                                            }
                                    )
                                }
                                TypeBadge(item.type, kind = item.noteKind)
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            item.title,
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        item.tagIds.forEach { tid ->
                                            tags.find { it.id == tid }?.let {
                                                Spacer(Modifier.width(6.dp))
                                                // Başlık titleMedium (16sp) — nokta da o boyda.
                                                TagDot(it.color, size = 16)
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        item.type.label(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (item.quick) {
                                    Text(
                                        "⚡",
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.padding(start = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }

    if (showTransferWizard) {
        TransferWizardDialog(viewModel = viewModel, onDismiss = { showTransferWizard = false })
    }

    if (qrTarama) {
        QrScanScreen(
            onEnvelope = { zarf ->
                qrTarama = false
                qrZarf = zarf
                showPasteImport = true
            },
            onCancel = { qrTarama = false }
        )
        return
    }

    if (showPasteImport) {
        PasteImportDialog(
            viewModel = viewModel,
            initialText = qrZarf,
            onDismiss = { showPasteImport = false; qrZarf = "" }
        )
    }

    if (manageTags) {
        TagManageDialog(viewModel = viewModel, tags = tags, onDismiss = { manageTags = false })
    }

    if (showGenerator) {
        val sifreEtiketi = stringResource(R.string.field_password)
        GeneratorDialog(
            onDismiss = { showGenerator = false },
            onCopy = { viewModel.copyToClipboard(sifreEtiketi, it) }
        )
    }
}
