import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.aditya.monoweather"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.aditya.monoweather"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }
    // Release key lives outside the repo (~/.android-keys). Without it, release builds fall back to the debug key.
    val keyProps = Properties().apply {
        val f = file("${System.getProperty("user.home")}/.android-keys/mono-release.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        if (keyProps.isNotEmpty()) create("release") {
            storeFile = file(keyProps.getProperty("storeFile"))
            storePassword = keyProps.getProperty("storePassword")
            keyAlias = keyProps.getProperty("keyAlias")
            keyPassword = keyProps.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { disable += "RestrictedApi" }
    buildFeatures { compose = true }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    // Remote Compose: real, launcher-side animation for widgets on Android 16+ (falls back to frame widgets below that)
    implementation("androidx.compose.remote:remote-creation-android:1.0.0-alpha22")
    implementation("androidx.compose.remote:remote-core:1.0.0-alpha22")
    // Xiaomi Super Island (focus notification) payloads — Apache-2.0, github.com/D4vidDf/HyperIsland-ToolKit
    implementation("io.github.d4viddf:hyperisland_kit:0.4.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
