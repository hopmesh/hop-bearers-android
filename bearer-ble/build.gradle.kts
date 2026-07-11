plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    jacoco
}
// bearer-ble - an INDEPENDENT Android bearer library depending only on the Kotlin SDK (sh.hop).
android {
    namespace = "sh.hopme.bearers.ble"
    compileSdk = 34
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // quality-cov: Robolectric shadows android.util.Log so LinkProtocol's framing/dispatch loop (which
    // logs on every lifecycle edge) runs over in-memory streams.
    testOptions { unitTests { isIncludeAndroidResources = true } }
}
dependencies {
    implementation(project(":hop-sdk"))   // Bearer/LinkSink/HopRole contract + transport helpers

    // R7 dial-backoff / dedup / iBeacon unit tests (pure JVM, no Android SDK), PLUS the extracted
    // LinkProtocol (framing/dispatch over streams, under Robolectric for android.util.Log) and the
    // pure DialState (dial-slot / backoff / suppression bookkeeping) tests.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
}

// quality-cov / cov/android-bearers: line-coverage report + 80% floor over the BLE bearer's testable
// surface. Pin JaCoCo to 0.8.11 (matches Robolectric's instrumented class-file version).
jacoco { toolVersion = "0.8.11" }

tasks.withType<Test>().configureEach {
    extensions.configure(JacocoTaskExtension::class) {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

// DOCUMENTED DENOMINATOR (mirrors the driver's uniffi/** exclusion + KeystoreSecret rationale): the
// genuinely device-bound BLE radio classes are EXCLUDED and covered by the on-device workflow instead,
// because neither Robolectric nor an emulator has a Bluetooth radio:
//   - Link            : socket byte-I/O over a real BluetoothSocket (its pure wire logic was lifted into
//                       LinkProtocol, which IS in the denominator and unit-tested).
//   - Peripheral      : the GATT server (openGattServer) + AdvertisingSet advertiser/beacon.
//   - Central         : live connectGatt / discoverServices / createInsecureL2capChannel + the GATT
//                       callback threads + the scan throttle (its pure dial bookkeeping was lifted into
//                       DialState, which IS in the denominator and unit-tested).
//   - BleBearer       : the dual-role radio bring-up, the BluetoothAdapter on/off BroadcastReceiver,
//                       and the STATUS timer - all radio/framework wiring.
//   - BleBearerKt     : the file facade that initializes Android-typed top-level vals (ParcelUuid
//                       SERVICE_UUID) + reflective SystemProperties reads (sysProp).
// What REMAINS in the denominator is the pure, unit-tested logic: LinkProtocol (framing/dispatch),
// DialState (dial gating/backoff), DialBackoff (the schedule), BleDedup (the keep-rule), BleBeacon
// (the iBeacon layout).
private val bleCoverageExclusions = listOf(
    "**/Link.class", "**/Link\$*.class",
    "**/Peripheral.class", "**/Peripheral\$*.class",
    "**/Central.class", "**/Central\$*.class",
    "**/BleBearer.class", "**/BleBearer\$*.class",
    "**/BleBearerKt.class",
)

tasks.register<JacocoReport>("jacocoBleReport") {
    dependsOn("testDebugUnitTest")
    reports { xml.required.set(true); html.required.set(true) }
    classDirectories.setFrom(
        fileTree("${buildDir}/tmp/kotlin-classes/debug") { exclude(bleCoverageExclusions) },
    )
    sourceDirectories.setFrom(files("src/main/java"))
    executionData.setFrom(fileTree(buildDir) { include("jacoco/testDebugUnitTest.exec") })
}

tasks.register<JacocoCoverageVerification>("jacocoBleCoverageVerification") {
    dependsOn("testDebugUnitTest")
    classDirectories.setFrom(
        fileTree("${buildDir}/tmp/kotlin-classes/debug") { exclude(bleCoverageExclusions) },
    )
    sourceDirectories.setFrom(files("src/main/java"))
    executionData.setFrom(fileTree(buildDir) { include("jacoco/testDebugUnitTest.exec") })
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}
