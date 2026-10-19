package com.afgover.vault.core

import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * İsteğe bağlı ikinci kapı: PIN (vault_takip B-066).
 *
 * PIN bir **arayüz kontrolü değil**, kriptografik bir katmandır. Açıkken
 * saklanan sargının içinde doğrudan `dataKey` değil, PIN'den türeyen anahtarla
 * şifrelenmiş hâli (*iç sargı*) durur:
 *
 * ```
 * parola  → Enc(KEK_parola, dataKey)               (PIN sorulmaz)
 * parmak  → Enc(K_keystore, Enc(K_pin, dataKey))   (PIN şart)
 * ```
 *
 * PIN, ana parola yolunu değil **parmak izi yolunu** korur: korunmak istenen
 * senaryo "biri parmağımı kullanır"dır; ana parolanın üstüne PIN sormak
 * yalnız sürtünme olurdu. Düşük entropi kabul edilebilir, çünkü iç sargıya
 * ulaşmak için önce cihazın biyometrik Keystore anahtarını geçmek gerekir —
 * PIN'i tek başına deneyecek saldırgan için elde blob yoktur. Kurtarma
 * garantisi de korunur: ana parola her zaman tek başına açar.
 *
 * Yanlış PIN "rastgele çözme" üretmez: GCM etiketi tutmaz ve `null` döner.
 */
object PinLock {

    /**
     * Sabit: PIN sargısı ve doğrulama blobu tur sayısını saklamaz, yani bu
     * sayı değişirse bugünkü PIN'ler açılmaz. Ana parolanın tur sayısından
     * bağımsızdır; PIN'i asıl koruyan Keystore anahtarıdır, tur sayısı değil.
     */
    const val ITERATIONS = Crypto.LEGACY_KDF_ITERATIONS

    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 12

    fun isValid(pin: String): Boolean =
        pin.length in MIN_LENGTH..MAX_LENGTH && pin.all { it.isDigit() }

    private fun key(pin: String, salt: ByteArray): SecretKey =
        Crypto.deriveKey(pin.toCharArray(), salt, ITERATIONS)

    /** `dataKey` → iç sargı. */
    fun wrap(dataKey: SecretKey, pin: String, salt: ByteArray): ByteArray =
        Crypto.encrypt(key(pin, salt), dataKey.encoded)

    private const val CHECK_TEXT = "vault-pin-ok"

    /** PIN'i sargıya dokunmadan sınamak için küçük doğrulama blobu. */
    fun wrapCheck(pin: String, salt: ByteArray): ByteArray =
        Crypto.encrypt(key(pin, salt), CHECK_TEXT.toByteArray())

    fun unwrapCheck(blob: ByteArray, pin: String, salt: ByteArray): Boolean =
        Crypto.decrypt(key(pin, salt), blob)?.toString(Charsets.UTF_8) == CHECK_TEXT

    /** İç sargı → `dataKey`; PIN yanlışsa null. */
    fun unwrap(inner: ByteArray, pin: String, salt: ByteArray): SecretKey? =
        Crypto.decrypt(key(pin, salt), inner)?.let { SecretKeySpec(it, "AES") }
}
