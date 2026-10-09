package com.afgover.vault.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.ui.Alignment
import com.afgover.vault.bt.BtCihaz
import com.afgover.vault.bt.BtCihazListesi
import com.afgover.vault.bt.BtCihazTuru
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.afgover.vault.bt.BtHidManager
import com.afgover.vault.bt.HidLayouts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.afgover.vault.R
import com.afgover.vault.ui.theme.vaultButtonColors

/** Aynı geri sayım + yazma akışından geçen üç gönderim. */
private enum class Gonderim { DUZEN_TESTI, HIZ_TESTI, DEGER }

/** Bu uzunluğa kadar olan değerler yazılınca diyalog kendi kapanır. */
private const val KISA_DEGER = 100

/** "42 sn" / "2 dk 15 sn". */
private fun sureMetni(context: Context, ms: Long): String {
    val sn = ((ms + 999L) / 1000L).toInt()
    return if (sn < 60) context.getString(R.string.bt_duration_seconds, sn)
    else context.getString(R.string.bt_duration_minutes, sn / 60, sn % 60)
}

/**
 * Seçilen değeri Bluetooth klavye olarak bilgisayara yazar.
 * Akış: izin → HID kaydı → eşleşmiş cihaz seç → bağlan → 1 sn geri sayım → yaz.
 *
 * Uzun sırlarda (5.000+ karakter) yazma dakikalar sürebildiği için hız
 * seçilebilir; hızın bu bilgisayarda güvenli olduğu "hız testi" ile ölçülür.
 */
