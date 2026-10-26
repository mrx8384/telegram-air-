import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Optional build-time defaults for the Telegram API credentials of *this app*
// (from https://my.telegram.org). Put them in telegram.properties (git-ignored):
//   TELEGRAM_API_ID=12345
//   TELEGRAM_API_HASH=abcdef...
// They can also be entered in the app's Settings screen.
val telegramProps = Properties().apply {
    val f = rootProject.file("telegram.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.tcam.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.tcam.channelmanager"
        minSdk = 29 // Android 10
        targetSdk = 36 // Android 16
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField("int", "TELEGRAM_API_ID", (telegramProps.getProperty("TELEGRAM_API_ID") ?: "0"))
        buildConfigField("String", "TELEGRAM_API_HASH", "\"${telegramProps.getProperty("TELEGRAM_API_HASH") ?: ""}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        generateLocaleConfig = true
        // The app ships English and Persian; drop other library translations.
        localeFilters += listOf("en", "fa")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Robolectric needs these on JDK 17+.
            it.jvmArgs(
                "--add-exports", "java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens", "java.base/java.io=ALL-UNNAMED",
                "--add-opens", "java.base/java.lang=ALL-UNNAMED",
            )
            it.maxHeapSize = "3g"
        }
    }

    packaging {
        // Compress native libraries (TDLib, ML Kit) inside the APK to keep downloads small.
        jniLibs { useLegacyPackaging = true }
        // Compress dex too: the TDLib API classes are large and debug builds are not minified.
        dex { useLegacyPackaging = true }
    }

    // One APK per CPU type plus a universal APK. Most phones need arm64-v8a.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":automation-tdlib"))
    implementation(project(":automation-accessibility"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
