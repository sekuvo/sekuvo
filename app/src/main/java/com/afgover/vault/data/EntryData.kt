package com.afgover.vault.data

import androidx.annotation.StringRes
import com.afgover.vault.R
import org.json.JSONArray
import org.json.JSONObject

/**
 * Kullanıcının eklediği alanın türü. Tür değerin NE OLDUĞUNU söyler:
 * düzenlerken hangi klavyenin açılacağını, şifrenin maskelenip
 * maskelenmeyeceğini ve üretici düğmesinin görünüp görünmeyeceğini.
 *
 * [stable] JSON'a yazılan, dile bağlı olmayan addır ([FieldKey] ile aynı
 * gerekçe). Bilinmeyen ya da eksik tür [TEXT] okunur: türsüz eski kayıtlar
 * ve daha yeni bir sürümün yazdığı tanımadığımız türler metin olarak kalır.
 */
enum class CustomFieldType(@StringRes val labelRes: Int, val stable: String) {
    TEXT(R.string.field_type_text, "text"),
    PASSWORD(R.string.field_type_password, "password"),
    EMAIL(R.string.field_type_email, "email"),
    PHONE(R.string.field_type_phone, "phone"),
    URL(R.string.field_type_url, "url"),
    NUMBER(R.string.field_type_number, "number"),
    MULTILINE(R.string.field_type_multiline, "multiline");

    companion object {
        fun ofStable(name: String?): CustomFieldType = entries.firstOrNull { it.stable == name } ?: TEXT
    }
}

/** Kullanıcının kendi tanımladığı ek alan (ad + değer + tür). */
data class CustomField(
    val label: String,
    val value: String,
    val type: CustomFieldType = CustomFieldType.TEXT
)

/**
 * Geride bırakılmış bir parola ve **ne zaman** bırakıldığı (epoch ms).
 *
 * Bir sızıntı duyulduğunda sorulan soru "o tarihte hangi parola
 * kullanılıyordu" oluyor; parolayı üzerine yazmak bu cevabı yok ediyordu.
 * Geçmiş şifreli gövdenin içinde durur — yani yedeğe de kendiliğinden girer,
 * ayrı bir saklama yeri yoktur.
 */
data class OldPassword(val value: String, val changedAt: Long)

/**
 * Yerleşik alanların KİMLİĞİ. Etiket artık metin değil anahtar:
 *
 * - [labelRes] yalnız EKRANDA gösterilen metindir, dile göre değişir.
 * - [stable] ise günlüğe ve pano etiketine yazılan, **dile bağlı olmayan**
 *   addır. Eskiden buraya Türkçe etiket yazılıyordu; uygulama İngilizceye
 *   açılınca aynı kaydın geçmişi iki dile bölünürdü.
 * - Maskeleme de metin karşılaştırmasıyla değil kimlikle yapılır
 *   ([EntryField.hidden]): "Şifre" metnine bakan bir kontrol, etiket
 *   çevrildiği anda şifreyi maskesiz gösterirdi.
 */
enum class FieldKey(@StringRes val labelRes: Int, val stable: String) {
    FULL_NAME(R.string.field_full_name, "fullName"),
    USERNAME(R.string.field_username, "username"),
    PASSWORD(R.string.field_password, "password"),
    URL(R.string.field_url, "url"),
    PHONE(R.string.field_phone, "phone"),
    EMAIL(R.string.field_email, "email"),
    ADDRESS(R.string.field_address, "address"),
    CARDHOLDER(R.string.field_cardholder, "cardholder"),
    CARD_NUMBER(R.string.field_card_number, "cardNumber"),
    EXPIRY(R.string.field_expiry, "expiry"),
    CVV(R.string.field_cvv, "cvv"),
    IBAN(R.string.field_iban, "iban"),
    NOTES(R.string.field_notes, "notes");

    companion object {
        /** Günlükte duran kararlı ada karşılık gelen alan; bilinmiyorsa null. */
        fun ofStable(name: String?): FieldKey? = entries.firstOrNull { it.stable == name }
    }
}

/**
 * Gösterilecek tek alan. Yerleşikse [key] doludur; kullanıcının kendi
 * eklediği alansa [customLabel] doludur (o zaten kullanıcının yazdığı metin,
 * çevrilmez).
 */
data class EntryField(
    val key: FieldKey?,
    val customLabel: String?,
    val value: String,
    val customType: CustomFieldType? = null
) {
    /** Günlüğe/panoya yazılan ad — dile bağlı DEĞİL. */
    val stableName: String get() = key?.stable ?: customLabel.orEmpty()

    /** Varsayılan olarak gizlenir mi (şifre, CVV)? Metin değil kimlik sorusu. */
    val hidden: Boolean
        get() = key == FieldKey.PASSWORD || key == FieldKey.CVV ||
            customType == CustomFieldType.PASSWORD
}

/**
 * Bir kaydın şifrelenen alanları. Türe göre ilgili alanlar doldurulur;
 * boş alanlar JSON'a yazılmaz. Yeni alanlar sona eklenir: eski yedeklerde
 * bulunmayan alanlar boş gelir, biçim geriye dönük uyumludur.
 */
