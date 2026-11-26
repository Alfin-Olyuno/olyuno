plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.tunes"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.oyluno.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.2"
    }

    // Keystore release tetap (dari environment / GitHub Secrets) supaya setiap APK bisa dipasang sebagai
    // update di atas versi sebelumnya. Tanpa keystore, build jatuh ke debug key (hanya untuk uji lokal;
    // debug key dibuat acak per mesin, jadi APK dari mesin berbeda saling konflik).
    val releaseKeystore = System.getenv("OLYUNO_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }?.let { File(it) }
    val hasReleaseKeystore = releaseKeystore != null && releaseKeystore.isFile
    if (hasReleaseKeystore) {
        signingConfigs {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("OLYUNO_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("OLYUNO_KEY_ALIAS")
                keyPassword = System.getenv("OLYUNO_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 sengaja dimatikan supaya build aman; aktifkan nanti kalau mau APK lebih kecil.
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // API Material3 eksperimental (Slider dkk.) dipakai di banyak layar; izinkan di seluruh modul.
        freeCompilerArgs += "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
    }
    buildFeatures {
        compose = true
    }
    lint {
        // Lint Media3 (@UnstableApi) sering rewel di build release; tidak perlu untuk pemakaian pribadi.
        abortOnError = false
        checkReleaseBuilds = false
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.androidx.work.runtime.ktx)
}
