package com.afgover.vault.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.autofill.AutofillManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.afgover.vault.R
import com.afgover.vault.ui.theme.vaultButtonColors
import com.afgover.vault.data.EntrySort
import com.afgover.vault.ui.VaultViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.res.stringResource
import com.afgover.vault.core.AppLocale
import com.afgover.vault.bt.HidLayouts
import android.content.Context
import android.app.Activity
import androidx.compose.material3.Switch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: VaultViewModel,
    canUseBiometric: Boolean,
    onBiometricEnable: (String?) -> Unit,
    onBack: () -> Unit,
    onInfo: (InfoKind) -> Unit = {},
    onTanitim: () -> Unit = {},
    onBtKurulum: () -> Unit = {}
) {
    val context = LocalContext.current

    var exportUri by remember { mutableStateOf<Uri?>(null) }
    var importUri by remember { mutableStateOf<Uri?>(null) }
    var showChangePassword by remember { mutableStateOf(false) }
    var showPasteImport by remember { mutableStateOf(false) }
    var showPinDialog by remember { mutableStateOf(false) }
    var showBiometricPin by remember { mutableStateOf(false) }
    var showUsageLog by remember { mutableStateOf(false) }
    val prefs = remember {
        context.getSharedPreferences("vault_settings", Context.MODE_PRIVATE)
    }
    var dilSecimi by remember { mutableStateOf(AppLocale.current(context)) }
    var acikDuzenler by remember {
        mutableStateOf(HidLayouts.enabledLayouts(prefs.getString("pc_layouts_off", null)).toSet())
    }
    var sonDuzenUyarisi by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> if (uri != null) exportUri = uri }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) importUri = uri }

    // Karşılama ekranından "Yedeğim var" ile gelindiyse dosya seçici
    // kendiliğinden açılır; bayrak burada tüketilir ki kilitlen-aç
    // döngüsünde seçici ikinci kez fırlamasın.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (viewModel.restoreAfterSetup) {
            viewModel.restoreAfterSetup = false
            importLauncher.launch(arrayOf("*/*"))
        }
    }

    fun autofillIsEnabled(): Boolean =
        context.getSystemService(AutofillManager::class.java)
            ?.let { it.isAutofillSupported && it.hasEnabledAutofillServices() } ?: false

    var autofillEnabled by remember { mutableStateOf(autofillIsEnabled()) }
    val autofillLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { autofillEnabled = autofillIsEnabled() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
            viewModel.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
            }
            if (viewModel.busy) {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
            }

            // "Görünüm" bölümü kaldırıldı: sıralama ana ekrandaki ⇅ simgesine
            // taşındığından geriye yalnız oraya işaret eden bir açıklama
            // kalmıştı — ayar olmayan bir ayar başlığı.

            // --- Bilgi sayfaları ---
            // Bu içerik sitede de var; uygulamada olması şart çünkü Sekuvo'nun
            // İnternet izni yok. Gizlilik politikasını okumak için tarayıcı
            // açtırmak, uygulamanın kendi iddiasıyla çelişirdi.
            Text(
                stringResource(R.string.settings_info_section),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onTanitim,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.settings_tour)) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onBtKurulum,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.settings_bt_setup)) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { onInfo(InfoKind.GUIDE) },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.info_guide_title)) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { onInfo(InfoKind.PRIVACY) },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.info_privacy_title)) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { onInfo(InfoKind.ABOUT) },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.info_about_title)) }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // --- Uygulama dili ---
            Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.settings_language_desc),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                AppLocale.Option.entries.forEach { secenek ->
                    SecimCipi(
                        secili = dilSecimi == secenek,
                        onClick = {
                            if (dilSecimi != secenek) {
                                AppLocale.set(context, secenek)
                                dilSecimi = secenek
                                // Dil yapılandırması Context'e bağlı: ekranın
                                // yeniden yaratılması gerekiyor.
                                (context as? Activity)?.recreate()
                            }
                        },
                        label = stringResource(secenek.labelRes),
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // --- Bilgisayara yazarken kullanılabilecek klavye düzenleri ---
            // Sekiz düzen açıkken bölüm ayarların yarısını kaplıyor ve altındaki
            // her şeyi ekrandan aşağı itiyordu; oysa bir kez seçilip yıllarca
            // dönülmeyen bir ayar. Kapalı başlar, kaç tanesinin açık olduğunu
            // başlıkta söyler — açmadan da cevabı görürsün.
            var duzenlerAcik by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { duzenlerAcik = !duzenlerAcik },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.settings_layouts),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        stringResource(
                            R.string.settings_layouts_summary,
                            acikDuzenler.size,
                            HidLayouts.Layout.entries.size
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    if (duzenlerAcik) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = stringResource(
                        if (duzenlerAcik) R.string.settings_collapse else R.string.settings_expand
                    )
                )
            }
            if (duzenlerAcik) {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.settings_layouts_desc),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            HidLayouts.Layout.entries.forEach { duzen ->
                val acik = duzen in acikDuzenler
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(duzen.labelRes),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = acik,
                        onCheckedChange = { istendi ->
                            val yeniKume = if (istendi) acikDuzenler + duzen
                            else acikDuzenler - duzen
                            // En az bir düzen açık kalmalı; hepsi kapanırsa
                            // aktarım ekranı kullanılamaz hâle gelirdi.
                            if (yeniKume.isEmpty()) {
                                sonDuzenUyarisi = true
                            } else {
                                acikDuzenler = yeniKume
                                sonDuzenUyarisi = false
                                prefs.edit()
                                    .putString("pc_layouts_off", HidLayouts.storeDisabled(yeniKume))
                                    .apply()
                            }
                        }
                    )
                }
            }
            if (sonDuzenUyarisi) {
                Text(
                    stringResource(R.string.settings_layouts_min),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text(stringResource(R.string.settings_backup), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.settings_backup_desc),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            Button(
                colors = vaultButtonColors(),
                onClick = {
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                    exportLauncher.launch("vault-$stamp.vaultbak")
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.settings_backup_export)) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.settings_backup_restore)) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { showPasteImport = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.settings_import_paste)) }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text(stringResource(R.string.settings_usage_log), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.settings_usage_log_desc),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { showUsageLog = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.settings_usage_log_open)) }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text(stringResource(R.string.settings_security), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            Text(stringResource(R.string.settings_locking), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.settings_locking_desc),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(12.dp))

            if (canUseBiometric) {
                if (viewModel.biometricEnabled) {
                    OutlinedButton(
                        onClick = { viewModel.disableBiometric() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.settings_biometric_disable)) }
                } else {
                    OutlinedButton(
                        onClick = {
                            if (viewModel.isPinEnabled) showBiometricPin = true
                            else onBiometricEnable(null)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.settings_biometric_enable)) }
                }
                Spacer(Modifier.height(12.dp))
                // PIN, parmak izi yolunun ikinci kapısıdır — bu yüzden onun
                // altında duruyor, ayrı bir bölümde değil.
                Text(stringResource(R.string.settings_pin_heading), style = MaterialTheme.typography.titleSmall)
                if (viewModel.isPinLegacy) {
                    Text(
                        stringResource(R.string.settings_pin_legacy_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Text(
                        if (viewModel.isPinEnabled)
                            stringResource(R.string.settings_pin_on_desc)
                        else
                            stringResource(R.string.settings_pin_off_desc),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { showPinDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        when {
                            viewModel.isPinLegacy -> stringResource(R.string.settings_pin_repair)
                            viewModel.isPinEnabled -> stringResource(R.string.settings_pin_turn_off)
                            else -> stringResource(R.string.settings_pin_set)
                        }
                    )
                }

                Spacer(Modifier.height(12.dp))
            }

            // Hatırlatıcı yalnız biyometrik açılışı bastırmak için var, o yüzden
            // parmak izi olmayan cihazda gösterilmiyor. Ama seçili bir gün
            // sayısı VARSA gösterilmek zorunda: kullanıcı parmak izini
            // telefondan silince ayar görünmez oluyor, yürürlükte kalan süre
            // de kilit ekranında hatırlatma yazdırmaya devam ediyordu — ve
            // kapatılacak yer kalmıyordu (tur bulgusu).
            if (canUseBiometric || viewModel.reminderDays > 0) {
                Text(stringResource(R.string.settings_reminder_heading), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.settings_reminder_desc),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    listOf(0, 30, 60, 90, 180).forEach { gun ->
                        SecimCipi(
                            secili = viewModel.reminderDays == gun,
                            onClick = { viewModel.setReminderDays(gun) },
                            label = if (gun == 0) stringResource(R.string.settings_reminder_never)
                            else stringResource(R.string.settings_reminder_days, gun),
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            OutlinedButton(
                onClick = { showChangePassword = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.settings_change_password)) }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text(stringResource(R.string.settings_autofill), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                if (autofillEnabled) stringResource(R.string.settings_autofill_on_desc)
                else stringResource(R.string.settings_autofill_off_desc),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    val intent = Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE)
                        .setData(Uri.parse("package:${context.packageName}"))
                    runCatching { autofillLauncher.launch(intent) }.onFailure {
                        context.startActivity(Intent(Settings.ACTION_SETTINGS))
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (autofillEnabled) stringResource(R.string.settings_autofill_open)
                    else stringResource(R.string.settings_autofill_enable)
                )
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text(stringResource(R.string.settings_keyboard), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.settings_keyboard_desc),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.settings_keyboard_open)) }
        }
    }

    // Yedek alma: parola iste
    exportUri?.let { uri ->
        PasswordDialog(
            title = stringResource(R.string.settings_export_pw_title),
            description = stringResource(R.string.settings_export_pw_desc),
            confirmField = true,
            onConfirm = { pw ->
                viewModel.exportBackup(uri, pw)
                exportUri = null
            },
            onDismiss = { exportUri = null }
        )
    }

    // Geri yükleme: parola + mod iste
    importUri?.let { uri ->
        ImportDialog(
            onConfirm = { pw, replace ->
                viewModel.importBackup(uri, pw, replace)
                importUri = null
            },
            onDismiss = { importUri = null }
        )
    }

    if (showChangePassword) {
        ChangePasswordDialog(
            viewModel = viewModel,
            onDismiss = { showChangePassword = false }
        )
    }

    if (showBiometricPin) {
        var pin by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showBiometricPin = false },
            title = { Text(stringResource(R.string.settings_biometric_pin_title)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.settings_biometric_pin_desc),
                        style = MaterialTheme.typography.bodySmall
                    )
                    SecretField(
                        value = pin,
                        onValueChange = { y -> if (y.all { it.isDigit() } && y.length <= 12) pin = y },
                        label = stringResource(R.string.settings_label_pin),
                        numeric = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showBiometricPin = false; onBiometricEnable(pin) },
                    enabled = pin.length >= 4
                ) { Text(stringResource(R.string.settings_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { showBiometricPin = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            }
        )
    }

    if (showPinDialog) {
        PinSetupDialog(
            viewModel = viewModel,
            kapatmaKipi = viewModel.isPinEnabled,
            onarimKipi = viewModel.isPinLegacy,
            onDismiss = { showPinDialog = false }
        )
    }

    if (showUsageLog) {
        UsageLogDialog(viewModel = viewModel, onDismiss = { showUsageLog = false })
    }

    if (showPasteImport) {
        PasteImportDialog(
            viewModel = viewModel,
            onDismiss = { showPasteImport = false }
        )
    }
}


