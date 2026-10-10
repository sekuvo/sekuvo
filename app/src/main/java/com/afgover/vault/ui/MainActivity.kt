package com.afgover.vault.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.afgover.vault.R
import com.afgover.vault.data.EntryType
import com.afgover.vault.ui.screens.EditScreen
import com.afgover.vault.ui.screens.DetailScreen
import com.afgover.vault.ui.screens.HomeScreen
import com.afgover.vault.ui.screens.PinScreen
import com.afgover.vault.ui.screens.SettingsScreen
import com.afgover.vault.ui.screens.UnlockScreen
import com.afgover.vault.ui.theme.VaultTheme
import com.afgover.vault.core.AppLocale
import android.content.Context
import android.os.PowerManager
import com.afgover.vault.core.VaultSession
import android.os.Build
import android.view.Display
import com.afgover.vault.core.ScreenLockPolicy
import com.afgover.vault.core.ClipClearPolicy
import com.afgover.vault.VaultApp

class MainActivity : FragmentActivity() {

    // Seçili uygulama dili cihaz dilinden bağımsızdır; her Context açılışında
    // yapılandırmaya bindirilir (AppLocale).
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }


    private val viewModel: VaultViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Ekran görüntüsü ve son uygulamalar önizlemesini engelle
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        enableEdgeToEdge()
        setContent {
            VaultTheme {
                VaultRoot(
                    viewModel = viewModel,
                    canUseBiometric = ::canUseBiometric,
                    onBiometricUnlock = ::biometricUnlock,
                    onBiometricEnable = { pin -> biometricEnable(pin) }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshLockState()
    }

    /**
     * Odak bize geri döndüğünde panoda kalan duyarlı değeri temizle.
     *
     * Temizliğin **garanti** çalıştığı tek an burası. Android 10'dan beri
     * odakta olmayan uygulama panoya dokunamıyor; kullanıcı kopyalayıp
     * uygulamadan çıktığında 45 saniyelik sayaç sessizce reddediliyordu ve
     * değer panoda kalıyordu (ölçüldü — hiç silinmemişti).
     *
     * `onResume` değil `onWindowFocusChanged`: `onResume` pencere odağı
     * verilmeden önce koşar, yani sistemin izin kontrolü o noktada henüz
     * geçmez. Odağın kaybedildiği an ise kasten kullanılmıyor — kopyalamanın
     * amacı değeri başka bir uygulamaya yapıştırmak.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) VaultApp.from(this).temizleDuyarliPano(ClipClearPolicy.An.DONUS)
    }

    /**
     * Ekran kapandığı için arka plana düştüysek YAYINI BEKLEME, burada kilitle.
     *
     * `ACTION_SCREEN_OFF` önbellekteki sürece ertelenerek teslim edilebiliyor
     * ve pratikte uygulama yeniden öne geldikten sonra geliyordu; o aralıkta
     * kasa kilitli sayılmıyordu. `onStop` ekran kapanmasıyla senkron çalışır.
     *
     * Ekran AÇIKKEN uygulamadan çıkmak kilitlemez: klavye ve otomatik doldurma
     * başka uygulamalardayken aynı oturumu kullanıyor (tasarım gereği).
     */
    override fun onStop() {
        super.onStop()
        if (ekranimGittiMi()) VaultSession.lock()
    }

    /**
     * Bu Activity'nin bulunduğu ekran kapandı mı? Katlanabilir cihazda telefonu
     * katlamak iç ekranı kapatır ama kapak ekranı açık kaldığı için cihaz
     * "uyanık" sayılır ve ekran-kapandı yayını hiç gelmez; genel etkileşim
     * durumuna bakmak bu yüzden yetmiyor (ölçüldü).
     */
    private fun ekranimGittiMi(): Boolean {
        val ekran = if (Build.VERSION.SDK_INT >= 30) {
            display
        } else {
            @Suppress("DEPRECATION") windowManager.defaultDisplay
        }
        val pm = getSystemService(PowerManager::class.java)
        return ScreenLockPolicy.shouldLock(
            displayState = ekran?.state ?: Display.STATE_OFF,
            interactive = pm?.isInteractive ?: true
        )
    }

    private fun canUseBiometric(): Boolean = BiometricAuth.canUse(this)

    private fun biometricUnlock() {
        BiometricAuth.unlock(
            this, viewModel.keyManager,
            onError = { viewModel.error = it }
        ) { viewModel.onStage1Payload(it) }
    }

    private fun biometricEnable(pin: String? = null) {
        // PIN açıkken parmak izi kaydı, ham anahtarı değil PIN ile sarılmış
        // iç sargıyı taşımalı; yoksa açılışta PIN aşaması hiç geçilemez.
        val payload = viewModel.biometricPayload(pin) ?: run {
            viewModel.error = getString(R.string.main_biometric_needs_pin)
            return
        }
        val cipher = try {
            viewModel.keyManager.biometricEncryptCipher()
        } catch (e: Exception) {
            return
        }
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val c = result.cryptoObject?.cipher
                    if (c == null) {
                        viewModel.onBiometricRegistered(false)
                        return
                    }
                    viewModel.keyManager.storeBiometricWrappedKey(c, payload)
                    viewModel.onBiometricRegistered(true)
                }

                override fun onAuthenticationError(code: Int, msg: CharSequence) {
                    // Kullanıcı vazgeçtiyse sessiz kal; gerçek hatada söyle.
                    if (code != BiometricPrompt.ERROR_USER_CANCELED &&
                        code != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    ) {
                        viewModel.error = this@MainActivity.getString(
                            R.string.main_biometric_enable_failed, msg.toString()
                        )
                    }
                    viewModel.refreshLockOptions()
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.main_biometric_enroll_title))
            .setNegativeButtonText(getString(R.string.main_cancel))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }
}

