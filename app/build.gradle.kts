import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Release imza bilgileri local.properties'ten okunur ve repoya girmez (R-006).
// Dosya ya da anahtarlar yoksa release imzasız derlenir; bu bilinçli — CI gibi
// anahtarı olmayan bir ortamda derleme kırılmamalı, ama Play'e giden AAB ancak
// bu makinede imzalanır.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val releaseStoreFile: String? = localProps.getProperty("RELEASE_STORE_FILE")

android {
    namespace = "com.afgover.vault"
    compileSdk = 36

    defaultConfig {
        // Marka: Sekuvo (2026-08-25). Paket kimliği sahip olunan alan adından;
        // ilk Play yüklemesinden sonra SONSUZA DEK değişemez. Kod paketleri
        // (namespace com.afgover.vault) bilinçli olarak eski adda — applicationId
        // ile namespace'in ayrışması desteklenen ve maliyetsiz yoldur.
        applicationId = "com.sekuvo.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = localProps.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = localProps.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = localProps.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseStoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true   // Hakkında sayfası sürüm adını buradan okur
    }

    /**
     * Play, AAB'yi VARSAYILAN OLARAK dile göre böler ve cihaza yalnız sistem
     * dilinin kaynaklarını indirir. Bu uygulamanın KENDİ dil seçicisi var
     * (AppLocale, 16 dil): bölme açıkken telefonu Türkçe olan kullanıcı
     * Ayarlar'dan Japonca seçtiğinde o dizeler cihazda bulunmaz ve arayüz
     * tabana — İngilizceye — düşer. Yani 16 dilin tamamı Play'den kuranlarda
     * çalışmaz; APK ile kurulumda görünmediği için sahada fark edilmesi zor
     * bir kusur (denetim, market öncesi).
     *
     * Android'in kendi "uygulama başına dil" API'si bunu Play Core olmadan
     * çözerdi ama API 33+ istiyor; burada minSdk 26. Bu yüzden diller taban
     * pakete gömülüyor: birkaç yüz KB karşılığında özellik gerçekten çalışır.
     */
    bundle {
        language {
            enableSplit = false
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.biometric)
    // biometric 1.1.0 geçişli olarak fragment 1.2.5'i getirir. O sürümdeki
    // FragmentActivity.startActivityForResult, requestCode'un yalnızca alt 16 biti
    // kullanmasını şart koşar; ActivityResultRegistry ise her zaman 0x10000'den büyük
    // kod üretir. Sonuç: rememberLauncherForActivityResult ile açılan her ekran
    // (yedek al / geri yükle / otomatik doldurma ayarı / Bluetooth izni) çöker.
    // Bu yüzden fragment sürümü açıkça yükseltiliyor — kaldırma.
    // (MainActivity zaten FragmentActivity'den türediği için doğrudan bağımlılık doğru.)
    implementation(libs.androidx.fragment)

    // QR ile doğrudan aktarım: kamera + çevrimdışı çözücü.
    // ML Kit bilinçli olarak kullanılmadı (Play Services bağımlılığı, SEC-019).
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)

    // JVM birim testleri: BackupManager java.util.Base64 kullanır (minSdk 26),
    // org.json ise Android'in çalışma zamanı sınıflarıyla aynı pakettir.
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)

    constraints {
        // Doğrudan bağımlılık ileride kaldırılsa bile hiçbir geçişli bağımlılık
        // fragment'i 1.7.1'in altına düşüremesin. (Eskiden bu bloğa dependencies
        // bloğunun kopyası yapışmıştı; kamera/zxing/test bağımlılıkları yanlışlıkla
        // kısıt olarak yineleniyor ve `because` yanlış bağımlılığa iliştirilmişti —
        // denetim temizledi.)
        implementation(libs.androidx.fragment) {
            because(
                "fragment 1.7.1'den eskisi ActivityResultRegistry'nin ürettiği " +
                    "requestCode'ları reddediyor (Can only use lower 16 bits for requestCode)"
            )
        }
    }
}

/**
 * 16 KB sayfa uyumu: `assembleDebug` sonrası yerel kütüphanelerin ELF
 * hizalaması ölçülür. 4 KB hizalı bir `.so` cihazda "uygulama 16 KB ile
 * uyumlu değil" uyarısı verir ve derlemede hiçbir iz bırakmaz; bu kontrol
 * onu derleme zamanına taşır (CameraX 1.3.4 ile tam bunu yaşadık).
 */
tasks.register<Exec>("checkApkAlignment") {
    dependsOn("assembleDebug")
    commandLine(
        "python3",
        "${rootDir}/tools/apk-hizalama.py",
        "${layout.buildDirectory.get()}/outputs/apk/debug/app-debug.apk"
    )
}

// Asıl önemli olan MARKET'e giden çıktı: release APK'yı da ölç (denetim —
// eskiden yalnız debug denetleniyordu). assembleRelease sonrası otomatik koşar.
// Dosya adı imza yapılandırmasına bağlı: imza varsa app-release.apk, yoksa
// app-release-unsigned.apk.
tasks.register<Exec>("checkReleaseApkAlignment") {
    dependsOn("assembleRelease")
    val apkName = if (releaseStoreFile != null) "app-release.apk" else "app-release-unsigned.apk"
    commandLine(
        "python3",
        "${rootDir}/tools/apk-hizalama.py",
        "${layout.buildDirectory.get()}/outputs/apk/release/$apkName"
    )
}
tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy("checkReleaseApkAlignment")
}
