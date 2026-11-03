plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
}

android {
    namespace = "app.opentreadmill"
    compileSdk = 34
    defaultConfig {
        applicationId = "app.opentreadmill"
        minSdk = 24
        targetSdk = 34
        versionCode = 4
        versionName = "0.3.0"
    }
    // The signing key is kept out of the repo. Builds that should install over the console's copy
    // (yours, and CI's) point at it with these settings, from ~/.gradle/gradle.properties or the
    // environment. Without them the build uses Android's throwaway debug key, which can't update
    // the installed app. See docs/SIGNING.md.
    fun setting(name: String): String? =
        providers.gradleProperty(name).orNull ?: providers.environmentVariable(name).orNull
    val keystore = setting("OPENTREADMILL_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (keystore != null) create("opentreadmill") {
            storeFile = keystore
            storePassword = setting("OPENTREADMILL_KEYSTORE_PASSWORD")
            keyAlias = setting("OPENTREADMILL_KEY_ALIAS") ?: "opentreadmill"
            keyPassword = setting("OPENTREADMILL_KEY_PASSWORD") ?: setting("OPENTREADMILL_KEYSTORE_PASSWORD")
        }
    }
    buildTypes {
        getByName("debug") {
            if (keystore != null) signingConfig = signingConfigs.getByName("opentreadmill")
            else logger.warn("OpenTreadmill: no signing key set (OPENTREADMILL_KEYSTORE); this APK can't update the installed app.")
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
