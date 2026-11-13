plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

// Sideload signing key. The default key lives in the repo on purpose (see README → "Подпись APK"): the app is only
// ever installed by sideloading, and a stable key lets `adb install -r` update an existing install without wiping
// its settings. Override with your own key through environment variables if you prefer.
val sideloadKeystore = System.getenv("MONOTV_KEYSTORE") ?: rootProject.file("keystore/sideload.jks").path
val sideloadStorePassword = System.getenv("MONOTV_KEYSTORE_PASSWORD") ?: "monotv-sideload"
val sideloadKeyAlias = System.getenv("MONOTV_KEY_ALIAS") ?: "monotv"
val sideloadKeyPassword = System.getenv("MONOTV_KEY_PASSWORD") ?: sideloadStorePassword

android {
    namespace = "tv.monotv.launcher"
    compileSdk = 37

    defaultConfig {
        applicationId = "tv.monotv.launcher"
        // Sber TV / SberBox firmwares in the field are Android 9, 11 and 13 (API 28–33). 23 is simply the floor of
        // current Jetpack Compose, so every one of them is covered.
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        create("sideload") {
            storeFile = file(sideloadKeystore)
            storePassword = sideloadStorePassword
            keyAlias = sideloadKeyAlias
            keyPassword = sideloadKeyPassword
        }
    }

    buildTypes {
        debug {
            // Same key as release so debug and release builds can replace each other on the TV.
            signingConfig = signingConfigs.getByName("sideload")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/*.kotlin_module", "kotlin/**", "DebugProbesKt.bin")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                // Optional override for restricted networks: -ProbolectricRepoUrl=https://repo1.maven.org/maven2
                providers.gradleProperty("robolectricRepoUrl").orNull?.let {
                    test.systemProperty("robolectric.dependency.repo.url", it)
                }
                test.maxHeapSize = "2g"
            }
        }
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.org.json)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
