package com.afgover.vault.core

import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Anahtar hiyerarşisi:
 *  - dataKey: rastgele 256 bit; tüm kayıtları şifreler.
 *  - KEK: ana paroladan PBKDF2 ile türetilir; dataKey'i sarar (AES-GCM).
 *    GCM doğrulaması sayesinde yanlış parola açılırken belli olur.
 *  - Biyometrik (opsiyonel): dataKey ayrıca Keystore'daki biyometrik korumalı
 *    bir anahtarla da sarılır. Keystore cihaz sıfırlanınca kaybolur; bu yüzden
 *    yalnızca kolaylık içindir, ana parola her zaman çalışır.
 */
class KeyManager(context: Context) {

    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("vault_keys", Context.MODE_PRIVATE)

    companion object {
        private const val PREF_SALT = "kdf_salt"
        private const val PREF_ITERATIONS = "kdf_iterations"
        private const val PREF_WRAPPED_KEY = "wrapped_data_key"
        private const val PREF_BIO_WRAPPED_KEY = "bio_wrapped_data_key"
        private const val PREF_BIO_IV = "bio_iv"
        private const val PREF_PIN_SALT = "pin_salt"
        private const val PREF_PIN_CHECK = "pin_check"
        /** pin_check, Keystore quickKey ile ikinci kez sarılı mı (çevrimdışı kahini kapatır). */
        private const val PREF_PIN_CHECK_KS = "pin_check_ks"
        private const val PREF_REMINDER_DAYS = "master_reminder_days"
        private const val PREF_LAST_MASTER = "last_master_password_at"
        private const val PREF_PIN_FAILS = "pin_fail_count"
        private const val PREF_PIN_FAIL_AT = "pin_fail_at"
        private const val PREF_PIN_FAIL_MONO = "pin_fail_mono"
        /** Hızlı erişim anahtarının üretildiği kural sürümü; artınca anahtar yenilenir. */
        private const val PREF_QUICK_KEY_VER = "quick_key_ver"
        private const val QUICK_KEY_VER = 2
        /** Bu kadar art arda yanlış PIN'den sonra parmak izi yolu tamamen kapanır. */
        private const val PIN_MAX_FAILS = 10
        private const val KEYSTORE_ALIAS = "vault_biometric_key"
        private const val QUICK_KEYSTORE_ALIAS = "vault_quick_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }

    val isInitialized: Boolean
        get() = prefs.contains(PREF_WRAPPED_KEY)

    // ---- PIN (ikinci kapı; varsayılan KAPALI) ----
    //
    // PIN, ana parola yolunu DEĞİL **parmak izi yolunu** korur. Gerekçe:
    // ana parola zaten güçlü sırdır, üstüne PIN sormak yalnız sürtünme
    // ekler. Korunmak istenen senaryo "biri parmağımı kullanır" olduğu için
    // katman tam oraya kondu:
    //
    //   parola  → Enc(KEK_parola, dataKey)              (PIN sorulmaz)
    //   parmak  → Enc(K_keystore, Enc(K_pin, dataKey))  (PIN şart)
    //
    // Böylece kurtarma garantisi de bozulmaz: ana parola her zaman tek
    // başına açar; PIN unutulursa parmak izi kaydı silinip yenilenir.

    val isPinEnabled: Boolean
        get() = prefs.contains(PREF_PIN_SALT)

    private fun pinSalt(): ByteArray? = prefs.getString(PREF_PIN_SALT, null)?.let(::unb64)

    /**
     * `ead5d0f` sürümünde açılmış PIN kaydı: PIN tuzu var ama doğrulama
     * blobu yok ve ana parola sargısı **iç sargıyı** taşıyor. O sürüm
     * yayından kalktı; bu durumdaki kasa [migrateLegacyPin] ile onarılır,
     * yoksa parola doğru olsa bile kayıtlar çözülemez.
     */
    val isPinLegacy: Boolean
        get() = isPinEnabled && !prefs.contains(PREF_PIN_CHECK)

    /**
     * Eski model kaydı yeni modele taşır: iç sargı PIN ile çözülür, `dataKey`
     * doğrudan ana parola katmanına yazılır ve PIN kaydı silinir. Kullanıcı
     * isterse PIN'i sonra yeniden açar (bu kez parmak izi yolunu koruyarak).
     */
    fun migrateLegacyPin(password: CharArray, pin: String): Boolean {
        if (!isPinLegacy) return false
        val inner = unlockStage1(password) ?: return false
        val salt = pinSalt() ?: return false
        val dataKey = PinLock.unwrap(inner, pin, salt) ?: return false
        storePayload(password, dataKey.encoded)
        prefs.edit().remove(PREF_PIN_SALT).remove(PREF_PIN_CHECK).remove(PREF_PIN_CHECK_KS).apply()
        resetPinFailures()
        clearBiometric()
        return true
    }

    // ---- PIN kaba kuvvet kısıtlaması (denetim) ----
    //
    // PIN 4-12 hane; kilitlenme olmadan bir saldırgan (parmak izini bir kez
    // geçtikten sonra) tüm PIN'leri sırayla deneyebiliyordu. Kalıcı bir sayaç +
    // üssel bekleme bunu kapatır; süreç ölümünden etkilenmez (SharedPreferences).
    //
    // Sayaç, sahibinin kendini kanıtladığı her yerde sıfırlanır: doğru PIN,
    // ana parolayla açılış, kasa açıkken parmak izi kurulurken doğrulanan PIN,
    // PIN'in açılıp kapanması. Yalnız doğru PIN'de sıfırlandığında 10. yanlış
    // parmak izini siliyor, kullanıcı ana parolayla açıp yeniden kurunca sayaç
    // 10'da kalıyor ve ilk yanlış PIN kaydı yine siliyordu (ikinci tur denetim).

    /**
     * Şu an denemeye izin verilene kadar kalan süre (ms); 0 = serbest.
     * Duvar saati ve monoton saat birlikte ([PinBackoff]): saati ileri almak
     * beklemeyi atlatmaz.
     */
    fun pinLockRemainingMs(
        now: Long = System.currentTimeMillis(),
        mono: Long = SystemClock.elapsedRealtime()
    ): Long = PinBackoff.remainingMs(
        fails = prefs.getInt(PREF_PIN_FAILS, 0),
        failWall = prefs.getLong(PREF_PIN_FAIL_AT, 0L),
        failMono = prefs.getLong(PREF_PIN_FAIL_MONO, 0L),
        nowWall = now,
        nowMono = mono
    )

    /**
     * Yanlış PIN'i kaydeder ve kalan bekleme süresini döndürür. [PIN_MAX_FAILS]
     * aşılınca parmak izi yolu tamamen kapatılır (yalnız ana parola kalır) —
     * çevrimdışı deneme kahini de böylece işe yaramaz hâle gelir.
     */
    fun notePinFailure(
        now: Long = System.currentTimeMillis(),
        mono: Long = SystemClock.elapsedRealtime()
    ): Boolean {
        val fails = prefs.getInt(PREF_PIN_FAILS, 0) + 1
        prefs.edit().putInt(PREF_PIN_FAILS, fails).putLong(PREF_PIN_FAIL_AT, now)
            .putLong(PREF_PIN_FAIL_MONO, mono).apply()
        if (fails >= PIN_MAX_FAILS) {
            clearBiometric()
            return true
        }
        return false
    }

    fun resetPinFailures() {
        prefs.edit().remove(PREF_PIN_FAILS).remove(PREF_PIN_FAIL_AT).remove(PREF_PIN_FAIL_MONO).apply()
    }

    /** PIN'in doğruluğunu sargıya dokunmadan sınar. */
    fun verifyPin(pin: String): Boolean {
        val salt = pinSalt() ?: return false
        val stored = prefs.getString(PREF_PIN_CHECK, null)?.let(::unb64) ?: return false
        // KS sarılıysa önce Keystore anahtarıyla aç: cihaz dökümünü ele geçiren
        // (Keystore anahtarı donanımda, dışa çıkmaz) blobu çözemez, yani PIN'i
        // çevrimdışı deneyemez (denetim). Anahtar kaybolursa doğrulama düşer;
        // PIN yine de gerçek sargıyı GCM etiketiyle açar, yalnız bayat-kayıt
        // ayrımı bozulur.
        val check = if (prefs.getBoolean(PREF_PIN_CHECK_KS, false)) {
            val qk = quickKey() ?: return false
            Crypto.decrypt(qk, stored) ?: return false
        } else stored
        return PinLock.unwrapCheck(check, pin, salt)
    }

    /**
     * PIN'i açar. Ana parola yalnız DOĞRULAMA için istenir; sargı biçimi
     * değişmez. Var olan parmak izi kaydı ham dataKey'i sardığı için
     * temizlenir — kullanıcı yeniden etkinleştirdiğinde PIN katmanıyla
     * sarılır ([wrapForBiometric]).
     */
    fun enablePin(password: CharArray, pin: String): Boolean {
        if (!PinLock.isValid(pin) || isPinEnabled) return false
        unlockWithPassword(password) ?: return false
        val salt = Crypto.randomBytes(16)
        val checkPlain = PinLock.wrapCheck(pin, salt)
        val qk = quickKey()
        val (stored, ks) = if (qk != null)
            Crypto.encryptWithGeneratedIv(qk, checkPlain) to true
        else checkPlain to false   // Keystore yoksa eski davranışa düş
        prefs.edit()
            .putString(PREF_PIN_SALT, b64(salt))
            .putString(PREF_PIN_CHECK, b64(stored))
            .putBoolean(PREF_PIN_CHECK_KS, ks)
            .apply()
        resetPinFailures()
        clearBiometric()
        return true
    }

    /**
     * PIN'i kapatır. **Yalnız ana parola ister**: PIN zaten sadece parmak izi
     * yolunu koruyor ve ana parola tek başına kasayı açıyor, dolayısıyla
     * kapatma anında PIN sormak güvenlik eklemez — yalnız unutulmuş bir PIN'i
     * kaldırılamaz hâle getirirdi. Parmak izi kaydı yine tazelenmeli.
     */
    fun disablePin(password: CharArray): Boolean {
        unlockWithPassword(password) ?: return false
        prefs.edit().remove(PREF_PIN_SALT).remove(PREF_PIN_CHECK).remove(PREF_PIN_CHECK_KS).apply()
        resetPinFailures()
        clearBiometric()
        return true
    }

    /** Parmak izi kaydına yazılacak içerik: PIN açıksa iç sargı, kapalıysa dataKey. */
    fun wrapForBiometric(dataKey: SecretKey, pin: String?): ByteArray? {
        if (!isPinEnabled) return dataKey.encoded
        val salt = pinSalt() ?: return null
        if (pin == null || !verifyPin(pin)) return null
        // Kasa açık ve PIN doğru: sahibidir, yanlış PIN sayacı sıfırlanır.
        resetPinFailures()
        return PinLock.wrap(dataKey, pin, salt)
    }

    /** Parmak izinden gelen iç sargıyı PIN ile çözer. */
    fun unlockWithPin(inner: ByteArray, pin: String): SecretKey? {
        val salt = pinSalt() ?: return null
        return PinLock.unwrap(inner, pin, salt)
    }

    // ---- Ana parola hatırlatıcısı (varsayılan: süresiz) ----

    /** 0 = süresiz (kapalı). */
    var reminderDays: Int
        get() = prefs.getInt(PREF_REMINDER_DAYS, 0)
        set(value) { prefs.edit().putInt(PREF_REMINDER_DAYS, value).apply() }

    fun markMasterPasswordUsed() {
        prefs.edit().putLong(PREF_LAST_MASTER, System.currentTimeMillis()).apply()
    }

    /**
     * Biyometrik açılış yerine ana parola istenmeli mi? Amaç güvenlik değil
     * **unutmayı önlemek**: aylarca yazılmayan parola kaybolur ve yedekleri
     * de o açar. Süre kullanıcı tarafından seçilir; varsayılan süresizdir.
     */
    fun masterPasswordDue(now: Long = System.currentTimeMillis()): Boolean {
        val days = reminderDays
        if (days <= 0) return false
        val last = prefs.getLong(PREF_LAST_MASTER, 0L)
        if (last == 0L) return false
        return now - last >= days * 24L * 60L * 60L * 1000L
    }

    /** İlk kurulum: yeni dataKey üretir ve ana parola ile sarıp saklar. */
    fun setup(password: CharArray): SecretKey {
        val dataKey = Crypto.randomKey()
        storeWrappedKey(password, dataKey)
        return dataKey
    }

    private fun storeWrappedKey(password: CharArray, dataKey: SecretKey) =
        storePayload(password, dataKey.encoded)

    /**
     * Saklanan sargının içeriği: PIN kapalıyken `dataKey`, açıkken PIN ile
     * sarılmış iç sargı. Ana parola katmanı ikisini de aynı şekilde tutar.
     */
    private fun storePayload(password: CharArray, payload: ByteArray) {
        val salt = Crypto.randomBytes(16)
        val kek = Crypto.deriveKey(password, salt)
        val wrapped = Crypto.encrypt(kek, payload)
        prefs.edit()
            .putString(PREF_SALT, b64(salt))
            .putInt(PREF_ITERATIONS, Crypto.KDF_ITERATIONS)
            .putString(PREF_WRAPPED_KEY, b64(wrapped))
            .apply()
    }

    /**
     * Ana parola katmanını açar. Dönen bayt dizisi PIN kapalıyken `dataKey`,
     * açıkken PIN ile sarılmış iç sargıdır ([unlockWithPin] ile çözülür).
     */
    fun unlockStage1(password: CharArray): ByteArray? {
        val salt = prefs.getString(PREF_SALT, null)?.let(::unb64) ?: return null
        val wrapped = prefs.getString(PREF_WRAPPED_KEY, null)?.let(::unb64) ?: return null
        val kek = Crypto.deriveKey(password, salt, storedIterations())
        return Crypto.decrypt(kek, wrapped)
    }

    /** Ana parola sargısının tur sayısı; yazılmamışsa ilk sürümlerin sayısı. */
    private fun storedIterations(): Int =
        prefs.getInt(PREF_ITERATIONS, Crypto.LEGACY_KDF_ITERATIONS)

    /**
     * Sargı bugünkü tur sayısından azıyla yazılmışsa (1.3'ten önceki
     * kasalar) aynı içerik yeni tuz ve [Crypto.KDF_ITERATIONS] ile yeniden
     * sarılır. Yalnız ana parola elde varken yapılabilir: parmak izi yolu
     * parolayı görmez, o yüzden yükseltme ilk parolalı açılışa kalır.
     * [payload], [unlockStage1]'in aynı parolayla az önce döndürdüğüdür.
     */
    fun upgradeKdf(password: CharArray, payload: ByteArray): Boolean {
        if (!Crypto.needsKdfUpgrade(storedIterations())) return false
        storePayload(password, payload)
        return true
    }

    /** Parola doğruysa dataKey. PIN bu yolu etkilemez (bilinçli). */
    fun unlockWithPassword(password: CharArray): SecretKey? {
        val raw = unlockStage1(password) ?: return null
        return SecretKeySpec(raw, "AES")
    }

    /**
     * Ana parolayı değiştirir: içerik (dataKey ya da PIN iç sargısı) aynı
     * kalır, yalnızca ana parola katmanı yenilenir — PIN açıkken de çalışır.
     */
    fun changePassword(oldPassword: CharArray, newPassword: CharArray): Boolean {
        val payload = unlockStage1(oldPassword) ?: return false
        storePayload(newPassword, payload)
        return true
    }

    /** İçe aktarma sonrası: mevcut dataKey'i yeni parola ile yeniden sarar. */
    fun rewrap(password: CharArray, dataKey: SecretKey) {
        storeWrappedKey(password, dataKey)
        clearBiometric()
    }

    // ---- Hızlı erişim ----

    /**
     * "Hızlı erişim" işaretli kayıtların klavye kopyasını şifreleyen anahtar.
     * Keystore'da durur ve **kimlik doğrulama istemez** — kasa kilitliyken de
     * kullanılabilmesinin sebebi budur. Dolayısıyla bu anahtarla korunan
     * kayıtların güvenliği, kasa parolasına değil telefonun kendi ekran
     * kilidine dayanır; oraya yalnızca düşük değerli bilgiler konmalıdır.
     *
     * Anahtar cihaza bağlıdır (sıfırlamada kaybolur). Kayıtların aslı ana
     * paroladan türeyen dataKey ile şifreli olduğu için bu bir veri kaybı
     * yaratmaz: kopyalar kilit açıldığında yeniden üretilir.
     *
     * Keystore erişilemiyorsa null döner; bu durumda hızlı erişim çalışmaz,
     * kayıtlar korumalı gibi davranır.
     *
     * Eşzamanlı çağrılar (klavye, otomatik doldurma, kilit açılışındaki
     * onarım) aynı anda yenilemeye girip birbirinin anahtarını ezmesin diye
     * senkronize.
     */
    @Synchronized
    fun quickKey(): SecretKey? = try {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val mevcut = ks.getKey(QUICK_KEYSTORE_ALIAS, null) as? SecretKey
        if (mevcut != null && prefs.getInt(PREF_QUICK_KEY_VER, 1) < QUICK_KEY_VER && !deviceLocked()) {
            // Eski kuralla üretilmiş anahtar (cihaz kilidi şartı yok): yenilenir.
            // Eski kopyalar bir sonraki kilit açılışında kaynağından yeniden
            // yazılır (VaultRepository.repairQuickCopies); o ana kadar hızlı
            // erişim kayıtları korumalı gibi davranır — başarısızlık güvenli yöne.
            // Cihaz kilitliyken yenilenmez: yeni anahtar o an kullanılamaz ve
            // PIN doğrulama blobu yeniden sarılamaz; eski anahtar bir sonraki
            // açık anda yenilenir.
            rotateQuickKey(ks, mevcut)
        } else mevcut ?: generateQuickKey()
    } catch (e: Exception) {
        null
    }

    /**
     * Hızlı erişim anahtarını yeni kuralla yeniden üretir. PIN doğrulama blobu
     * ([PREF_PIN_CHECK_KS]) eski anahtarla sarılıdır: silmeden önce açılır ve
     * yeni anahtarla yeniden sarılır. Aksi hâlde [verifyPin] kalıcı olarak
     * düşer — bayat sargı ayrımı bozulur ve PIN açıkken parmak izi yeniden
     * etkinleştirilemez ([wrapForBiometric]).
     */
    private fun rotateQuickKey(ks: KeyStore, eski: SecretKey): SecretKey? {
        val pinCheck = if (prefs.getBoolean(PREF_PIN_CHECK_KS, false))
            prefs.getString(PREF_PIN_CHECK, null)?.let(::unb64)?.let { Crypto.decrypt(eski, it) }
        else null
        ks.deleteEntry(QUICK_KEYSTORE_ALIAS)
        val yeni = generateQuickKey()
        if (pinCheck != null) {
            val sarili = yeni?.let {
                try { Crypto.encryptWithGeneratedIv(it, pinCheck) } catch (e: Exception) { null }
            }
            // Yeni anahtarla sarılamazsa enablePin'in Keystore'suz yoluna düşülür.
            prefs.edit()
                .putString(PREF_PIN_CHECK, b64(sarili ?: pinCheck))
                .putBoolean(PREF_PIN_CHECK_KS, sarili != null)
                .apply()
        }
        return yeni
    }

    private fun deviceLocked(): Boolean =
        appContext.getSystemService(KeyguardManager::class.java)?.isDeviceLocked ?: false

    private fun generateQuickKey(): SecretKey? = try {
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    QUICK_KEYSTORE_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(false)
                    .apply {
                        // Bu anahtarın güvenliği telefonun ekran kilidine dayanır;
                        // o dayanak Keystore'a da yazdırılır: cihaz
                        // kilitliyken anahtar kullanılamaz, kilitli ekrandaki bir
                        // süreç hızlı erişim kopyalarını ve tamponu çözemez.
                        if (Build.VERSION.SDK_INT >= 28) setUnlockedDeviceRequired(true)
                    }
                    .build()
            )
        }.generateKey().also { prefs.edit().putInt(PREF_QUICK_KEY_VER, QUICK_KEY_VER).apply() }
    } catch (e: Exception) {
        null
    }

    // ---- Biyometrik ----

    val isBiometricEnabled: Boolean
        get() = prefs.contains(PREF_BIO_WRAPPED_KEY)

    fun clearBiometric() {
        prefs.edit().remove(PREF_BIO_WRAPPED_KEY).remove(PREF_BIO_IV).apply()
        try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            ks.deleteEntry(KEYSTORE_ALIAS)
        } catch (_: Exception) {
        }
    }

    /** Biyometrik etkinleştirme için ENCRYPT modunda cipher hazırlar. */
    fun biometricEncryptCipher(): Cipher {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE
        )
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
                .build()
        )
        val key = keyGenerator.generateKey()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key)
        }
    }

    /**
     * BiometricPrompt onayından sonra saklanan içeriği (PIN kapalıysa
     * dataKey, açıksa iç sargı) keystore anahtarıyla sarıp saklar.
     */
    fun storeBiometricWrappedKey(authorizedCipher: Cipher, payload: ByteArray) {
        val ct = authorizedCipher.doFinal(payload)
        prefs.edit()
            .putString(PREF_BIO_WRAPPED_KEY, b64(ct))
            .putString(PREF_BIO_IV, b64(authorizedCipher.iv))
            .apply()
    }

    /**
     * Biyometrik kilit açma için DECRYPT modunda cipher hazırlar.
     * Keystore anahtarı geçersizleşmişse (yeni parmak izi kaydı, sıfırlama vb.)
     * biyometrik veriyi temizler ve null döner → parolaya düşülür.
     */
    fun biometricDecryptCipher(): Cipher? {
        val iv = prefs.getString(PREF_BIO_IV, null)?.let(::unb64) ?: return null
        return try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val key = ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey ?: return null
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            clearBiometric()
            null
        } catch (e: Exception) {
            null
        }
    }

    /** BiometricPrompt onayından sonra dataKey'i çözer. */
    /** Biyometrik katmanı açar; PIN açıksa iç sargı, kapalıysa dataKey döner. */
    fun unlockWithBiometricStage1(authorizedCipher: Cipher): ByteArray? {
        val ct = prefs.getString(PREF_BIO_WRAPPED_KEY, null)?.let(::unb64) ?: return null
        return try {
            authorizedCipher.doFinal(ct)
        } catch (e: Exception) {
            null
        }
    }

    fun unlockWithBiometricCipher(authorizedCipher: Cipher): SecretKey? {
        if (isPinEnabled) return null
        val raw = unlockWithBiometricStage1(authorizedCipher) ?: return null
        return SecretKeySpec(raw, "AES")
    }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
}
