plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
// bearer-relay — an INDEPENDENT Android bearer library depending only on the Kotlin SDK (sh.hop).
android {
    namespace = "sh.hopme.bearers.relay"
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
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
