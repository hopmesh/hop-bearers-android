plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
// bearer-lan — the LAN transport (NSD + TCP) as a fully INDEPENDENT Android library depending only on
// the Kotlin SDK (sh.hop). The Android mirror of bearers/apple/HopBearerLan.
android {
    namespace = "sh.hopme.bearers.lan"
    compileSdk = 34
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":hop-sdk"))   // Bearer/LinkSink/HopRole contract + transport helpers
}