@Composable
fun BtTypeDialog(
    label: String,
    value: String,
    onDismiss: () -> Unit,
    /** Asıl değer yazıldığında hedef bilgisayarın adıyla çağrılır (günlük). */
    onTyped: (target: String) -> Unit = {}
) {
    val context = LocalContext.current

    if (!BtHidManager.isSupported) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.bt_unsupported_title)) },
            text = { Text(stringResource(R.string.bt_unsupported_body)) },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.bt_ok)) }
            }
        )
        return
    }

    val needsPermission = Build.VERSION.SDK_INT >= 31
    var permissionGranted by remember {
        mutableStateOf(
            !needsPermission || ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> permissionGranted = granted }

    LaunchedEffect(Unit) {
        if (!permissionGranted) {
            permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }
    LaunchedEffect(permissionGranted) {
        if (permissionGranted && Build.VERSION.SDK_INT >= 28) {
            BtHidManager.start(context)
        }
    }

    /** Yazma arka planda blokluyor; durdurma bu bayrakla bildirilir. */
    val iptal = remember { AtomicBoolean(false) }
    DisposableEffect(Unit) {
        onDispose {
            iptal.set(true)
            BtHidManager.stop()
        }
    }

    val state by BtHidManager.state.collectAsState()
    val error by BtHidManager.lastError.collectAsState()

    val prefs = remember {
        context.getSharedPreferences("vault_settings", Context.MODE_PRIVATE)
    }
    // Kullanıcının ayarlardan açtığı düzenler; aktarım ekranında yalnız bunlar
    // görünür (istemediği düzen yoluna çıkmasın).
    val acikDuzenler = remember {
        HidLayouts.enabledLayouts(prefs.getString("pc_layouts_off", null))
    }
    var layout by remember {
        mutableStateOf(HidLayouts.readLayout(prefs.getString("pc_layout", null), acikDuzenler))
    }
    // Kapalı bir düzen seçiliyken açık olana düşüldüyse bunu KALICI yap: yoksa
    // tercih kapalı düzeni göstermeye devam eder ve bir dahaki açılışta yine
    // sessizce başka bir düzene düşülür (denetim).
    LaunchedEffect(Unit) {
        if (prefs.getString("pc_layout", null) != layout.name) {
            prefs.edit().putString("pc_layout", layout.name).apply()
        }
    }
    var speed by remember {
        mutableStateOf(
            runCatching {
                BtHidManager.Speed.valueOf(prefs.getString("pc_speed", "FAST") ?: "FAST")
            }.getOrDefault(BtHidManager.Speed.FAST)
        )
    }
    var countdown by remember { mutableIntStateOf(0) }
    // Cihaz listesi: yıldız değişince yeniden okunsun diye sürüm sayacı.
    var listeSurumu by remember { mutableIntStateOf(0) }
    var tumCihazlar by remember { mutableStateOf(false) }
    // Bağlantı kurulunca "son kullanılan" olarak kaydedilecek cihaz.
    var secilenAdres by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state) {
        if (state is BtHidManager.State.Connected) {
            secilenAdres?.let { BtCihazListesi.kullanildi(prefs, it) }
        }
    }
    var typing by remember { mutableStateOf(false) }

    // Uzun yazma dakikalar sürebiliyor ve kullanıcı bilgisayara bakar; ekran
    // zaman aşımı yazmayı yarıda kesip kasayı kilitler (denetim). Yazma/geri
    // sayım boyunca ekranı açık tut.
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(typing || countdown > 0) {
        val w = (view.context as? android.app.Activity)?.window
        if (typing || countdown > 0) {
            w?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            w?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { w?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    var untypedWarning by remember { mutableStateOf<String?>(null) }
    var summary by remember { mutableStateOf<String?>(null) }
    var testTyped by remember { mutableStateOf(false) }
    var hizSonucu by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Gonderim?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }

    // Üç gönderim de aynı akıştan geçer: geri sayım → yaz → sonucu bildir.
    LaunchedEffect(pending) {
        val kind = pending ?: return@LaunchedEffect
        val metin = when (kind) {
            Gonderim.DUZEN_TESTI -> HidLayouts.LAYOUT_TEST_TEXT
            Gonderim.HIZ_TESTI -> HidLayouts.SPEED_TEST_TEXT
            Gonderim.DEGER -> value
        }
        untypedWarning = null
        summary = null
        progress = 0
        total = metin.length
        iptal.set(false)
        // Bir saniye: kullanıcı imleci "Yaz"a basmadan önce yerleştiriyor;
        // üç saniye beklemek her alanda gereksiz bir duraklamaydı.
        countdown = 1
        delay(1000)
        countdown = 0
        typing = true
        val sonuc = withContext(Dispatchers.IO) {
            BtHidManager.typeText(
                metin, layout, speed,
                onProgress = { progress = it },
                isCancelled = { !isActive || iptal.get() }
            )
        }
        typing = false
        pending = null

        when {
            sonuc.cancelled -> summary = context.getString(
                R.string.bt_stopped_summary, sonuc.typed, metin.length
            )

            sonuc.aborted -> summary = context.getString(
                R.string.bt_aborted_summary, sonuc.typed, metin.length
            )

            else -> {
                if (kind == Gonderim.DUZEN_TESTI) testTyped = true
                if (kind == Gonderim.HIZ_TESTI) {
                    hizSonucu = context.getString(
                        R.string.bt_speed_result,
                        sonuc.typed,
                        sureMetni(context, sonuc.elapsedMs),
                        sonuc.charsPerSecond
                    )
                }
                if (kind == Gonderim.DEGER) {
                    onTyped(
                        (state as? BtHidManager.State.Connected)?.name
                            ?: context.getString(R.string.bt_unknown_device)
                    )
                }
                if (sonuc.untyped.isNotEmpty()) {
                    untypedWarning = context.getString(
                        R.string.bt_untyped_warning,
                        sonuc.untyped.distinct().joinToString(" ")
                    )
                } else if (kind == Gonderim.DEGER) {
                    if (value.length <= KISA_DEGER) {
                        onDismiss()
                    } else {
                        summary = context.getString(
                            R.string.bt_done_summary,
                            sonuc.typed,
                            sureMetni(context, sonuc.elapsedMs),
                            sonuc.charsPerSecond
                        )
                    }
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!typing) onDismiss() },
        title = { Text(stringResource(R.string.bt_title, label)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (!permissionGranted) {
                    Text(stringResource(R.string.bt_permission_required))
                    return@Column
                }

                when (val s = state) {
                    is BtHidManager.State.Idle,
                    is BtHidManager.State.Registering ->
                        Text(stringResource(R.string.bt_preparing))

                    is BtHidManager.State.Unsupported ->
                        Text(error?.let { stringResource(it.res, *it.args.toTypedArray()) }
                            ?: stringResource(R.string.bt_err_unsupported))

                    is BtHidManager.State.Ready -> {
                        Text(
                            stringResource(R.string.bt_pick_computer),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(8.dp))
                        val eslesmis = remember(state) {
                            BtHidManager.bondedDevices().associate { (_, d) -> BtHidManager.deviceAddress(d) to d }
                        }
                        val cihazlar = remember(state, listeSurumu) {
                            val yildizli = BtCihazListesi.yildizlilar(prefs)
                            val son = BtCihazListesi.sonKullanilanlar(prefs)
                            BtCihazListesi.sirala(
                                BtHidManager.bondedDevices().map { (ad, d) ->
                                    val adres = BtHidManager.deviceAddress(d)
                                    BtCihaz(ad, adres, BtHidManager.deviceType(d), adres in yildizli, son.indexOf(adres))
                                }
                            )
                        }
                        if (cihazlar.isEmpty()) {
                            Text(stringResource(R.string.bt_no_bonded_devices))
                        }
                        // Kulaklık, saat, araba listeyi kalabalıklaştırıyordu:
                        // varsayılan liste bilgisayarlar + kullanıcının seçtikleri.
                        val gorunen = if (tumCihazlar) cihazlar else cihazlar.filter { it.varsayilandaGorunur }
                        val gizli = cihazlar.size - cihazlar.count { it.varsayilandaGorunur }
                        if (gorunen.isEmpty() && cihazlar.isNotEmpty()) {
                            Text(
                                stringResource(R.string.bt_no_computers),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        val enSon = cihazlar.firstOrNull { it.sonKullanimSirasi == 0 }?.adres
                        gorunen.forEach { cihaz ->
                            CihazSatiri(
                                cihaz = cihaz,
                                sonKullanilan = cihaz.adres == enSon,
                                onSec = {
                                    eslesmis[cihaz.adres]?.let { d ->
                                        secilenAdres = cihaz.adres
                                        BtHidManager.connect(d)
                                    }
                                },
                                onYildiz = {
                                    BtCihazListesi.yildizDegistir(prefs, cihaz.adres)
                                    listeSurumu++
                                }
                            )
                        }
                        if (gizli > 0) {
                            TextButton(onClick = { tumCihazlar = !tumCihazlar }) {
                                Text(
                                    if (tumCihazlar) stringResource(R.string.bt_show_computers_only)
                                    else stringResource(R.string.bt_show_all_devices, gizli)
                                )
                            }
                        }
                    }

                    is BtHidManager.State.Connecting ->
                        Text(stringResource(R.string.bt_connecting, s.name))

                    is BtHidManager.State.Connected -> {
                        Text(
                            stringResource(R.string.bt_connected, s.name),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.bt_layout_heading),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            stringResource(R.string.bt_layout_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(4.dp))
                        // Yedi düzen tek satıra sığmıyor; kaydırılabilir olsun.
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            acikDuzenler.forEach { l ->
                                SecimCipi(
                                    secili = layout == l,
                                    onClick = {
                                        layout = l
                                        testTyped = false
                                        prefs.edit().putString("pc_layout", l.name).apply()
                                    },
                                    label = stringResource(l.labelRes),
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                            }
                        }
                        Text(
                            stringResource(
                                R.string.bt_selected_layout, stringResource(layout.labelRes)
                            ),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(8.dp))
                        val riskli = remember(value, layout) {
                            HidLayouts.layoutSensitiveChars(value, layout)
                        }
                        if (riskli.isNotEmpty() && !testTyped) {
                            Text(
                                stringResource(
                                    R.string.bt_sensitive_chars, riskli.joinToString(" ")
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                        OutlinedButton(
                            enabled = !typing && countdown == 0,
                            onClick = { pending = Gonderim.DUZEN_TESTI },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (testTyped) stringResource(R.string.bt_test_done)
                                else stringResource(R.string.bt_test_first)
                            )
                        }
                        if (testTyped) {
                            Text(
                                stringResource(
                                    R.string.bt_test_expected, HidLayouts.LAYOUT_TEST_TEXT
                                ),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.bt_speed_heading),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            stringResource(R.string.bt_speed_desc),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(4.dp))
                        Row {
                            BtHidManager.Speed.entries.forEach { h ->
                                SecimCipi(
                                    secili = speed == h,
                                    onClick = {
                                        speed = h
                                        hizSonucu = null
                                        prefs.edit().putString("pc_speed", h.name).apply()
                                    },
                                    label = stringResource(h.labelRes),
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                            }
                        }
                        Text(
                            stringResource(
                                R.string.bt_estimate,
                                value.length,
                                sureMetni(context, value.length * speed.perCharMs)
                            ),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(
                            enabled = !typing && countdown == 0,
                            onClick = { pending = Gonderim.HIZ_TESTI },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                stringResource(
                                    R.string.bt_speed_test_button,
                                    HidLayouts.SPEED_TEST_TEXT.length
                                )
                            )
                        }
                        hizSonucu?.let {
                            Text(
                                stringResource(
                                    R.string.bt_speed_result_detail,
                                    it,
                                    HidLayouts.SPEED_TEST_BLOCK
                                ),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        Spacer(Modifier.height(12.dp))
                        when {
                            typing -> {
                                Text(
                                    stringResource(
                                        R.string.bt_typing_progress, progress, total
                                    )
                                )
                                Spacer(Modifier.height(4.dp))
                                LinearProgressIndicator(
                                    progress = {
                                        if (total == 0) 0f else progress / total.toFloat()
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    stringResource(
                                        R.string.bt_remaining,
                                        sureMetni(
                                            context, (total - progress) * speed.perCharMs
                                        )
                                    ),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            countdown > 0 -> Text(
                                pluralStringResource(R.plurals.bt_countdown, countdown, countdown),
                                color = MaterialTheme.colorScheme.primary
                            )
                            else -> Text(
                                stringResource(R.string.bt_before_start),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                summary?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it)
                }
                untypedWarning?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                if (state !is BtHidManager.State.Unsupported && error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(error!!.res, *error!!.args.toTypedArray()),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            if (state is BtHidManager.State.Connected) {
                Button(
                    colors = vaultButtonColors(),
                    enabled = !typing && countdown == 0,
                    onClick = { pending = Gonderim.DEGER }
                ) {
                    Text(
                        if (countdown > 0) stringResource(R.string.bt_countdown_short, countdown)
                        else stringResource(R.string.bt_type)
                    )
                }
            }
        },
        dismissButton = {
            if (typing) {
                TextButton(onClick = { iptal.set(true) }) {
                    Text(stringResource(R.string.bt_stop))
                }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.bt_close)) }
            }
        }
    )
}

/** Cihaz listesinde tek satır: tür ikonu · ad (+ "son kullanılan") · yıldız. */
@Composable
private fun CihazSatiri(
    cihaz: BtCihaz,
    sonKullanilan: Boolean,
    onSec: () -> Unit,
    onYildiz: () -> Unit
) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clickable(onClick = onSec)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp)
        ) {
            Icon(
                when (cihaz.tur) {
                    BtCihazTuru.DIZUSTU -> Icons.Filled.Laptop
                    BtCihazTuru.MASAUSTU -> Icons.Filled.DesktopWindows
                    BtCihazTuru.TELEFON -> Icons.Filled.Smartphone
                    BtCihazTuru.DIGER -> Icons.Filled.Bluetooth
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(cihaz.ad, style = MaterialTheme.typography.bodyLarge)
                if (sonKullanilan) {
                    Text(
                        stringResource(R.string.bt_last_used),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            IconButton(onClick = onYildiz) {
                Icon(
                    if (cihaz.yildizli) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = stringResource(
                        if (cihaz.yildizli) R.string.bt_unstar else R.string.bt_star
                    ),
                    tint = if (cihaz.yildizli) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