data class EntryData(
    val username: String = "",
    val password: String = "",
    val url: String = "",
    val cardholder: String = "",
    val cardNumber: String = "",
    val expiry: String = "",
    val cvv: String = "",
    val iban: String = "",
    val notes: String = "",
    // Gündelik bilgiler
    val fullName: String = "",
    val phone: String = "",
    val email: String = "",
    val address: String = "",
    // Kullanıcının eklediği serbest alanlar
    val custom: List<CustomField> = emptyList(),
    /**
     * Şifre alanının son değiştiği an (epoch ms); 0 = bilinmiyor (eski kayıt).
     * Eskiyen parola uyarısının temeli. Şifreli veride durur: yalnız kilit
     * açıkken okunacak bir bilgidir.
     */
    val passwordChangedAt: Long = 0,
    /**
     * Eski parolalar, **en yenisi başta**. Yalnız parola gerçekten değişince
     * büyür ([withNewPassword]); kayıt her kaydedildiğinde değil.
     *
     * Bilerek [fields] dışında: klavye ve otomatik doldurma yalnız güncel
     * parolayı yazmalı, eski bir parola oraya asla düşmemeli.
     */
    val passwordHistory: List<OldPassword> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        if (username.isNotEmpty()) put("username", username)
        if (password.isNotEmpty()) put("password", password)
        if (url.isNotEmpty()) put("url", url)
        if (cardholder.isNotEmpty()) put("cardholder", cardholder)
        if (cardNumber.isNotEmpty()) put("cardNumber", cardNumber)
        if (expiry.isNotEmpty()) put("expiry", expiry)
        if (cvv.isNotEmpty()) put("cvv", cvv)
        if (iban.isNotEmpty()) put("iban", iban)
        if (notes.isNotEmpty()) put("notes", notes)
        if (fullName.isNotEmpty()) put("fullName", fullName)
        if (phone.isNotEmpty()) put("phone", phone)
        if (email.isNotEmpty()) put("email", email)
        if (address.isNotEmpty()) put("address", address)
        if (custom.isNotEmpty()) {
            put("custom", JSONArray().apply {
                custom.forEach { field ->
                    put(JSONObject().put("label", field.label).put("value", field.value).apply {
                        // Metin varsayılan: yazılmaz, türsüz eski biçimle aynı kalır.
                        if (field.type != CustomFieldType.TEXT) put("type", field.type.stable)
                    })
                }
            })
        }
        if (passwordChangedAt > 0) put("passwordChangedAt", passwordChangedAt)
        if (passwordHistory.isNotEmpty()) {
            put("passwordHistory", JSONArray().apply {
                passwordHistory.forEach { eski ->
                    put(JSONObject().put("value", eski.value).put("changedAt", eski.changedAt))
                }
            })
        }
    }

    /** IME ve detay ekranında gösterilecek (etiket, değer) çiftleri. */
    fun fields(): List<EntryField> = buildList {
        fun yerlesik(key: FieldKey, value: String) {
            if (value.isNotEmpty()) add(EntryField(key, null, value))
        }
        yerlesik(FieldKey.FULL_NAME, fullName)
        yerlesik(FieldKey.USERNAME, username)
        yerlesik(FieldKey.PASSWORD, password)
        yerlesik(FieldKey.URL, url)
        yerlesik(FieldKey.PHONE, phone)
        yerlesik(FieldKey.EMAIL, email)
        yerlesik(FieldKey.ADDRESS, address)
        yerlesik(FieldKey.CARDHOLDER, cardholder)
        yerlesik(FieldKey.CARD_NUMBER, cardNumber)
        yerlesik(FieldKey.EXPIRY, expiry)
        yerlesik(FieldKey.CVV, cvv)
        yerlesik(FieldKey.IBAN, iban)
        custom.forEach { field ->
            if (field.value.isNotEmpty()) add(EntryField(null, field.label, field.value, field.type))
        }
        yerlesik(FieldKey.NOTES, notes)
    }

    companion object {
        fun fromJson(json: JSONObject): EntryData = EntryData(
            username = json.optString("username"),
            password = json.optString("password"),
            url = json.optString("url"),
            cardholder = json.optString("cardholder"),
            cardNumber = json.optString("cardNumber"),
            expiry = json.optString("expiry"),
            cvv = json.optString("cvv"),
            iban = json.optString("iban"),
            notes = json.optString("notes"),
            fullName = json.optString("fullName"),
            phone = json.optString("phone"),
            email = json.optString("email"),
            address = json.optString("address"),
            custom = json.optJSONArray("custom").toCustomFields(),
            passwordChangedAt = json.optLong("passwordChangedAt"),
            passwordHistory = json.optJSONArray("passwordHistory").toOldPasswords()
        )

        private fun JSONArray?.toOldPasswords(): List<OldPassword> {
            if (this == null) return emptyList()
            return buildList {
                for (i in 0 until length()) {
                    val o = optJSONObject(i) ?: continue
                    val v = o.optString("value")
                    if (v.isNotEmpty()) add(OldPassword(v, o.optLong("changedAt")))
                }
            }
        }

        private fun JSONArray?.toCustomFields(): List<CustomField> {
            if (this == null) return emptyList()
            return buildList {
                for (i in 0 until length()) {
                    val o = optJSONObject(i) ?: continue
                    val label = o.optString("label")
                    if (label.isNotEmpty()) {
                        add(CustomField(label, o.optString("value"), CustomFieldType.ofStable(o.optString("type"))))
                    }
                }
            }
        }
    }
}

/** Bellekte çözülmüş kayıt. */
data class DecryptedEntry(
    val id: Long,
    val type: EntryType,
    val title: String,
    val data: EntryData,
    val createdAt: Long,
    val updatedAt: Long,
    /** Klavyede kasa kilitliyken de kullanılabilir mi? */
    val quick: Boolean = false,
    val tagIds: List<Long> = emptyList(),
    /** Yalnız yedek içe aktarmada dolu: id'ler cihaza özgüdür, yedek ad taşır. */
    val tagNames: List<String> = emptyList(),
    val noteKind: NoteKind = NoteKind.GENEL,
    val sortIndex: Int = 0,
    /** Araç çıpası kaydı mı (bkz. [EntryEntity.anchor]). */
    val anchor: Boolean = false
)
