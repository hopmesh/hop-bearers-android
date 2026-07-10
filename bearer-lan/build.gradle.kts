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

    // quality-net-03: pure-JVM unit tests for the LAN wire codec + one-pipe-per-peer dedup keep-rule
    // (LanWire.kt / LanDedup are deliberately Android-free so they load under testDebugUnitTest).
    testImplementation("junit:junit:4.13.2")
}
