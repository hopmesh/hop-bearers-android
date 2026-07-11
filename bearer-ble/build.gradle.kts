plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
// bearer-ble — an INDEPENDENT Android bearer library depending only on the Kotlin SDK (sh.hop).
android {
    namespace = "sh.hopme.bearers.ble"
    compileSdk = 34
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // quality-cov: JVM unit-test line coverage (AGP wires the jacoco exec + the
    // createDebugUnitTestCoverageReport task off this).
    buildTypes { debug { enableUnitTestCoverage = true } }
}
dependencies {
    implementation(project(":hop-sdk"))   // Bearer/LinkSink/HopRole contract + transport helpers

    testImplementation("junit:junit:4.13.2") // R7 dial-backoff unit test (pure JVM, no Android SDK)
}
