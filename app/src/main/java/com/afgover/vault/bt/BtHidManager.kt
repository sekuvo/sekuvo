package com.afgover.vault.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import com.afgover.vault.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.afgover.vault.core.AppLocale

/**
 * Telefonu bilgisayara Bluetooth klavye (HID cihazı) olarak tanıtır ve
 * verilen metni tuş basımları halinde gönderir.
 *
 * Metin ağdan veya panodan geçmez; Bluetooth bağlantısının kendi şifrelemesi
 * içinde tuş tuş iletilir. Bilgisayara hiçbir yazılım kurulması gerekmez.
 *
 * İzinler çağıran taraf (BtTypeDialog) tarafından garanti edilir; yine de tüm
 * BT çağrıları SecurityException'a karşı korunur.
 */
@SuppressLint("MissingPermission")
object BtHidManager {

    sealed interface State {
        data object Idle : State
        data object Unsupported : State
        data object Registering : State
        /** HID uygulaması kayıtlı; cihaz seçilebilir. */
        data object Ready : State
        data class Connecting(val name: String) : State
        data class Connected(val name: String) : State
    }

    /**
     * Tuş gönderim temposu: her rapordan sonra beklenen süre. Karakter başına
     * iki rapor gider (basma + bırakma), yani maliyet [stepMs] x 2.
     *
     * Bu değerler bağlantının fiziksel sınırı değil, seçilmiş bir emniyet
     * payıdır — hangisinin çalıştığı bilgisayara ve ortama göre değişir,
     * "Hız testi" ile ölçülür.
     */
    enum class Speed(@StringRes val labelRes: Int, val stepMs: Long) {
        SAFE(R.string.bt_speed_safe, 12),
        FAST(R.string.bt_speed_fast, 5),
        TURBO(R.string.bt_speed_turbo, 2);

        /** Karakter başına yaklaşık maliyet (ms). */
        val perCharMs: Long get() = stepMs * 2
    }

