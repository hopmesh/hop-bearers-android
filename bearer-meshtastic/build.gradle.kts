import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    jacoco
}
// bearer-meshtastic - the Meshtastic/LoRa transport as a fully INDEPENDENT Android library depending only
// on the Kotlin SDK (sh.hop). The Android mirror of bearers/apple/HopBearerMeshtastic.
android {
    namespace = "sh.hopme.bearers.meshtastic"
    compileSdk = 37 // aligned across both builds; core-ktx 1.19.0 requires >= 37 from every consumer
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests { isIncludeAndroidResources = true } }
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":hop-sdk"))   // Bearer/LinkSink/HopRole contract + transport helpers

    // Pure-JVM unit tests for the Meshtastic protobuf codec, the fragment/reassembly layer, the Hop
    // link-frame grammar, dedup, AND the full MeshtasticBearer state machine driven against a fake radio.
    // No Robolectric is needed for those (they touch no android.jar), but it is on hand for parity with
    // the other bearer suites.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.7.0")
}

// Line-coverage report + 80% floor over the bearer's OWN Kotlin. Pin JaCoCo to 0.8.11 to match the
// class-file version Robolectric's instrumenting classloader emits (a skew reads every class as 0%).
jacoco { toolVersion = "0.8.11" }

tasks.withType<Test>().configureEach {
    extensions.configure(JacocoTaskExtension::class) {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

// DOCUMENTED DENOMINATOR (mirrors :bearer-lan's NSD-glue exclusion): the device-bound BLE GATT client is
// excluded. AndroidMeshtasticRadio drives a real BluetoothGatt peer that Robolectric's shadow cannot
// exchange protobuf frames with, and an emulator has no Meshtastic radio. The pure Meshtastic protocol
// (protobuf, fragmentation, reassembly), the Hop link-frame grammar, dedup, and the full MeshtasticBearer
// state machine (connect, discover, reassemble, keepalive, reap, send) are all exercised against a fake
// radio; only the un-drivable GATT class + its callbacks are excluded here.
private val meshCoverageExclusions = listOf(
    "**/AndroidMeshtasticRadio.class",
    "**/AndroidMeshtasticRadio\$*.class",
)

tasks.register<JacocoReport>("jacocoMeshtasticReport") {
    dependsOn("testDebugUnitTest")
    reports { xml.required.set(true); html.required.set(true) }
    classDirectories.setFrom(
        fileTree("${buildDir}/tmp/kotlin-classes/debug") { exclude(meshCoverageExclusions) },
    )
    sourceDirectories.setFrom(files("src/main/java"))
    executionData.setFrom(fileTree(buildDir) { include("jacoco/testDebugUnitTest.exec") })
}

tasks.register<JacocoCoverageVerification>("jacocoMeshtasticCoverageVerification") {
    dependsOn("testDebugUnitTest")
    classDirectories.setFrom(
        fileTree("${buildDir}/tmp/kotlin-classes/debug") { exclude(meshCoverageExclusions) },
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
