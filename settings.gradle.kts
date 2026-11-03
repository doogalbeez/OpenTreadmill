pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
    plugins {
        id("com.android.application") version "8.7.3"
        kotlin("jvm") version "2.0.21"
        kotlin("android") version "2.0.21"
        kotlin("plugin.compose") version "2.0.21"
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}
rootProject.name = "OpenTreadmill"
include(":core")
// The Android app needs the Android SDK. Skip it with -PcoreOnly on machines without one.
if (!providers.gradleProperty("coreOnly").isPresent) include(":app")
