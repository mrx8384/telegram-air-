// Screen automation: drives the official Telegram app through an
// AccessibilityService, with ML Kit OCR as a fallback for finding text.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.tcam.screen"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.mlkit.text.recognition)
    testImplementation(libs.junit)
}
