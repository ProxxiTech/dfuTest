plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.bledfutesteractivity"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.bledfutesteractivity"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Two installable variants (distinct applicationId) toggling the native→Kotlin ACL hand-off.
    flavorDimensions += "mode"
    productFlavors {
        // AOSP-only connect: native warm-up + DFU library, NO Kotlin ClientBleGatt step.
        // Equivalent to the fork-dfu-timeout baseline (100/100).
        create("aosp") {
            dimension = "mode"
            applicationIdSuffix = ".aosp"
            manifestPlaceholders["appLabel"] = "Proxxi DFU AOSP"
            buildConfigField("boolean", "ACL_HANDOFF_ENABLED", "false")
        }
        // Adds the native→Kotlin ACL hand-off validation step (ClientBleGatt.connect).
        create("handoff") {
            dimension = "mode"
            manifestPlaceholders["appLabel"] = "Proxxi DFU Handoff"
            buildConfigField("boolean", "ACL_HANDOFF_ENABLED", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    // This removes the need for the Compose build features
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += setOf("META-INF/NOTICE.md", "META-INF/LICENSE.md")
        }
    }
}

dependencies {
    // Core dependencies for a View-based app
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.google.material)
    implementation(libs.androidx.constraintlayout)

    // DFU Tester App specific dependencies
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.activity.ktx) // Provides by viewModels()
    implementation(libs.android.mail)
    implementation(libs.android.activation)
    // Vendored fork of no.nordicsemi.android:dfu:2.8.0 with a fast-fail/fast-retry connect().
    implementation(project(":dfu-forked"))
    // Nordic Kotlin BLE client — used only to validate the native→Kotlin ACL hand-off.
    implementation(libs.nordic.ble.client)

    // Testing dependencies
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}