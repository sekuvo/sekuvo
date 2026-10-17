package com.afgover.vault.autofill

import android.app.assist.AssistStructure
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.view.autofill.AutofillManager
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.IntentCompat
import androidx.fragment.app.FragmentActivity
import com.afgover.vault.VaultApp
import com.afgover.vault.core.VaultSession
import com.afgover.vault.ui.BiometricAuth
import com.afgover.vault.ui.LockState
import com.afgover.vault.ui.VaultViewModel
import com.afgover.vault.ui.screens.UnlockScreen
import com.afgover.vault.ui.theme.VaultTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.afgover.vault.core.AppLocale
import android.content.Context

/**
 * Otomatik doldurma menüsünde "kilidi aç" seçildiğinde açılan ekran.
 * Kilit açıldıktan sonra doldurma seçeneklerini üretip sisteme döndürür;
 * kasa kapalıyken sisteme hiçbir kayıt verilmez.
 */
class AutofillUnlockActivity : FragmentActivity() {

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

        val structure = IntentCompat.getParcelableExtra(
            intent,
            AutofillManager.EXTRA_ASSIST_STRUCTURE,
            AssistStructure::class.java
        )
        if (structure == null) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        setContent {
            VaultTheme {
                if (viewModel.lockState == LockState.UNLOCKED) {
                    LaunchedEffect(Unit) { deliver(structure) }
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
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

    private suspend fun deliver(structure: AssistStructure) {
        val key = VaultSession.key()
        val parsed = StructureParser.parse(structure)
        val response = if (key == null) {
            null
        } else {
            val entries = withContext(Dispatchers.IO) {
                VaultApp.from(this@AutofillUnlockActivity).repository.getAllDecrypted(key)
            }
            AutofillResponses.fillResponse(this, parsed, entries)
        }
        if (response == null) {
            setResult(RESULT_CANCELED)
        } else {
            setResult(
                RESULT_OK,
                Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, response)
            )
        }
        finish()
    }
}