@Composable
private fun PasswordDialog(
    title: String,
    description: String,
    confirmField: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(description, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                SecretField(
                    value = pw,
                    onValueChange = { pw = it },
                    label = stringResource(R.string.settings_label_password)
                )
                if (confirmField) {
                    Spacer(Modifier.height(8.dp))
                    SecretField(
                        value = pw2,
                        onValueChange = { pw2 = it },
                        label = stringResource(R.string.settings_label_password_again)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(pw) },
                enabled = pw.isNotEmpty() && (!confirmField || pw == pw2)
            ) { Text(stringResource(R.string.settings_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
        }
    )
}

@Composable
private fun ImportDialog(
    onConfirm: (password: String, replace: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var pw by remember { mutableStateOf("") }
    var hata by remember { mutableStateOf<String?>(null) }
    var replace by remember { mutableIntStateOf(0) } // 0 = ekle, 1 = değiştir
    // onClick @Composable değil: metni burada çözüp lambdaya kapatıyoruz.
    val parolaYokMesaji = stringResource(R.string.settings_import_pw_missing)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_backup_restore)) },
        text = {
            Column {
                SecretField(
                    value = pw,
                    onValueChange = { pw = it },
                    label = stringResource(R.string.settings_label_backup_password)
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    SecimCipi(
                        secili = replace == 0,
                        onClick = { replace = 0 },
                        label = stringResource(R.string.settings_import_merge),
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    SecimCipi(
                        secili = replace == 1,
                        onClick = { replace = 1 },
                        label = stringResource(R.string.settings_import_replace)
                    )
                }
                if (replace == 1) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.settings_import_replace_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                hata?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (pw.isEmpty()) hata = parolaYokMesaji
                    else onConfirm(pw, replace == 1)
                }
            ) { Text(stringResource(R.string.settings_import_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
        }
    )
}

@Composable
private fun ChangePasswordDialog(
    viewModel: VaultViewModel,
    onDismiss: () -> Unit
) {
    var old by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_change_password)) },
        text = {
            Column {
                SecretField(
                    value = old,
                    onValueChange = { old = it },
                    label = stringResource(R.string.settings_label_current_password)
                )
                Spacer(Modifier.height(8.dp))
                SecretField(
                    value = new,
                    onValueChange = { new = it },
                    label = stringResource(R.string.settings_label_new_password)
                )
                Spacer(Modifier.height(8.dp))
                SecretField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = stringResource(R.string.settings_label_new_password_again)
                )
                viewModel.error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { viewModel.changePassword(old, new, confirm, onDismiss) },
                enabled = old.isNotEmpty() && new.isNotEmpty() && !viewModel.busy
            ) { Text(stringResource(R.string.settings_change)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
        }
    )
}

