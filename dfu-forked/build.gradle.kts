/*
 * Vendored fork of no.nordicsemi.android:dfu:2.8.0 (Apache-2.0).
 *
 * Why this fork exists:
 *   Stock DfuBaseService.connect() waits on an *untimed* mLock.wait(), so every failed
 *   connection attempt blocks for Android's full ~30s create-connection timeout before it can
 *   retry. This peripheral fails the first CONNECT_IND (HCI 0x3E) and only accepts a fast retry
 *   (confirmed on an nRF dongle). The single change vs upstream is in DfuBaseService.connect():
 *   a bounded per-attempt establishment timeout + cancel(close) + fast retry, so a stuck attempt
 *   fails in ~6s and retries immediately. Search for "// FORK:" in DfuBaseService.java.
 *
 * To re-sync with a newer upstream: re-extract lib/dfu from the tag and re-apply the // FORK block.
 */
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "no.nordicsemi.android.dfu"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("module-rules.pro")
        // DfuBaseService references BuildConfig.VERSION_NAME; AGP no longer auto-generates it for
        // libraries, so provide it explicitly.
        buildConfigField("String", "VERSION_NAME", "\"2.8.0-fork\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // Pinned to match upstream 2.8.0; do not bump core to 1.13 (raises minSdk to 19).
    implementation("androidx.core:core:1.12.0")
    implementation("androidx.annotation:annotation:1.7.1")
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
    implementation("com.google.code.gson:gson:2.10.1")
}
