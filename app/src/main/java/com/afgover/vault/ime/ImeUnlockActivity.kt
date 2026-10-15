package com.afgover.vault.ime

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.fragment.app.FragmentActivity
import com.afgover.vault.ui.BiometricAuth
import com.afgover.vault.ui.LockState
import com.afgover.vault.ui.VaultViewModel
import com.afgover.vault.ui.screens.UnlockScreen
import com.afgover.vault.ui.theme.VaultTheme
import com.afgover.vault.core.AppLocale
import android.content.Context

/**
 * Vault Klavyesi'ndeki "Kilidi aç" düğmesinin açtığı ekran.
 *
 * Kilit açılır açılmaz kapanır; kullanıcı yazmakta olduğu uygulamaya geri
 * döner ve klavye listeyi tazeler. Oturum ekran kapanana kadar açık kaldığı
 * için bu ekran tek bir kez görülür.
 */
class ImeUnlockActivity : FragmentActivity() {

    // Seçili uygulama dili cihaz dilinden bağımsızdır; her Context açılışında
    // yapılandırmaya bindirilir (AppLocale).
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }


    private val viewModel: VaultViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        setContent {
            VaultTheme {
                if (viewModel.lockState == LockState.UNLOCKED) {
                    LaunchedEffect(Unit) { finish() }
                } else if (viewModel.lockState == LockState.NEEDS_PIN) {
                    // PIN açıksa bu ekranlarda da ikinci kapı gösterilir;
                    // yoksa klavye/otomatik doldurma kilidi hiç açılamazdı.
                    com.afgover.vault.ui.screens.PinScreen(viewModel)
                } else {
                    UnlockScreen(
                        viewModel = viewModel,
                        canUseBiometric = BiometricAuth.canUse(this),
                        onBiometricUnlock = {
                            BiometricAuth.unlock(
                                this, viewModel.keyManager,
                                onError = { viewModel.error = it; viewModel.refreshLockOptions() }
                            ) {
                                viewModel.onStage1Payload(it)
                            }
                        }
                    )
                }
            }
        }
    }
}
