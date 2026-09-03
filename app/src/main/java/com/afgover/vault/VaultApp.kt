package com.afgover.vault

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.afgover.vault.core.ClipClearPolicy
import com.afgover.vault.core.KeyManager
import com.afgover.vault.core.VaultSession
import com.afgover.vault.data.UsageLogRepository
import com.afgover.vault.data.VaultRepository
import android.hardware.display.DisplayManager
import android.view.Display

class VaultApp : Application() {

    val keyManager by lazy { KeyManager(this) }
    val usageLog by lazy { UsageLogRepository(this, keyManager) }
    val repository by lazy { VaultRepository(this, keyManager, usageLog) }

    /**
     * Kasa yalnızca ekran kapandığında kilitlenir. Klavye eklentisi süreci
     * ayakta tuttuğu için bu kayıt pratikte hep dinlemededir; ekran kapanır
     * kapanmaz veri anahtarı bellekten silinir.
     */
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            VaultSession.lock()
            temizleDuyarliPano(ClipClearPolicy.An.EKRAN_KAPANDI)
        }
    }

    /**
     * Panoya duyarlı bir değer konduğunda çağrılır: silme kararının süre
     * hesabı buradan başlar. Damga süreç kapsamındadır — Activity ölse de
     * yaşar, çünkü panonun kendisi de öyle.
     */
    @Volatile
    private var panoDamgasi: Long = 0L

    fun duyarliPanoyuIsaretle() {
        panoDamgasi = SystemClock.elapsedRealtime()
    }

    /**
     * Panoda **bizim** işaretimizi taşıyan duyarlı bir değer duruyorsa siler.
     * Karar [ClipClearPolicy]'de; burada yalnız sistemle konuşulur.
     *
     * Dönüş değeri silmenin **gerçekten olduğunu** söyler. Bu gerekli, çünkü
     * Android 10'dan beri odakta olmayan uygulamanın pano yazması sessizce
     * reddediliyor: çağrı başarıyla döner, pano değişmez. Silme sonrası
     * işareti yeniden okumak, "sildim" ile "silebildim"i ayıran tek şey.
     */
    fun temizleDuyarliPano(an: ClipClearPolicy.An): Boolean {
        return try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val bizim = cm.primaryClipDescription?.extras?.getBoolean(CLIP_MARKER) == true
            val gecen = SystemClock.elapsedRealtime() - panoDamgasi
            if (!ClipClearPolicy.shouldClear(an, bizim, gecen)) return false
            if (android.os.Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip()
            else cm.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
            val kaldi = cm.primaryClipDescription?.extras?.getBoolean(CLIP_MARKER) == true
            if (!kaldi) panoDamgasi = 0L
            !kaldi
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Ana ekranın kapanmasını dinler. Katlanabilir cihazda telefonu katlamak
     * iç ekranı kapatır ama kapak ekranı açık kaldığı için cihaz "uyanık"
     * sayılır ve `ACTION_SCREEN_OFF` HİÇ GELMEZ (ölçüldü). Activity o sırada
     * zaten durmuş olabileceği için `onStop` da devreye girmez — kasa açık
     * kalırdı. Bu dinleyici o boşluğu kapatır.
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = kilitle(displayId)
        override fun onDisplayChanged(displayId: Int) = kilitle(displayId)

        private fun kilitle(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val dm = getSystemService(DisplayManager::class.java) ?: return
            val durum = dm.getDisplay(Display.DEFAULT_DISPLAY)?.state ?: Display.STATE_OFF
            if (durum != Display.STATE_ON) {
                VaultSession.lock()
                temizleDuyarliPano(ClipClearPolicy.An.EKRAN_KAPANDI)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(
            this,
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        getSystemService(DisplayManager::class.java)
            ?.registerDisplayListener(displayListener, null)
    }

    companion object {
        /** Panoya konan duyarlı değeri tanımak için işaret (ClipDescription extras). */
        const val CLIP_MARKER = "com.afgover.vault.sensitive_clip"

        fun from(context: Context): VaultApp = context.applicationContext as VaultApp
    }
}