/** PIN açma/kapatma: ikisi de ana parola ister (sargı yeniden yazılır). */
@Composable
private fun PinSetupDialog(
    viewModel: VaultViewModel,
    kapatmaKipi: Boolean,
    onarimKipi: Boolean = false,
    onDismiss: () -> Unit
) {
    var parola by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    // Kapatmada PIN sorulmaz: ana parola zaten kasayı açan yetkidir.
    // Onarım ayrı: orada PIN kriptografik olarak GEREKLİ (iç sargıyı o açar),
    // ve onarım kipinde kapatmaKipi de true olduğu için ayrıca yazılıyor.
    val pinGerekli = !kapatmaKipi || onarimKipi
    val gecerli = parola.isNotEmpty() &&
        (!pinGerekli || (com.afgover.vault.core.PinLock.isValid(pin) &&
            (onarimKipi || pin == pin2)))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    onarimKipi -> stringResource(R.string.settings_pin_repair)
                    kapatmaKipi -> stringResource(R.string.settings_pin_turn_off)
                    else -> stringResource(R.string.settings_pin_set)
                }
            )
        },
        text = {
            Column {
                Text(
                    if (kapatmaKipi) stringResource(R.string.settings_pin_disable_desc)
                    else stringResource(R.string.settings_pin_set_desc),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                SecretField(
                    value = parola,
                    onValueChange = { parola = it },
                    label = stringResource(R.string.settings_label_master_password)
                )
                if (pinGerekli) {
                    SecretField(
                        value = pin,
                        onValueChange = { y -> if (y.all { it.isDigit() } && y.length <= 12) pin = y },
                        label = if (onarimKipi) stringResource(R.string.settings_label_current_pin)
                        else stringResource(R.string.settings_label_pin),
                        numeric = true
                    )
                }
                if (pinGerekli && !onarimKipi) {
                    SecretField(
                        value = pin2,
                        onValueChange = { y -> if (y.all { it.isDigit() } && y.length <= 12) pin2 = y },
                        label = stringResource(R.string.settings_label_pin_again),
                        numeric = true
                    )
                }
                viewModel.error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when {
                        onarimKipi -> viewModel.migrateLegacyPin(parola, pin) { if (it) onDismiss() }
                        kapatmaKipi -> viewModel.disablePin(parola) { if (it) onDismiss() }
                        else -> viewModel.enablePin(parola, pin) { if (it) onDismiss() }
                    }
                },
                enabled = gecerli && !viewModel.busy
            ) {
                Text(
                    when {
                        onarimKipi -> stringResource(R.string.settings_repair)
                        kapatmaKipi -> stringResource(R.string.settings_turn_off)
                        else -> stringResource(R.string.settings_set)
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
        }
    )
}