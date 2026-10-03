// UzNav — O'zbek Navigator (app moduli)
// Debug APK:   ./gradlew assembleDebug
// Release APK: ./gradlew assembleRelease

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/**
 * Release imzosi uchun kalitlar `keystore.properties` faylidan o'qiladi.
 * Agar fayl bo'lmasa (masalan, GitHub Actions'da yoki birinchi sinovda),
 * release APK debug kalit bilan imzolanadi — shunda `assembleRelease`
 * har doim ishlaydi va APK telefonga o'rnatiladi.
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseKeystore = keystoreProperties.getProperty("storeFile") != null &&
        rootProject.file(keystoreProperties.getProperty("storeFile")).exists()

android {
    namespace = "uz.uznav.navigator"
    compileSdk = 34

    defaultConfig {
        applicationId = "uz.uznav.navigator"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        // Ilova interfeysi to'liq o'zbek tilida (standart resurslar — values/)
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            // Mapsforge XML render-tema va AndroidSVG reflection ishlatadi, shuning uchun
            // kod qisqartirish standart holatda o'chirilgan. Yoqmoqchi bo'lsangiz
            // proguard-rules.pro dagi qoidalar allaqachon tayyor.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/DEPENDENCIES",
                "META-INF/*.kotlin_module"
            )
        }
    }

    lint {
        // Build CI'da lint xatolari tufayli to'xtab qolmasin
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.google.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.android)

    // Offline xarita (OpenStreetMap ma'lumotlari .map formatida)
    implementation(libs.mapsforge.core)
    implementation(libs.mapsforge.map)
    implementation(libs.mapsforge.map.reader)
    implementation(libs.mapsforge.map.android)
    implementation(libs.mapsforge.themes)
    implementation(libs.androidsvg)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
}