sealed interface Nav {
    data object Home : Nav
    data class Detail(val id: Long) : Nav
    data class Edit(val id: Long, val type: EntryType) : Nav
    data object Settings : Nav
    /** Araç çıpası; ana ekrandaki parmak izi düğmesinden açılır. */
    data object Anchor : Nav
    data class Info(val kind: com.afgover.vault.ui.screens.InfoKind) : Nav
    /** Uygulama tanıtımı; bitince [donus]'e dönülür. */
    data class Tanitim(val donus: Nav) : Nav
    /** Bluetooth kurulum sihirbazı; açıldığı yere ([donus]) döner. */
    data class BtKurulum(val donus: Nav) : Nav
}

@Composable
fun VaultRoot(
    viewModel: VaultViewModel,
    canUseBiometric: () -> Boolean,
    onBiometricUnlock: () -> Unit,
    onBiometricEnable: (String?) -> Unit
) {
    // Basit gezinme: durum bellekte tutulur; süreç yeniden başlarsa Home'a döner.
    // Dil değişimi Activity'yi recreate ediyor; nav burada tutulursa kullanıcı
    // Ayarlar'dan ana listeye düşerdi (denetim). ViewModel recreate'ten sağ
    // çıktığı için konum orada saklanıyor.
    var nav by remember { mutableStateOf<Nav>(viewModel.acilacakEkran as? Nav ?: Nav.Home) }
    LaunchedEffect(nav) { viewModel.acilacakEkran = nav }

    // Sistem geri tuşu üst çubuktaki ok ile aynı yere döner; uygulamadan yalnız
    // Home'dayken çıkılır. (Ok'suz sistem geri'si doğrudan uygulamayı kapatıyordu.)
    androidx.activity.compose.BackHandler(
        enabled = viewModel.lockState == LockState.UNLOCKED && nav != Nav.Home
    ) {
        nav = when (val screen = nav) {
            is Nav.Edit -> if (screen.id == 0L) Nav.Home else Nav.Detail(screen.id)
            is Nav.Info -> Nav.Settings
            is Nav.Tanitim -> screen.donus
            is Nav.BtKurulum -> screen.donus
            else -> Nav.Home
        }
    }

    if (viewModel.offerBiometric) {
        if (canUseBiometric()) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { viewModel.offerBiometric = false },
                icon = {
                    androidx.compose.material3.Icon(
                        androidx.compose.material.icons.Icons.Filled.Fingerprint,
                        contentDescription = null
                    )
                },
                title = {
                    androidx.compose.material3.Text(
                        stringResource(R.string.main_biometric_offer_title)
                    )
                },
                text = {
                    androidx.compose.material3.Text(
                        stringResource(R.string.main_biometric_offer_body)
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        viewModel.offerBiometric = false
                        onBiometricEnable(null)
                    }) {
                        androidx.compose.material3.Text(stringResource(R.string.main_enable))
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        viewModel.offerBiometric = false
                    }) {
                        androidx.compose.material3.Text(
                            stringResource(R.string.main_password_for_now)
                        )
                    }
                }
            )
        } else {
            // Cihazda biyometrik yoksa teklif hiç görünmez.
            LaunchedEffect(Unit) { viewModel.offerBiometric = false }
        }
    }

    when (viewModel.lockState) {
        LockState.NEEDS_PIN -> PinScreen(viewModel)

        LockState.NEEDS_SETUP, LockState.LOCKED -> {
            LaunchedEffect(Unit) { nav = Nav.Home }
            UnlockScreen(
                viewModel = viewModel,
                canUseBiometric = canUseBiometric(),
                onBiometricUnlock = onBiometricUnlock
            )
        }

        LockState.UNLOCKED -> {
            // Karşılamada "Yedeğim var" seçildiyse kurulum sonrası doğrudan
            // Ayarlar'a: dosya seçiciyi orası açar (bayrağı da orası tüketir).
            LaunchedEffect(Unit) {
                if (viewModel.restoreAfterSetup) nav = Nav.Settings
                // Kasa yeni kurulduysa tanıtım bir kez kendiliğinden açılır.
                if (viewModel.tanitimGoster) {
                    viewModel.tanitimGoster = false
                    nav = Nav.Tanitim(donus = Nav.Home)
                }
            }
            when (val screen = nav) {
            is Nav.Home -> HomeScreen(
                viewModel = viewModel,
                onOpen = { nav = Nav.Detail(it) },
                onAdd = { type -> nav = Nav.Edit(0L, type) },
                onSettings = { nav = Nav.Settings },
                onAnchor = { nav = Nav.Anchor }
            )

            is Nav.Detail -> DetailScreen(
                viewModel = viewModel,
                id = screen.id,
                onEdit = { id, type -> nav = Nav.Edit(id, type) },
                onBack = { nav = Nav.Home },
                onBtKurulum = { nav = Nav.BtKurulum(donus = screen) }
            )

            is Nav.Edit -> EditScreen(
                viewModel = viewModel,
                id = screen.id,
                type = screen.type,
                onBack = { nav = if (screen.id == 0L) Nav.Home else Nav.Detail(screen.id) },
                onDeleted = { nav = Nav.Home }
            )

            is Nav.Settings -> SettingsScreen(
                viewModel = viewModel,
                canUseBiometric = canUseBiometric(),
                onBiometricEnable = onBiometricEnable,
                onBack = { nav = Nav.Home },
                onInfo = { kind -> nav = Nav.Info(kind) },
                onTanitim = { nav = Nav.Tanitim(donus = Nav.Settings) },
                onBtKurulum = { nav = Nav.BtKurulum(donus = Nav.Settings) }
            )

            is Nav.Anchor -> com.afgover.vault.ui.screens.AnchorScreen(
                viewModel = viewModel,
                onBack = { nav = Nav.Home }
            )

            is Nav.Info -> com.afgover.vault.ui.screens.InfoScreen(
                kind = screen.kind,
                onBack = { nav = Nav.Settings }
            )

            is Nav.Tanitim -> com.afgover.vault.ui.screens.TanitimEkrani(
                onBitir = { nav = screen.donus },
                onBtKurulum = { nav = Nav.BtKurulum(donus = screen.donus) }
            )

            is Nav.BtKurulum -> com.afgover.vault.ui.screens.BtKurulumEkrani(
                onBack = { nav = screen.donus }
            )
            }
        }
    }
}
