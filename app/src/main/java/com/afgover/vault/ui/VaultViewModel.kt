package com.afgover.vault.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.afgover.vault.R
import com.afgover.vault.VaultApp
import com.afgover.vault.backup.BackupManager
import com.afgover.vault.core.ClipClearPolicy
import com.afgover.vault.core.VaultSession
import com.afgover.vault.data.DecryptedEntry
import com.afgover.vault.data.EntryData
import com.afgover.vault.data.EntrySort
import com.afgover.vault.data.NoteKind
import com.afgover.vault.data.EntryType
import com.afgover.vault.data.TagEntity
import com.afgover.vault.data.UsageEvent
import com.afgover.vault.data.UsageKind
import com.afgover.vault.data.VaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.afgover.vault.core.AppLocale

data class EntryListItem(
    val id: Long,
    val type: EntryType,
    val title: String,
    val quick: Boolean,
    val tagIds: List<Long> = emptyList(),
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val noteKind: NoteKind = NoteKind.GENEL,
    val sortIndex: Int = 0
)

enum class LockState { NEEDS_SETUP, LOCKED, NEEDS_PIN, UNLOCKED }

class VaultViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as VaultApp
    val keyManager = app.keyManager
    private val repo = app.repository
    private val usageLog = app.usageLog

    /**
     * Dil değişimi Activity'yi recreate ettiği için gezinme konumu burada
     * saklanır — ViewModel recreate'ten sağ çıkar, `remember` çıkmaz (denetim).
     * Tipi arayüz katmanında tanımlı olduğundan Any? tutulur.
     */
    var acilacakEkran: Any? = null

    var lockState by mutableStateOf(
        when {
            !keyManager.isInitialized -> LockState.NEEDS_SETUP
            VaultSession.isUnlocked -> LockState.UNLOCKED
            else -> LockState.LOCKED
        }
    )
        private set

    var busy by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)

    /** İlk kurulum biter bitmez biyometrik teklifi gösterilsin mi? */
    var offerBiometric by mutableStateOf(false)

    /**
     * Karşılama ekranında "Yedeğim var" seçildi: kurulum biter bitmez
     * Ayarlar'a geçilir ve yedek dosyası seçici kendiliğinden açılır.
     * Ayarlar bayrağı tüketince sıfırlar; akış yarıda kalırsa bir sonraki
     * kurulum denemesinde seçim korunur (kasa hâlâ yok, ekran aynı).
     */
    var restoreAfterSetup by mutableStateOf(false)

    /** PIN aşaması için bekleyen iç sargı; PIN doğrulanınca temizlenir. */
    private var pendingInner: ByteArray? = null

    /**
     * Biyometrik kaydın durumu Compose'un göreceği bir durumda tutulur.
     * Doğrudan `keyManager.isBiometricEnabled` okunduğunda ekran, kayıt
     * değişse bile yeniden çizilmiyordu: kullanıcı parmak izini açıyor ama
     * düğme "etkinleştir" demeye devam ediyor, açılmamış sanılıyordu.
     */
    var biometricEnabled by mutableStateOf(keyManager.isBiometricEnabled)
        private set

    var pinEnabledState by mutableStateOf(keyManager.isPinEnabled)
        private set

    fun refreshLockOptions() {
        biometricEnabled = keyManager.isBiometricEnabled
        pinEnabledState = keyManager.isPinEnabled
    }

    val isPinEnabled: Boolean get() = pinEnabledState
    val isPinLegacy: Boolean get() = keyManager.isPinLegacy

    /** Eski sürümde açılmış PIN kaydını onarır (parola + PIN ile). */
    fun migrateLegacyPin(password: String, pin: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            busy = true
            val ok = withContext(Dispatchers.Default) {
                keyManager.migrateLegacyPin(password.toCharArray(), pin)
            }
            busy = false
            refreshLockOptions()
            if (ok) toast(str(R.string.vm_pin_repaired))
            else error = str(R.string.vm_pin_repair_failed)
            onDone(ok)
        }
    }
    val reminderDays: Int get() = keyManager.reminderDays

    fun setReminderDays(days: Int) {
        keyManager.reminderDays = days
    }

    /** Biyometrik yerine ana parola istenmeli mi (hatırlatıcı doldu mu)? */
    val masterPasswordDue: Boolean get() = keyManager.masterPasswordDue()

    /** Kullanıcının seçtiği sıra; klavye ile ana liste aynı düzeni kullanır. */
    var sort by mutableStateOf(EntrySort.read(application))
        private set

    fun selectSort(value: EntrySort) {
        sort = value
        EntrySort.write(getApplication(), value)
    }

    val entries: StateFlow<List<EntryListItem>> = repo.observeAll()
        .map { list ->
            list.map {
                EntryListItem(
                    id = it.id,
                    type = runCatching { EntryType.valueOf(it.type) }.getOrDefault(EntryType.NOTE),
                    title = it.title,
                    quick = it.quick,
                    tagIds = com.afgover.vault.data.TagIds.parse(it.tags),
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt,
                    noteKind = NoteKind.of(it.noteKind),
                    sortIndex = it.sortIndex
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val tags: StateFlow<List<TagEntity>> = repo.observeTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addTag(name: String, color: Int, onDone: (Long) -> Unit = {}) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val id = withContext(Dispatchers.IO) { repo.addTag(name, color) }
            onDone(id)
        }
    }

    fun updateTag(tag: TagEntity) {
        if (tag.name.isBlank()) return
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.updateTag(tag) } }
    }

    fun deleteTag(id: Long) {
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.deleteTag(id) } }
    }

    /** Ekran öne geldiğinde: ekran kapanıp kasa kilitlendiyse kilit ekranına dön. */
    init {
        // Oturum kilidi HER kaynaktan (ekran kapanması, klavye, otomatik
        // doldurma) anında arayüze yansısın: eskiden yalnız onResume'da
        // eşitleniyordu ve ertelenmiş yayın yüzünden kilitli kasada içerik
        // görünüyordu.
        viewModelScope.launch {
            VaultSession.unlocked.collect { acik ->
                if (!acik) kilitliyeDus()
            }
        }
    }

    /** Oturum kapandıysa arayüzü de kilitle; kurulum/PIN durumları korunur. */
    private fun kilitliyeDus() {
        if (lockState == LockState.UNLOCKED) lockState = LockState.LOCKED
        if (lockState == LockState.NEEDS_PIN && pendingInner != null) {
            pendingInner = null
            lockState = LockState.LOCKED
        }
    }

    fun refreshLockState() {
        if (!VaultSession.isUnlocked) kilitliyeDus()
    }

    /**
     * Kilit açıldığında hızlı erişim kopyaları da tazelenir: Keystore anahtarı
     * kaybolmuş ya da kopya hiç üretilememişse kaydın aslından yeniden yazılır.
     * Kilitliyken biriken kullanım olayları da bu anda günlüğe taşınır.
     */
    private fun onUnlocked(key: javax.crypto.SecretKey) {
        VaultSession.unlock(key)
        lockState = LockState.UNLOCKED
        viewModelScope.launch(Dispatchers.IO) {
            repo.repairQuickCopies(key)
            usageLog.foldBuffer(key)
        }
    }

    private fun SecretKeySpec(bytes: ByteArray, algo: String) =
        javax.crypto.spec.SecretKeySpec(bytes, algo)

    fun setup(password: String, confirm: String) {
        if (password.length < 8) {
            error = str(R.string.vm_master_password_too_short)
            return
        }
        if (password != confirm) {
            error = str(R.string.vm_passwords_mismatch)
            return
        }
        viewModelScope.launch {
            busy = true
            val key = withContext(Dispatchers.Default) {
                keyManager.setup(password.toCharArray())
            }
            busy = false
            keyManager.markMasterPasswordUsed()
            onUnlocked(key)
            // Teklif kurulumun hemen ardından yapılır: sonraya bırakılırsa
            // kullanıcı ana parolayı her açılışta yeniden yazmak zorunda kalır
            // ve özelliğin var olduğunu çoğu zaman hiç fark etmez.
            offerBiometric = true
        }
    }

    fun unlock(password: String) {
        viewModelScope.launch {
            busy = true
            val payload = withContext(Dispatchers.Default) {
                keyManager.unlockStage1(password.toCharArray())
            }
            busy = false
            if (payload == null) {
                error = str(R.string.vm_wrong_password)
                return@launch
            }
            // dataKey tam 32 bayt olmalı. Eski (legacy) PIN kasasında bu payload
            // 60 baytlık iç sargıdır; oturuma konursa kasa "açık ama boş" görünür
            // ve yazmada çöker (denetim). Onun yerine onarıma yönlendir.
            if (payload.size != 32) {
                if (keyManager.isPinLegacy) {
                    lockState = LockState.LOCKED
                    error = str(R.string.vm_legacy_pin_vault)
                } else {
                    error = str(R.string.vm_key_undecryptable)
                }
                return@launch
            }
            // Ana parola tek başına açar: PIN yalnız parmak izi yolunu korur.
            keyManager.markMasterPasswordUsed()
            onUnlocked(SecretKeySpec(payload, "AES"))
        }
    }

    /**
     * İkinci aşama: parmak izinden gelen iç sargıyı PIN ile çözer.
     *
     * PIN doğru olmasına rağmen sargı açılmıyorsa kayıt **bayattır** (PIN
     * açılmadan önce yazılmış bir parmak izi kaydı ham anahtarı sarar). O
     * durumda kayıt silinir ve kullanıcıya yenilemesi söylenir — aksi hâlde
     * uygulama "PIN yanlış" diyerek açılmaz hâle gelirdi.
     */
    fun unlockWithPin(pin: String) {
        val inner = pendingInner ?: run { lockState = LockState.LOCKED; return }
        // Kaba kuvvete karşı üssel bekleme (denetim): kilitliyken deneme reddedilir.
        val kalan = keyManager.pinLockRemainingMs()
        if (kalan > 0) {
            error = cogul(R.plurals.vm_pin_locked_wait, (kalan / 1000).coerceAtLeast(1).toInt())
            return
        }
        viewModelScope.launch {
            busy = true
            val key = withContext(Dispatchers.Default) { keyManager.unlockWithPin(inner, pin) }
            busy = false
            if (key != null) {
                keyManager.resetPinFailures()
                pendingInner = null
                onUnlocked(key)
                return@launch
            }
            if (keyManager.verifyPin(pin)) {
                // PIN doğru ama sargı bayat: sayaç bir güvenlik olayı değil,
                // sıfırla ve onarıma yönlendir.
                keyManager.resetPinFailures()
                keyManager.clearBiometric()
                pendingInner = null
                lockState = LockState.LOCKED
                error = str(R.string.vm_biometric_stale)
            } else {
                val kapandi = keyManager.notePinFailure()
                if (kapandi) {
                    pendingInner = null
                    lockState = LockState.LOCKED
                    error = str(R.string.vm_pin_biometric_off)
                } else {
                    val yeniKalan = keyManager.pinLockRemainingMs()
                    error = if (yeniKalan > 0)
                        cogul(R.plurals.vm_pin_wrong_wait, (yeniKalan / 1000).coerceAtLeast(1).toInt())
                    else str(R.string.vm_pin_wrong)
                }
            }
        }
    }

    fun cancelPinStage() {
        pendingInner = null
        error = null
        lockState = LockState.LOCKED
    }

    /** Biyometrik/parola aşama-1 çıktısı; PIN açıksa ikinci aşamaya geçer. */
    fun onStage1Payload(payload: ByteArray) {
        if (keyManager.isPinEnabled) {
            pendingInner = payload
            lockState = LockState.NEEDS_PIN
        } else if (payload.size == 32) {
            onUnlocked(SecretKeySpec(payload, "AES"))
        } else {
            lockState = LockState.LOCKED
            error = str(R.string.vm_key_unexpected_size)
        }
    }

    fun onBiometricUnlocked(key: javax.crypto.SecretKey) = onUnlocked(key)

    /** Parmak izi kaydı için sarılacak içerik (PIN açıksa PIN gerekir). */
    fun biometricPayload(pin: String?): ByteArray? {
        val key = VaultSession.key() ?: return null
        return keyManager.wrapForBiometric(key, pin)
    }

    fun enablePin(password: String, pin: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            busy = true
            val ok = withContext(Dispatchers.Default) {
                keyManager.enablePin(password.toCharArray(), pin)
            }
            busy = false
            refreshLockOptions()
            if (!ok) error = str(R.string.vm_pin_enable_failed)
            else toast(str(R.string.vm_pin_enabled))
            onDone(ok)
        }
    }

    fun disablePin(password: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            busy = true
            val ok = withContext(Dispatchers.Default) {
                keyManager.disablePin(password.toCharArray())
            }
            busy = false
            refreshLockOptions()
            if (!ok) error = str(R.string.vm_pin_disable_failed)
            else toast(str(R.string.vm_pin_disabled))
            onDone(ok)
        }
    }

    fun lock() {
        VaultSession.lock()
        lockState = LockState.LOCKED
    }

    fun changePassword(old: String, new: String, confirm: String, onDone: () -> Unit) {
        if (new.length < 8) {
            error = str(R.string.vm_new_password_too_short)
            return
        }
        if (new != confirm) {
            error = str(R.string.vm_passwords_mismatch)
            return
        }
        viewModelScope.launch {
            busy = true
            val ok = withContext(Dispatchers.Default) {
                keyManager.changePassword(old.toCharArray(), new.toCharArray())
            }
            busy = false
            if (ok) {
                toast(str(R.string.vm_master_password_changed))
                onDone()
            } else {
                error = str(R.string.vm_current_password_wrong)
            }
        }
    }

    // ---- Kayıtlar ----

    suspend fun loadEntry(id: Long): DecryptedEntry? {
        val key = VaultSession.key() ?: return null
        return withContext(Dispatchers.IO) { repo.getDecrypted(id, key) }
    }

    fun saveEntry(
        id: Long,
        type: EntryType,
        title: String,
        data: EntryData,
        quick: Boolean,
        tagIds: List<Long> = emptyList(),
        noteKind: NoteKind = NoteKind.GENEL,
        onDone: () -> Unit
    ) {
        val key = VaultSession.key() ?: run { lockState = LockState.LOCKED; return }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { repo.save(id, type, title, data, quick, key, tagIds, noteKind) }
                onDone()
            } catch (e: VaultRepository.EntryTooLargeException) {
                // Ekran açık kalır, girilenler durur; kullanıcı kısaltıp yeniden dener.
                toast(str(R.string.entry_too_large, e.title, e.kb, e.limitKb))
            }
        }
    }


    // ── Zarftan tek değer okuma ────────────────────────────────────────────

    /**
     * Şifreli zarfı çözer ve içinden **tek bir değer** alır; kasaya hiçbir şey
     * yazmaz. Var olan bir kaydın parolasını QR ile tazelemek ve araç çıpasını
     * güncellemek bunu kullanır: zarfın taşıdığı kayıt içe aktarılmaz, yalnız
     * istenen alanı okunup gerisi atılır.
     *
     * [sec] zarftaki ilk kayıttan hangi alanın alınacağını söyler.
     */
    fun readFromEnvelope(
        text: String,
        password: String,
        sec: (DecryptedEntry) -> String,
        onValue: (String) -> Unit
    ) {
        error = null
        val kirpik = BackupManager.normalize(text)
        if (kirpik.isEmpty()) { error = str(R.string.vm_pasted_text_empty); return }
        if (kirpik.length > 1_000_000) { error = str(R.string.vm_text_too_large); return }
        if (kirpik.startsWith("http://") || kirpik.startsWith("https://")) {
            error = str(R.string.vm_pasted_is_link); return
        }
        if (!(kirpik.startsWith("{") && kirpik.contains("\"app\"") && kirpik.contains("\"vault\""))) {
            error = str(R.string.vm_not_an_envelope); return
        }
        viewModelScope.launch {
            busy = true
            try {
                val deger = withContext(Dispatchers.IO) {
                    val imported = BackupManager.import(kirpik.byteInputStream(), password.toCharArray())
                    imported.entries.firstOrNull()?.let(sec).orEmpty()
                }
                if (deger.isEmpty()) {
                    error = str(R.string.vm_envelope_no_value)
                    return@launch
                }
                clearClipboard()
                onValue(deger)
            } catch (e: BackupManager.WrongPasswordException) {
                error = str(R.string.vm_backup_password_wrong)
            } catch (e: BackupManager.InvalidFormatException) {
                error = str(R.string.vm_invalid_envelope)
            } catch (e: Exception) {
                error = str(R.string.vm_import_failed, e.message.orEmpty())
            } finally {
                busy = false
            }
        }
    }

    // ── Araç çıpası ────────────────────────────────────────────────────────

    /** Kurulu araç çıpası; yoksa null. Çıpa ekranı açılırken doldurulur. */
    var anchor by mutableStateOf<DecryptedEntry?>(null)
        private set

    fun loadAnchor() {
        val key = VaultSession.key() ?: run { lockState = LockState.LOCKED; return }
        viewModelScope.launch {
            anchor = withContext(Dispatchers.IO) { repo.getAnchorDecrypted(key) }
        }
    }

    /** Çıpayı kurar ya da üzerine yazar; ardından ekranı tazeler. */
    fun saveAnchor(value: String, onDone: () -> Unit = {}) {
        val temiz = value.trim()
        if (temiz.isEmpty()) return
        val key = VaultSession.key() ?: run { lockState = LockState.LOCKED; return }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { repo.saveAnchor(temiz, key) }
                anchor = withContext(Dispatchers.IO) { repo.getAnchorDecrypted(key) }
                toast(str(R.string.anchor_saved))
                onDone()
            } catch (e: VaultRepository.EntryTooLargeException) {
                toast(str(R.string.entry_too_large, e.title, e.kb, e.limitKb))
            }
        }
    }

    /** Detay ekranındaki hızlı erişim anahtarı; içeriğe dokunmaz. */
    fun setQuick(id: Long, quick: Boolean, onDone: () -> Unit = {}) {
        val key = VaultSession.key() ?: run { lockState = LockState.LOCKED; return }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.setQuick(id, quick, key) }
            toast(
                if (quick) str(R.string.vm_quick_on)
                else str(R.string.vm_quick_off)
            )
            onDone()
        }
    }

    /** Sürükleme bitince yeni kullanıcı sırasını kaydeder. */
    fun saveManualOrder(idsInOrder: List<Long>) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.applyManualOrder(idsInOrder) }
        }
    }

    fun deleteEntry(id: Long, onDone: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.delete(id) }
            onDone()
        }
    }

    // ---- Pano ----

    private var clipboardClearJob: Job? = null

    /**
     * [label] ekranda gösterilen (çevrilmiş) ad, [stableName] ise günlüğe
     * yazılan dile bağlı olmayan ad. İkisi ayrı: günlük dil değiştiğinde
     * ikiye bölünmemeli (yerelleştirme).
     *
     * Buradaki sayaç yalnız **uygulama ekranda kalırsa** iş görür; kullanıcı
     * çıkarsa Android odak kısıtı silmeyi sessizce reddeder ([ClipClearPolicy]).
     * O durumda temizlik, odağın geri döndüğü ana ertelenir
     * (`MainActivity.onWindowFocusChanged`) — verilen söz de buna göre yazılı.
     */
    fun copyToClipboard(
        label: String,
        value: String,
        entryId: Long? = null,
        stableName: String? = null
    ) {
        val cm = getApplication<Application>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, value)
        // İşaretle: temizlik sırası geldiğinde panodakinin BİZİM değerimiz
        // olduğunu buradan tanıyoruz — kullanıcı sonradan başka bir şey
        // kopyaladıysa ona dokunulmuyor. Duyarlı bayrağı 33+ önizlemeyi gizler.
        clip.description.extras = PersistableBundle().apply {
            putBoolean(VaultApp.CLIP_MARKER, true)
            if (Build.VERSION.SDK_INT >= 33) putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
        cm.setPrimaryClip(clip)
        app.duyarliPanoyuIsaretle()
        toast(str(R.string.vm_copied, label))
        entryId?.let { logUsage(it, UsageKind.KOPYALANDI, stableName ?: label) }
        clipboardClearJob?.cancel()
        clipboardClearJob = viewModelScope.launch {
            delay(ClipClearPolicy.BEKLEME_MS)
            app.temizleDuyarliPano(ClipClearPolicy.An.SAYAC)
        }
    }

    // ---- Yedekleme ----

    fun exportBackup(uri: Uri, password: String) {
        val key = VaultSession.key() ?: run { lockState = LockState.LOCKED; return }
        if (password.length < 8) {
            error = str(R.string.vm_backup_password_too_short)
            return
        }
        viewModelScope.launch {
            busy = true
            try {
                val eksik = withContext(Dispatchers.IO) {
                    val all = repo.getAllDecrypted(key)
                    val allTags = repo.getAllTags()
                    // Çözülemeyen kayıt sessizce yedekten düşüyordu (denetim):
                    // toplam satırla karşılaştır, eksik varsa kullanıcıya söyle —
                    // eksik bir yedeği tam sanmak, kaybın en sinsi biçimi.
                    val toplam = repo.getAll().size
                    val eksikSayi = toplam - all.size
                    // Şifrele ÖNCE (belleğe), dosyaya sonra tek seferde yaz:
                    // yazma sırasında bir hata olursa seçilen konumda yarım/boş
                    // .vaultbak kalmasın — kullanıcı onu geçerli yedek sanabilir
                    // (denetim). "w" kipi ayrıca dosyayı budar (kesip yeniler).
                    val bytes = java.io.ByteArrayOutputStream().use { buf ->
                        BackupManager.export(buf, password.toCharArray(), all, allTags)
                        buf.toByteArray()
                    }
                    val out = getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                        ?: throw Exception(str(R.string.vm_file_open_failed))
                    out.use { it.write(bytes) }
                    eksikSayi
                }
                if (eksik > 0) {
                    error = cogul(R.plurals.vm_backup_saved_incomplete, eksik)
                } else {
                    toast(str(R.string.vm_backup_saved))
                }
            } catch (e: Exception) {
                error = str(R.string.vm_backup_failed, e.message.orEmpty())
            } finally {
                busy = false
            }
        }
    }

    /**
     * B-032: yapıştırılan şifreli zarf metninden içe aktarma. Aynı boru
     * ([BackupManager.import]); dosya yerine pano. Başarıda pano temizlenir —
     * şifreli de olsa zarfın klavye geçmişlerinde sürüklenmesine gerek yok.
     */
    fun importBackupText(text: String, password: String, replace: Boolean, onSuccess: () -> Unit = {}) {
        val key = VaultSession.key() ?: run {
            // Ekran kapanıp kasa kilitlenmiş olabilir; sessizce dönmek
            // "düğme çalışmıyor" gibi görünüyordu.
            error = str(R.string.vm_vault_locked_retry)
            lockState = LockState.LOCKED
            return
        }
        error = null
        val kirpik = BackupManager.normalize(text)
        if (kirpik.isEmpty()) { error = str(R.string.vm_pasted_text_empty); return }
        if (kirpik.length > 1_000_000) { error = str(R.string.vm_text_too_large); return }
        // Dostça ön-tanı: en sık iki yanlış yapıştırma ayrı ayrı adlandırılır,
        // çünkü "geçersiz" demek kullanıcıya ne yapacağını söylemiyor.
        if (kirpik.startsWith("http://") || kirpik.startsWith("https://")) {
            error = str(R.string.vm_pasted_is_link)
            return
        }
        val zarfGibi = kirpik.startsWith("{") && kirpik.contains("\"app\"") &&
            kirpik.contains("\"vault\"")
        if (!zarfGibi) {
            error = str(R.string.vm_not_an_envelope)
            return
        }
        viewModelScope.launch {
            busy = true
            try {
                val count = withContext(Dispatchers.IO) {
                    val imported = BackupManager.import(kirpik.byteInputStream(), password.toCharArray())
                    // Boş zarfta HİÇBİR depo işlemi yapma: 'Tümünü değiştir' ile
                    // boş zarf, kasanın tamamını silerdi (denetim: kritik).
                    if (imported.entries.isEmpty()) return@withContext 0
                    if (replace) repo.replaceAll(imported.entries, key, imported.tagColors)
                    else repo.addAll(imported.entries, key, imported.tagColors)
                    imported.entries.size
                }
                if (count == 0) {
                    error = str(R.string.vm_envelope_empty)
                    return@launch
                }
                clearClipboard()
                toast(cogul(R.plurals.vm_import_added, count))
                onSuccess()
            } catch (e: BackupManager.WrongPasswordException) {
                error = str(R.string.vm_backup_password_wrong)
            } catch (e: BackupManager.InvalidFormatException) {
                error = str(R.string.vm_invalid_envelope)
            } catch (e: Exception) {
                error = str(R.string.vm_import_failed, e.message.orEmpty())
            } finally {
                busy = false
            }
        }
    }

    /** Panodaki metni okur (yalnız ön plandayken çalışır; izin gerekmez). */
    fun clipboardText(): String {
        val cm = getApplication<Application>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        // Boş panoda coerceToText null döner; .toString() "null" metni üretiyordu (denetim).
        return cm.primaryClip?.getItemAt(0)?.coerceToText(getApplication())?.toString() ?: ""
    }

    private fun clearClipboard() {
        val cm = getApplication<Application>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        try {
            if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip()
            else cm.setPrimaryClip(ClipData.newPlainText("", ""))
        } catch (_: Exception) {
        }
    }

    fun importBackup(uri: Uri, password: String, replace: Boolean) {
        val key = VaultSession.key() ?: run { lockState = LockState.LOCKED; return }
        viewModelScope.launch {
            busy = true
            try {
                val count = withContext(Dispatchers.IO) {
                    val input = getApplication<Application>().contentResolver.openInputStream(uri)
                        ?: throw Exception(str(R.string.vm_file_open_failed))
                    val imported = BackupManager.import(input, password.toCharArray())
                    if (imported.entries.isEmpty()) return@withContext 0
                    if (replace) repo.replaceAll(imported.entries, key, imported.tagColors)
                    else repo.addAll(imported.entries, key, imported.tagColors)
                    imported.entries.size
                }
                if (count == 0) {
                    error = str(R.string.vm_backup_empty)
                    return@launch
                }
                toast(cogul(R.plurals.vm_restored, count))
            } catch (e: BackupManager.WrongPasswordException) {
                error = str(R.string.vm_backup_password_wrong)
            } catch (e: BackupManager.InvalidFormatException) {
                error = str(R.string.vm_invalid_backup_file)
            } catch (e: Exception) {
                error = str(R.string.vm_restore_failed, e.message.orEmpty())
            } finally {
                busy = false
            }
        }
    }

    // ---- Ayarlar ----

    /** Parmak izi kaydı yazıldıktan sonra çağrılır: durum + geri bildirim. */
    fun onBiometricRegistered(basarili: Boolean) {
        refreshLockOptions()
        if (basarili) toast(str(R.string.vm_biometric_enabled))
        else error = str(R.string.vm_biometric_enable_failed)
    }

    fun disableBiometric() {
        keyManager.clearBiometric()
        refreshLockOptions()   // düğme durumu 'kapat' → 'etkinleştir' güncellensin (denetim)
        toast(str(R.string.vm_biometric_disabled))
    }

    /** Yerelleştirilmiş metin: ViewModel'de Composable bağlamı yok. */
    /**
     * Application context uygulama diline SARILMAMIŞTIR: ViewModel'in ürettiği
     * hata ve toast metinleri, arayüz İngilizceyken bile cihaz dilinde çıkardı
     * (denetim — yüksek). Her çağrıda sarılmış context'ten çöz; ViewModel
     * recreate'ten sağ çıktığı için tek seferlik önbellek eski dile takılırdı.
     */
    private val yerel: Context get() = AppLocale.wrap(getApplication())

    private fun str(@StringRes id: Int, vararg args: Any): String =
        yerel.getString(id, *args)

    /**
     * Sayıya bağlı metin: İngilizcede "1 entries" gibi bozuk dilbilgisini
     * önler (Türkçede tek biçim yeterli, çeviri dosyası öyle tanımlı).
     */
    private fun cogul(@PluralsRes id: Int, adet: Int): String =
        yerel.resources.getQuantityString(id, adet, adet)

    private fun toast(msg: String) {
        Toast.makeText(yerel, msg, Toast.LENGTH_SHORT).show()
    }

    // ---- Kullanım günlüğü ----

    /**
     * Olayı arka planda kaydeder. Günlüğe yazamamak asıl işi (kopyalama,
     * yazma) hiçbir zaman engellemez; bu yüzden sonucu beklenmez.
     */
    fun logUsage(entryId: Long, kind: UsageKind, fieldLabel: String? = null, target: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            usageLog.record(
                UsageEvent(entryId, kind, System.currentTimeMillis(), fieldLabel, target)
            )
        }
    }

    suspend fun usageFor(entryId: Long): List<UsageEvent> {
        val key = VaultSession.key() ?: return emptyList()
        return withContext(Dispatchers.IO) { usageLog.eventsFor(entryId, key) }
    }

    suspend fun allUsage(): List<UsageEvent> {
        val key = VaultSession.key() ?: return emptyList()
        return withContext(Dispatchers.IO) { usageLog.events(key) }
    }

    /** Kilitliyken birikmiş, henüz günlüğe taşınmamış olay sayısı. */
    suspend fun pendingUsage(): Int = withContext(Dispatchers.IO) { usageLog.pendingCount() }

    fun clearUsageLog(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { usageLog.clearAll() }
            toast(str(R.string.vm_usage_log_cleared))
            onDone()
        }
    }
}