    /**
     * Yazma sonucu. [cancelled] ya da [aborted] ise bilgisayardaki metin
     * EKSİKTİR; [untyped] doluysa aradan karakter atlanmıştır.
     */
    data class TypeResult(
        val typed: Int,
        val untyped: List<Char>,
        val elapsedMs: Long,
        val cancelled: Boolean = false,
        val aborted: Boolean = false
    ) {
        val complete: Boolean get() = !cancelled && !aborted && untyped.isEmpty()

        /** Ölçülen gerçek hız (karakter/sn). */
        val charsPerSecond: Int
            get() = if (elapsedMs <= 0L) 0 else (typed * 1000L / elapsedMs).toInt()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /**
     * Hata, METİN değil kaynak kimliği olarak yayınlanır: bu nesne bir
     * `object` ve elinde Context yok, dolayısıyla dile göre metin üretemez.
     * Çeviriyi arayüz yapar (yerelleştirme).
     */
    data class BtError(@StringRes val res: Int, val args: List<Any> = emptyList())

    private val _lastError = MutableStateFlow<BtError?>(null)
    val lastError: StateFlow<BtError?> = _lastError

    private var hid: BluetoothHidDevice? = null
    private var connectedDevice: BluetoothDevice? = null
    /** Kullanıcının bu oturumda 'Bağlan' dediği cihaz; yalnız buna yazılır. */
    private var intendedDevice: BluetoothDevice? = null
    private var adapter: BluetoothAdapter? = null

    /** Standart 8 baytlık boot klavye rapor tanımı. */
    private val KEYBOARD_DESCRIPTOR = byteArrayOf(
        0x05.toByte(), 0x01, // Usage Page (Generic Desktop)
        0x09.toByte(), 0x06, // Usage (Keyboard)
        0xA1.toByte(), 0x01, // Collection (Application)
        0x05.toByte(), 0x07, //   Usage Page (Key Codes)
        0x19.toByte(), 0xE0.toByte(), //   Usage Min (224)
        0x29.toByte(), 0xE7.toByte(), //   Usage Max (231)
        0x15.toByte(), 0x00, //   Logical Min (0)
        0x25.toByte(), 0x01, //   Logical Max (1)
        0x75.toByte(), 0x01, //   Report Size (1)
        0x95.toByte(), 0x08, //   Report Count (8)
        0x81.toByte(), 0x02, //   Input (Data, Var, Abs) — değiştirici tuşlar
        0x95.toByte(), 0x01, //   Report Count (1)
        0x75.toByte(), 0x08, //   Report Size (8)
        0x81.toByte(), 0x01, //   Input (Const) — ayrılmış bayt
        0x95.toByte(), 0x06, //   Report Count (6)
        0x75.toByte(), 0x08, //   Report Size (8)
        0x15.toByte(), 0x00, //   Logical Min (0)
        0x25.toByte(), 0x65, //   Logical Max (101)
        0x05.toByte(), 0x07, //   Usage Page (Key Codes)
        0x19.toByte(), 0x00, //   Usage Min (0)
        0x29.toByte(), 0x65, //   Usage Max (101)
        0x81.toByte(), 0x00, //   Input (Data, Array) — 6 tuş
        0xC0.toByte()        // End Collection
    )

    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= 28

    @RequiresApi(28)
    fun start(context: Context) {
        if (_state.value != State.Idle && _state.value != State.Unsupported) return
        _lastError.value = null
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val btAdapter = manager.adapter
        if (btAdapter == null || safe { btAdapter.isEnabled } != true) {
            _lastError.value = BtError(R.string.bt_err_off)
            return
        }
        adapter = btAdapter
        _state.value = State.Registering

        val ok = safe {
            btAdapter.getProfileProxy(
                context.applicationContext,
                object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        if (profile != BluetoothProfile.HID_DEVICE) return
                        val hidDevice = proxy as BluetoothHidDevice
                        hid = hidDevice
                        registerApp(context.applicationContext, hidDevice)
                    }

                    override fun onServiceDisconnected(profile: Int) {
                        hid = null
                        connectedDevice = null
                        _state.value = State.Idle
                    }
                },
                BluetoothProfile.HID_DEVICE
            )
        }
        if (ok != true) {
            _state.value = State.Unsupported
            _lastError.value = BtError(R.string.bt_err_unsupported)
        }
    }

    @RequiresApi(28)
    private fun registerApp(context: Context, hidDevice: BluetoothHidDevice) {
        // Bu ad ve açıklama KARŞI BİLGİSAYARDA görünür (eşleşme listesinde),
        // yani telefonun dilinde olmalı — Context burada var (yerelleştirme).
        // Buraya applicationContext geliyor ve o uygulama diline sarılmamıştır
        // (denetim): karşı bilgisayarda ad yanlış dilde görünürdü.
        val yerel = AppLocale.wrap(context)
        val sdp = BluetoothHidDeviceAppSdpSettings(
            yerel.getString(R.string.bt_sdp_name),
            yerel.getString(R.string.bt_sdp_description),
            "Sekuvo",
            BluetoothHidDevice.SUBCLASS1_KEYBOARD,
            KEYBOARD_DESCRIPTOR
        )
        val ok = safe {
            hidDevice.registerApp(
                sdp, null, null, context.mainExecutor,
                object : BluetoothHidDevice.Callback() {
                    override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                        _state.value = if (registered) State.Ready else State.Idle
                    }

                    override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
                        when (state) {
                            BluetoothProfile.STATE_CONNECTED -> {
                                // Yalnız kullanıcının seçtiği cihazı kabul et:
                                // eşleşmiş başka bir host (ör. Windows) kendiliğinden
                                // bağlanırsa sır ona gitmesin (denetim).
                                if (intendedDevice == null || device == intendedDevice) {
                                    connectedDevice = device
                                    _state.value = State.Connected(deviceName(device))
                                } else {
                                    safe { hidDevice.disconnect(device) }
                                }
                            }
                            BluetoothProfile.STATE_CONNECTING ->
                                if (intendedDevice == null || device == intendedDevice)
                                    _state.value = State.Connecting(deviceName(device))
                            BluetoothProfile.STATE_DISCONNECTED,
                            BluetoothProfile.STATE_DISCONNECTING -> {
                                if (connectedDevice == device) connectedDevice = null
                                if (_state.value !is State.Idle) _state.value = State.Ready
                            }
                        }
                    }
                }
            )
        }
        if (ok != true) {
            _state.value = State.Unsupported
            _lastError.value = BtError(R.string.bt_err_register)
        }
    }

    /** Eşleştirilmiş cihazlar (bilgisayarını buradan seçersin). */
    fun bondedDevices(): List<Pair<String, BluetoothDevice>> =
        safe {
            adapter?.bondedDevices?.map { deviceName(it) to it }?.sortedBy { it.first }
        } ?: emptyList()

    /** Cihazın türü (ikon ve bilgisayar süzgeci için); okunamazsa [BtCihazTuru.DIGER]. */
    fun deviceType(device: BluetoothDevice): BtCihazTuru =
        safe { BtCihazTuru.sinifindan(device.bluetoothClass) } ?: BtCihazTuru.DIGER

    /** Cihazın kalıcı kimliği (yıldız ve son kullanılan kaydı için). */
    fun deviceAddress(device: BluetoothDevice): String = safe { device.address }.orEmpty()

    @RequiresApi(28)
    fun connect(device: BluetoothDevice) {
        _lastError.value = null
        intendedDevice = device
        _state.value = State.Connecting(deviceName(device))
        val ok = safe { hid?.connect(device) }
        if (ok != true) {
            _state.value = State.Ready
            _lastError.value = BtError(R.string.bt_err_connect)
        }
    }

    /**
     * Raporu gönderir; kuyruk doluysa katlanarak geri çekilip tekrar dener.
     * Toplam bekleme 2+4+8+16+32+64 = 126 ms — bilgisayarların en kısa tuş
     * tekrarı gecikmesinin (~250 ms) altında kalır, yani basılı tuş bu
     * denemeler sırasında kendi kendine tekrarlamaz.
     */
    @RequiresApi(28)
    private fun sendReport(
        hidDevice: BluetoothHidDevice,
        device: BluetoothDevice,
        report: ByteArray
    ): Boolean {
        var bekleme = 2L
        repeat(6) {
            if (safe { hidDevice.sendReport(device, 0, report) } == true) return true
            Thread.sleep(bekleme)
            bekleme *= 2
        }
        return false
    }

    /**
     * Metni tuş basımları olarak gönderir. Arka plan iş parçacığında çağrılmalı.
     *
     * Her karakter iki rapordur ve her rapordan sonra [speed] kadar beklenir.
     * Tempo bağlantının taşıyabileceğinden hızlıysa gönderim kuyruğu dolar:
     * o durumda [sendReport] geri çekilip tekrar dener, yine olmuyorsa yazma
     * DURUR ve [TypeResult.aborted] ile bildirilir. Eskiden `sendReport`'un
     * dönüşü yutuluyordu — başarısız rapor sessizce kaybolur, 5.000 karakterlik
     * bir sırda eksik karakter fark edilmezdi.
     *
     * @param onProgress yazılan karakter sayısı (her karakterde değil, ~25'te bir)
     * @param isCancelled true dönerse yazma bırakılır (tuş basılı bırakılmaz)
     */
    @RequiresApi(28)
    fun typeText(
        text: String,
        layout: HidLayouts.Layout,
        speed: Speed = Speed.FAST,
        onProgress: (Int) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ): TypeResult {
        val hidDevice = hid
        val device = connectedDevice
        if (hidDevice == null || device == null) {
            _lastError.value = BtError(R.string.bt_err_no_link)
            return TypeResult(0, text.toList(), 0L, aborted = true)
        }
        _lastError.value = null   // önceki denemenin hatası ekranda kalmasın
        val map = HidLayouts.map(layout)
        val untyped = mutableListOf<Char>()
        val release = ByteArray(8)
        val basladi = SystemClock.elapsedRealtime()
        var typed = 0

        fun gecen() = SystemClock.elapsedRealtime() - basladi

        fun birak() {
            // Hangi yoldan çıkarsak çıkalım tuş basılı kalmamalı: kalırsa
            // bilgisayar tuş tekrarına girer ve metne çöp karakter ekler.
            // Abort'un sebebi zaten kuyruğun dolu olmasıdır; tek deneme yetmez,
            // geri çekilmeli gönderim kullan ve son çare bağlantıyı kopar (denetim).
            if (!sendReport(hidDevice, device, release)) {
                safe { hidDevice.disconnect(device) }
            }
        }

        for ((i, ch) in text.withIndex()) {
            if (isCancelled()) {
                birak()
                return TypeResult(typed, untyped, gecen(), cancelled = true)
            }
            val stroke = map[ch]
            if (stroke == null) {
                untyped.add(ch)
                continue
            }
            val press = byteArrayOf(
                stroke.modifier.toByte(), 0,
                stroke.usage.toByte(), 0, 0, 0, 0, 0
            )
            if (!sendReport(hidDevice, device, press)) {
                birak()
                _lastError.value = BtError(R.string.bt_err_queue, listOf(typed))
                return TypeResult(typed, untyped, gecen(), aborted = true)
            }
            Thread.sleep(speed.stepMs)
            if (!sendReport(hidDevice, device, release)) {
                birak()
                _lastError.value = BtError(R.string.bt_err_queue, listOf(typed))
                return TypeResult(typed, untyped, gecen(), aborted = true)
            }
            Thread.sleep(speed.stepMs)
            typed++
            if (i % 25 == 0) onProgress(typed)
        }
        onProgress(typed)
        return TypeResult(typed, untyped, gecen())
    }

    fun stop() {
        if (Build.VERSION.SDK_INT >= 28) {
            safe {
                hid?.unregisterApp()
                adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
            }
        }
        hid = null
        connectedDevice = null
        _state.value = State.Idle
    }

    private fun deviceName(device: BluetoothDevice): String =
        safe { device.name } ?: device.address ?: "Bilinmeyen cihaz"

    /** BT çağrılarını izin/durum hatalarına karşı sarar. */
    private fun <T> safe(block: () -> T): T? =
        try {
            block()
        } catch (e: SecurityException) {
            null
        } catch (e: Exception) {
            null
        }
}
