package com.afgover.vault.core

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Tüm şifreleme burada: AES-256-GCM + PBKDF2-HMAC-SHA256.
 * Anahtar hiyerarşisi Android Keystore'a bağımlı DEĞİLDİR; ana parola ile
 * türetilir. Böylece yedek dosyası cihaz sıfırlansa bile sadece ana parola
 * ile geri yüklenebilir.
 */
object Crypto {

    /**
     * Yeni yazılan kasa sargısı ve yedeklerin tur sayısı (OWASP'ın güncel
     * önerisi). Tur sayısı hem sargıda hem yedekte yazılı olduğu için eski
     * kayıtlar kendi sayılarıyla açılır; okuyucular 1.000.000'a kadar kabul
     * eder, yani eski sürümler de yeni yedekleri açar.
     */
    const val KDF_ITERATIONS = 600_000

    /**
     * 1.3'ten önce her şey bu sayıyla yazıldı. Tur sayısı yazılmamış bir
     * kayıt bununla okunur; PIN sargısı sayıyı hiç saklamadığı için hep
     * bununla türetilir ([PinLock.ITERATIONS]).
     */
    const val LEGACY_KDF_ITERATIONS = 310_000

    const val KEY_BITS = 256
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    private val random = SecureRandom()

    fun randomBytes(count: Int): ByteArray = ByteArray(count).also { random.nextBytes(it) }

    fun randomKey(): SecretKey = SecretKeySpec(randomBytes(KEY_BITS / 8), "AES")

    /** Bu tur sayısıyla yazılmış bir sargı bugünkü sayıyla yeniden sarılmalı mı? */
    fun needsKdfUpgrade(storedIterations: Int): Boolean = storedIterations < KDF_ITERATIONS

    fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int = KDF_ITERATIONS): SecretKey {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    /**
     * Çıktı: iv(12 bayt) || şifreli veri+tag.
     *
     * [aad] (ilişkili veri) verilirse mühür ona bağlanır: aynı baytlar
     * verilmeden açılmaz. Cihazdaki kayıtlar bununla yerlerine bağlanır
     * ([com.afgover.vault.data.KayitBicimi]); yedek zarfı AAD kullanmaz.
     */
    fun encrypt(key: SecretKey, plaintext: ByteArray, aad: ByteArray? = null): ByteArray {
        val iv = randomBytes(GCM_IV_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        if (aad != null) cipher.updateAAD(aad)
        val ct = cipher.doFinal(plaintext)
        return iv + ct
    }

    /**
     * Android Keystore anahtarları IV'yi kendileri üretir; dışarıdan IV vermek
     * yasaktır. Çıktı biçimi [encrypt] ile aynıdır (iv || şifreli veri+tag),
     * bu yüzden çözme tarafı ortaktır: [decrypt].
     */
    fun encryptWithGeneratedIv(key: SecretKey, plaintext: ByteArray, aad: ByteArray? = null): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        if (aad != null) cipher.updateAAD(aad)
        return cipher.iv + cipher.doFinal(plaintext)
    }

    /**
     * Girdi: iv(12 bayt) || şifreli veri+tag. Yanlış anahtar, bozuk veri ya da
     * mühürlenirkenkinden farklı [aad] null döner.
     */
    fun decrypt(key: SecretKey, blob: ByteArray, aad: ByteArray? = null): ByteArray? {
        if (blob.size <= GCM_IV_BYTES) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(GCM_TAG_BITS, blob.copyOfRange(0, GCM_IV_BYTES))
            )
            if (aad != null) cipher.updateAAD(aad)
            cipher.doFinal(blob.copyOfRange(GCM_IV_BYTES, blob.size))
        } catch (e: AEADBadTagException) {
            null
        } catch (e: Exception) {
            null
        }
    }
}
