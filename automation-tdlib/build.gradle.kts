import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Background automation through TDLib. Plain Kotlin/JVM so it can be unit-tested
// on a desktop JVM; the Android app pulls the Android variant of the same
// TDLib library (with its native .so files) through Gradle variant matching.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(project(":core"))
    api(libs.tdl.coroutines)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
