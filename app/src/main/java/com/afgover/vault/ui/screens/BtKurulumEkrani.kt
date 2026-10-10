package com.afgover.vault.ui.screens

import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.Warning
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.afgover.vault.R
import com.afgover.vault.bt.BtCihazListesi
import com.afgover.vault.bt.BtHidManager
import com.afgover.vault.bt.BtKurulumAdimi
import com.afgover.vault.bt.BtKurulumDurumu
import com.afgover.vault.bt.HidLayouts
import com.afgover.vault.bt.PcSistemi
import com.afgover.vault.bt.duzenOnerisi
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bluetooth kurulum sihirbazı: telefonu bilgisayara klavye olarak ilk kez
 * bağlamayı adım adım, CANLI yürütür.
 *
 * "Canlı" iki anlama geliyor: adım, telefonun gerçek durumundan türetilir
 * ([BtKurulumDurumu]) — eşleşme ya da bağlantı algılanınca sihirbaz
 * kendiliğinden ilerler, bağlantı koparsa geri döner; ve son adımda
 * bilgisayara gerçekten bir deneme yazısı yazılır, kullanıcı ekranda
 * çıkanı telefondakiyle karşılaştırır. Bu yazı aynı zamanda klavye düzeni
 * testidir: yanlış düzen, eşleştirmeden sonra en sık yaşanan sorun.
 *
 * Telefonu görünür yapmak için yeni bir izin istenmez: sistemin Bluetooth
 * ayarları açıkken telefon zaten görünürdür, sihirbaz oraya götürür.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BtKurulumEkrani(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("vault_settings", Context.MODE_PRIVATE) }

    val izinGerekli = Build.VERSION.SDK_INT >= 31
    fun izinVarMi() = !izinGerekli || ContextCompat.checkSelfPermission(
        context, Manifest.permission.BLUETOOTH_CONNECT
    ) == PackageManager.PERMISSION_GRANTED

    var izin by remember { mutableStateOf(izinVarMi()) }
    var izinReddedildi by remember { mutableStateOf(false) }
    var btAcik by remember { mutableStateOf(izin && BtHidManager.isBluetoothOn(context)) }
    val izinIste = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { verildi ->
        izin = verildi
        izinReddedildi = !verildi
        if (verildi) btAcik = BtHidManager.isBluetoothOn(context)
    }
    val btAcIste = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        btAcik = BtHidManager.isBluetoothOn(context)
    }

    var sistem by remember { mutableStateOf<PcSistemi?>(null) }
    // Eşleştirme adımının durumu burada tutulur, adımın içinde değil: Sekuvo
    // arka plandayken sistem klavye kaydını siler ve adım bir an değişebilir;
    // içeride tutulsa kullanıcı ayarlardan döndüğünde seçtiği yol ve "ayara
    // gittim" bilgisi kaybolurdu (ölçüldü).
    var eslestirmeYolu by remember { mutableStateOf<EslestirmeYolu?>(null) }
    var ayaraGitti by remember { mutableStateOf(false) }
    var ayardanDondu by remember { mutableStateOf(false) }

    // İzin uygulama ayarlarından, Bluetooth sistemden açılabilir: sihirbaza
    // her dönüşte ikisini de yeniden oku, yoksa ekran eski durumu gösterir.
    val yasamDongusu = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(yasamDongusu) {
        val gozcu = LifecycleEventObserver { _, olay ->
            if (olay == Lifecycle.Event.ON_RESUME) {
                izin = izinVarMi()
                btAcik = izin && BtHidManager.isBluetoothOn(context)
                if (ayaraGitti) ayardanDondu = true
            }
        }
        yasamDongusu.addObserver(gozcu)
        onDispose { yasamDongusu.removeObserver(gozcu) }
    }

    var bitti by remember { mutableStateOf(false) }
    /** Bu sihirbaz açıkken yeni eşleşen cihaz: bilgisayar budur. */
    var eslesen by remember { mutableStateOf<BluetoothDevice?>(null) }
    /** Eşleşmiş cihaz listesi değiştikçe artar (liste yeniden okunur). */
    var eslesmeSurumu by remember { mutableIntStateOf(0) }

    // Bluetooth açılıp kapanması ve yeni eşleşmeler sistem yayınıyla gelir.
    DisposableEffect(izin) {
        if (!izin) return@DisposableEffect onDispose { }
        val alici = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothAdapter.ACTION_STATE_CHANGED -> btAcik = BtHidManager.isBluetoothOn(context)
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        eslesmeSurumu++
                        val durum = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                        val cihaz = IntentCompat.getParcelableExtra(
                            intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java
                        )
                        if (durum == BluetoothDevice.BOND_BONDED && cihaz != null) {
                            // Bilgisayar klavye bağlantısını kendisi başlatırsa
                            // reddedilmesin: beklenen cihaz artık bu.
                            BtHidManager.expect(cihaz)
                            eslesen = cihaz
                        }
                    }
                }
            }
        }
        val filtre = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(context, alici, filtre, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(alici) } }
    }

    // Telefonu klavye olarak duyur; Bluetooth sonradan açılırsa yeniden dene.
    LaunchedEffect(izin, btAcik) {
        if (izin && btAcik && Build.VERSION.SDK_INT >= 28) BtHidManager.start(context)
    }
    HidKaydiniCanliTut(izin && btAcik)
    val iptal = remember { AtomicBoolean(false) }
    DisposableEffect(Unit) {
        onDispose {
            iptal.set(true)
            BtHidManager.stop()
        }
    }

    val hid by BtHidManager.state.collectAsState()
    val hata by BtHidManager.lastError.collectAsState()

    // Bağlantı kurulduğunda bilgisayar "son kullanılan" olur: 💻 listesinde başa geçer.
    LaunchedEffect(hid) {
        if (hid is BtHidManager.State.Connected) {
            BtHidManager.connectedAddress()?.let { BtCihazListesi.kullanildi(prefs, it) }
        }
    }

    // Kayıt bir kez tamamlandıysa, dönüşteki kısa yeniden kayıt aralığı
    // sihirbazı hazırlık adımına geri atmasın; eşleştirme adımı o aralığı
    // kendi satırında gösterir. Bluetooth ya da izin giderse bu kabul de biter.
    var hidBirKezHazir by remember { mutableStateOf(false) }
    val hidSimdiHazir = hid is BtHidManager.State.Ready || hid is BtHidManager.State.Connecting ||
        hid is BtHidManager.State.Connected
    LaunchedEffect(hidSimdiHazir, izin, btAcik) {
        if (!izin || !btAcik) hidBirKezHazir = false
        else if (hidSimdiHazir) hidBirKezHazir = true
    }
    val durum = BtKurulumDurumu(
        destekleniyor = BtHidManager.isSupported && hid !is BtHidManager.State.Unsupported,
        sistem = sistem,
        izin = izin,
        btAcik = btAcik,
        hidHazir = hidSimdiHazir || (hidBirKezHazir &&
            (hid is BtHidManager.State.Idle || hid is BtHidManager.State.Registering)),
        bagli = hid is BtHidManager.State.Connected,
        bitti = bitti
    )
    val adim = durum.adim
    val bilgisayarAdi = (hid as? BtHidManager.State.Connected)?.name

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setup_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.setup_close))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            if (adim != BtKurulumAdimi.DESTEKLENMIYOR) {
                Text(
                    stringResource(R.string.setup_step_of, adim.sira, BtKurulumAdimi.TOPLAM),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { adim.sira / BtKurulumAdimi.TOPLAM.toFloat() },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
            }
            BtKurulumSahnesi(adim, sistem, eslesti = eslesen != null)
            Spacer(Modifier.height(16.dp))

            when (adim) {
                BtKurulumAdimi.SISTEM -> SistemAdimi(
                    sonSecim = PcSistemi.ofStable(prefs.getString("pc_os", null)),
                    onSec = {
                        sistem = it
                        prefs.edit().putString("pc_os", it.stable).apply()
                    }
                )

                BtKurulumAdimi.HAZIRLIK -> HazirlikAdimi(
                    izin = izin,
                    izinReddedildi = izinReddedildi,
                    btAcik = btAcik,
                    hidHazir = durum.hidHazir,
                    onIzinIste = { izinIste.launch(Manifest.permission.BLUETOOTH_CONNECT) },
                    onUygulamaAyarlari = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(Uri.fromParts("package", context.packageName, null))
                        )
                    },
                    onBtAc = { btAcIste.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                )

                BtKurulumAdimi.ESLESTIRME -> EslestirmeAdimi(
                    sistem = sistem!!,
                    hid = hid,
                    hataVar = hata?.res == R.string.bt_err_connect,
                    eslesen = eslesen,
                    eslesmeSurumu = eslesmeSurumu,
                    prefs = prefs,
                    yol = eslestirmeYolu,
                    onYol = { eslestirmeYolu = it },
                    dondu = ayardanDondu,
                    onAyaraGit = {
                        ayaraGitti = true
                        ayardanDondu = false
                        // Telefonun görünür olacağı ekran: sistem Bluetooth
                        // ayarları. Doğrudan "yeni cihaz eşle" ekranı üçüncü
                        // taraf uygulamalara kapalı (ölçüldü: Permission Denial).
                        runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                    },
                    onSistemiDegistir = { sistem = null }
                )

                BtKurulumAdimi.DENEME -> DenemeAdimi(
                    sistem = sistem!!,
                    bilgisayarAdi = bilgisayarAdi.orEmpty(),
                    prefs = prefs,
                    iptal = iptal,
                    onBitti = { bitti = true }
                )

                BtKurulumAdimi.BITTI -> BittiAdimi(
                    bilgisayarAdi = bilgisayarAdi,
                    prefs = prefs,
                    onBitir = onBack
                )

                BtKurulumAdimi.DESTEKLENMIYOR -> {
                    Text(stringResource(R.string.bt_unsupported_body), style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.setup_close))
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AdimBasligi(baslik: String, aciklama: String? = null) {
    Text(baslik, style = MaterialTheme.typography.headlineSmall)
    if (aciklama != null) {
        Spacer(Modifier.height(6.dp))
        Text(aciklama, style = MaterialTheme.typography.bodyMedium)
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun SistemAdimi(sonSecim: PcSistemi?, onSec: (PcSistemi) -> Unit) {
    AdimBasligi(stringResource(R.string.setup_os_title), stringResource(R.string.setup_os_body))
    PcSistemi.entries.forEach { s ->
        OutlinedCard(
            border = BorderStroke(
                if (s == sonSecim) 2.dp else 1.dp,
                if (s == sonSecim) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clickable { onSec(s) }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
                Icon(s.ikon(), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Text(stringResource(s.adRes), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (s == sonSecim) {
                    Text(
                        stringResource(R.string.setup_os_last),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

private fun PcSistemi.ikon(): ImageVector = when (this) {
    PcSistemi.WINDOWS -> Icons.Filled.DesktopWindows
    PcSistemi.MACOS, PcSistemi.CHROMEOS -> Icons.Filled.Laptop
    PcSistemi.LINUX -> Icons.Filled.Computer
}

/** Canlı kontrol satırı: tamamsa ✓, sürüyorsa dönen gösterge, değilse boş daire. */
@Composable
private fun KontrolSatiri(metin: String, tamam: Boolean, suruyor: Boolean = false, eylem: (@Composable () -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when {
                tamam -> Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                suruyor -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else -> Icon(
                    Icons.Filled.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(metin, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        eylem?.invoke()
    }
}

@Composable
private fun HazirlikAdimi(
    izin: Boolean,
    izinReddedildi: Boolean,
    btAcik: Boolean,
    hidHazir: Boolean,
    onIzinIste: () -> Unit,
    onUygulamaAyarlari: () -> Unit,
    onBtAc: () -> Unit
) {
    AdimBasligi(stringResource(R.string.setup_prep_title), stringResource(R.string.setup_prep_body))
    KontrolSatiri(stringResource(R.string.setup_prep_permission), tamam = izin) {
        if (!izin) Button(onClick = onIzinIste) { Text(stringResource(R.string.setup_prep_permission_button)) }
    }
    if (!izin && izinReddedildi) {
        Text(
            stringResource(R.string.setup_prep_permission_denied),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        TextButton(onClick = onUygulamaAyarlari) { Text(stringResource(R.string.setup_prep_open_app_settings)) }
    }
    KontrolSatiri(stringResource(R.string.setup_prep_bt_on), tamam = btAcik) {
        if (izin && !btAcik) Button(onClick = onBtAc) { Text(stringResource(R.string.setup_prep_bt_on_button)) }
    }
    KontrolSatiri(
        stringResource(R.string.setup_prep_hid),
        tamam = hidHazir,
        suruyor = izin && btAcik && !hidHazir
    )
    // Kayıt birkaç saniyeden uzun sürüyorsa çoğunlukla Bluetooth yığını
    // takılmıştır; kapatıp açmak çözer.
    var uzunSurdu by remember { mutableStateOf(false) }
    LaunchedEffect(izin, btAcik) {
        uzunSurdu = false
        if (izin && btAcik) {
            delay(8_000)
            uzunSurdu = true
        }
    }
    if (uzunSurdu && !hidHazir) {
        Text(
            stringResource(R.string.setup_prep_hid_slow),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}

private enum class EslestirmeYolu { MEVCUT, YENI }

@Composable
private fun EslestirmeAdimi(
    sistem: PcSistemi,
    hid: BtHidManager.State,
    hataVar: Boolean,
    eslesen: BluetoothDevice?,
    eslesmeSurumu: Int,
    prefs: android.content.SharedPreferences,
    yol: EslestirmeYolu?,
    onYol: (EslestirmeYolu) -> Unit,
    dondu: Boolean,
    onAyaraGit: () -> Unit,
    onSistemiDegistir: () -> Unit
) {
    val context = LocalContext.current
    AdimBasligi(stringResource(R.string.setup_pair_title))
    // Sekuvo'ya dönüldüğünde klavye kaydı birkaç yüz milisaniye içinde
    // yeniden yapılır; o aralık burada görünür, adım değişmez.
    if (hid is BtHidManager.State.Idle || hid is BtHidManager.State.Registering) {
        KontrolSatiri(stringResource(R.string.setup_prep_hid), tamam = false, suruyor = true)
    }
    // Daha önce eşleşmiş bir bilgisayar varsa önce yol sorulur; yoksa
    // doğrudan yeni eşleştirmeye geçilir.
    val eslesmisBilgisayarVar = remember(eslesmeSurumu) {
        BtHidManager.bondedDevices().any { (_, d) -> BtHidManager.deviceType(d).bilgisayarMi }
    }
    val etkinYol = yol ?: if (eslesmisBilgisayarVar) null else EslestirmeYolu.YENI

    when (etkinYol) {
        null -> {
            Text(stringResource(R.string.setup_pair_choice_body), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { onYol(EslestirmeYolu.MEVCUT) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.setup_pair_existing))
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { onYol(EslestirmeYolu.YENI) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.setup_pair_new))
            }
        }

        EslestirmeYolu.MEVCUT -> {
            Text(stringResource(R.string.setup_pair_pick), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            BtCihazSecimi(prefs = prefs, yenile = eslesmeSurumu) { _, d -> baglan(d) }
            BaglantiDurumu(hid)
            if (hataVar) SorunGiderme(onTekrar = null)
            TextButton(onClick = { onYol(EslestirmeYolu.YENI) }) {
                Text(stringResource(R.string.setup_pair_not_listed))
            }
        }

        EslestirmeYolu.YENI -> {
            // Telefonun bilgisayarda hangi adla görüneceği: listede bunu arayacak.
            Text(stringResource(R.string.setup_pair_phone_name), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                BtHidManager.phoneName(context) ?: Build.MODEL,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(12.dp))

            // Sıra önemli ve ölçüldü: Android, Sekuvo arka plana düşünce
            // klavye kaydını siler. Telefon yalnız sistemin eşleştirme ekranı
            // açıkken görünür, ama o sırada Sekuvo arka planda. Bu yüzden
            // bilgisayar telefonu listesinde GÖRDÜKTEN sonra kullanıcı
            // Sekuvo'ya döner; eşleştirme (bilgisayarın tıklaması) ancak o
            // zaman yapılır. Telefon görünür olmaktan çıksa da bağlanabilir
            // kalır, telefonu zaten bulmuş bilgisayar eşleştirmeyi başlatabilir.
            EslestirmeAdimKarti(no = 1, baslik = stringResource(R.string.setup_pair_step1, stringResource(sistem.adRes))) {
                Text(stringResource(sistem.aramaRes), style = MaterialTheme.typography.bodyMedium)
            }
            EslestirmeAdimKarti(no = 2, baslik = stringResource(R.string.setup_pair_step2)) {
                OutlinedCard(
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(12.dp)) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                stringResource(R.string.setup_pair_visible_warning_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.setup_pair_visible_warning),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onAyaraGit,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.setup_pair_make_visible)) }
            }
            EslestirmeAdimKarti(
                no = 3,
                baslik = stringResource(R.string.setup_pair_step3),
                vurgulu = dondu && eslesen == null
            ) {
                if (dondu && eslesen == null) {
                    Text(
                        stringResource(R.string.setup_pair_returned),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Text(stringResource(R.string.setup_pair_step3_body), style = MaterialTheme.typography.bodyMedium)
                if (sistem == PcSistemi.MACOS) {
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.setup_pair_macos_assistant), style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(12.dp))

            // 4) Canlı durum: eşleşme bekleniyor → eşleşti → bağlanıyor.
            var baglanamadi by remember { mutableStateOf(false) }
            LaunchedEffect(eslesen) {
                val cihaz = eslesen ?: return@LaunchedEffect
                baglanamadi = false
                // Bilgisayar bağlantıyı çoğu zaman kendisi başlatır; birkaç
                // saniye fırsat tanı, gelmezse telefondan bağlan.
                delay(2_500)
                if (BtHidManager.state.value is BtHidManager.State.Ready) baglan(cihaz)
                delay(15_000)
                if (BtHidManager.state.value !is BtHidManager.State.Connected) baglanamadi = true
            }
            if (eslesen == null) {
                KontrolSatiri(stringResource(R.string.setup_pair_waiting), tamam = false, suruyor = true)
            } else {
                KontrolSatiri(
                    stringResource(R.string.setup_pair_bonded, BtHidManager.displayName(eslesen)),
                    tamam = true
                )
                BaglantiDurumu(hid)
            }
            if (baglanamadi || hataVar) {
                SorunGiderme(onTekrar = eslesen?.let { c -> { baglan(c) } })
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = onSistemiDegistir) {
        Text(stringResource(R.string.setup_change_os, stringResource(sistem.adRes)))
    }
}

@Composable
private fun BaglantiDurumu(hid: BtHidManager.State) {
    if (hid is BtHidManager.State.Connecting) {
        KontrolSatiri(stringResource(R.string.bt_connecting, hid.name), tamam = false, suruyor = true)
    }
}

/** Eşleşti ama klavye bağlantısı kurulmadıysa en sık sebep ve çözümü. */
@Composable
private fun SorunGiderme(onTekrar: (() -> Unit)?) {
    OutlinedCard(
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                stringResource(R.string.setup_pair_trouble_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.setup_pair_trouble_body), style = MaterialTheme.typography.bodySmall)
            if (onTekrar != null) {
                TextButton(onClick = onTekrar) { Text(stringResource(R.string.setup_pair_retry)) }
            }
        }
    }
}

private enum class DenemeCevabi { AYNI, FARKLI, HICBIR_SEY }

@Composable
private fun DenemeAdimi(
    sistem: PcSistemi,
    bilgisayarAdi: String,
    prefs: android.content.SharedPreferences,
    iptal: AtomicBoolean,
    onBitti: () -> Unit
) {
    AdimBasligi(stringResource(R.string.setup_test_title))
    Text(
        stringResource(R.string.bt_connected, bilgisayarAdi),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.titleSmall
    )
    Spacer(Modifier.height(8.dp))
    Text(stringResource(sistem.notDefteriRes), style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(12.dp))

    val yerel = Locale.getDefault()
    var duzen by remember {
        mutableStateOf(duzenOnerisi(prefs.getString("pc_layout", null), yerel.language, yerel.country))
    }
    val hiz = remember {
        runCatching { BtHidManager.Speed.valueOf(prefs.getString("pc_speed", "FAST") ?: "FAST") }
            .getOrDefault(BtHidManager.Speed.FAST)
    }
    var menuAcik by remember { mutableStateOf(false) }
    var yaziyor by remember { mutableStateOf(false) }
    var yazildi by remember { mutableStateOf(false) }
    var cevap by remember { mutableStateOf<DenemeCevabi?>(null) }
    var yazilamayan by remember { mutableStateOf("") }
    val kapsam = rememberCoroutineScope()

    Text(stringResource(R.string.setup_test_layout), style = MaterialTheme.typography.titleSmall)
    Box {
        OutlinedButton(onClick = { menuAcik = true }, enabled = !yaziyor) {
            Text(stringResource(duzen.labelRes))
            Icon(Icons.Filled.ExpandMore, contentDescription = null)
        }
        DropdownMenu(expanded = menuAcik, onDismissRequest = { menuAcik = false }) {
            HidLayouts.Layout.entries.forEach { l ->
                DropdownMenuItem(
                    text = { Text(stringResource(l.labelRes)) },
                    onClick = {
                        duzen = l
                        menuAcik = false
                        cevap = null
                    }
                )
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = {
            kapsam.launch {
                yaziyor = true
                cevap = null
                // 💻 ekranıyla aynı: kullanıcı imleci yerleştirsin diye 1 sn.
                delay(1_000)
                val sonuc = withContext(Dispatchers.IO) {
                    if (Build.VERSION.SDK_INT >= 28) {
                        BtHidManager.typeText(HidLayouts.LAYOUT_TEST_TEXT, duzen, hiz, isCancelled = { iptal.get() })
                    } else {
                        BtHidManager.TypeResult(0, emptyList(), 0L, aborted = true)
                    }
                }
                yazilamayan = sonuc.untyped.joinToString(" ")
                yaziyor = false
                yazildi = true
            }
        },
        enabled = !yaziyor,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (yaziyor) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        else Text(stringResource(R.string.setup_test_type))
    }
    Text(stringResource(R.string.bt_before_start), style = MaterialTheme.typography.bodySmall)

    if (yazildi && !yaziyor) {
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.setup_test_expect), style = MaterialTheme.typography.titleSmall)
        Text(
            HidLayouts.LAYOUT_TEST_TEXT,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(vertical = 6.dp)
        )
        if (yazilamayan.isNotEmpty()) {
            Text(
                stringResource(R.string.bt_untyped_warning, yazilamayan),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                // Doğrulanan düzen kalıcı olur ve 💻 ekranında açık düzenlere eklenir.
                val acik = HidLayouts.enabledLayouts(prefs.getString("pc_layouts_off", null)) + duzen
                prefs.edit()
                    .putString("pc_layout", duzen.name)
                    .putString("pc_layouts_off", HidLayouts.storeDisabled(acik))
                    .apply()
                onBitti()
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.setup_test_same)) }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { cevap = DenemeCevabi.FARKLI }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.setup_test_different))
            }
            OutlinedButton(onClick = { cevap = DenemeCevabi.HICBIR_SEY }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.setup_test_nothing))
            }
        }
        when (cevap) {
            DenemeCevabi.FARKLI -> Text(
                stringResource(R.string.setup_test_different_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            DenemeCevabi.HICBIR_SEY -> Text(
                stringResource(R.string.setup_test_nothing_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            else -> Unit
        }
    }
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = onBitti, enabled = !yaziyor) { Text(stringResource(R.string.setup_test_skip)) }
}

@Composable
private fun BittiAdimi(bilgisayarAdi: String?, prefs: android.content.SharedPreferences, onBitir: () -> Unit) {
    AdimBasligi(stringResource(R.string.setup_done_title))
    if (bilgisayarAdi != null) {
        Text(stringResource(R.string.setup_done_body, bilgisayarAdi), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
    }
    Text(
        stringResource(
            R.string.setup_done_layout,
            stringResource(HidLayouts.readLayout(prefs.getString("pc_layout", null)).labelRes)
        ),
        style = MaterialTheme.typography.bodyMedium
    )
    // Bilgisayar favori yapılırsa 💻 listesinde her zaman en üstte durur.
    val adres = remember { BtHidManager.connectedAddress() }
    if (adres != null) {
        var yildizli by remember { mutableStateOf(adres in BtCihazListesi.yildizlilar(prefs)) }
        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable {
                BtCihazListesi.yildizDegistir(prefs, adres)
                yildizli = !yildizli
            }
        ) {
            Icon(
                if (yildizli) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.setup_done_star), style = MaterialTheme.typography.bodyLarge)
        }
    }
    Spacer(Modifier.height(16.dp))
    Button(onClick = onBitir, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.setup_done_finish))
    }
}

/** Eşleştirme bölümündeki numaralı adım; [vurgulu] ise sıradaki adım budur. */
@Composable
private fun EslestirmeAdimKarti(
    no: Int,
    baslik: String,
    vurgulu: Boolean = false,
    icerik: @Composable () -> Unit
) {
    OutlinedCard(
        border = BorderStroke(
            if (vurgulu) 2.dp else 1.dp,
            if (vurgulu) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$no",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .size(24.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                        .wrapContentSize(Alignment.Center)
                )
                Spacer(Modifier.width(8.dp))
                Text(baslik, style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(6.dp))
            icerik()
        }
    }
}

/**
 * Bluetooth klavye (HID) Android 9 ile geldi. Sihirbaz eşleştirme ve deneme
 * adımlarına ancak HID kaydı tamamsa, yani Android 9+ üzerinde gelir; kontrol
 * bunu derleyiciye de söyler.
 */
private fun baglan(cihaz: BluetoothDevice) {
    if (Build.VERSION.SDK_INT >= 28) BtHidManager.connect(cihaz)
}
