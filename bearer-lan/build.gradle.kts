import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    jacoco
}
// bearer-lan - the LAN transport (NSD + TCP) as a fully INDEPENDENT Android library depending only on
// the Kotlin SDK (sh.hop). The Android mirror of bearers/apple/HopBearerLan.
android {
    namespace = "sh.hopme.bearers.lan"
    compileSdk = 37 // aligned across both builds; core-ktx 1.19.0 requires >= 37 from every consumer
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // quality-cov: Robolectric loads android resources through the merged test config; keep them on so
    // the RobolectricTestRunner can build a real Application/Context (LanBearer needs a Context).
    testOptions { unitTests { isIncludeAndroidResources = true } }
}
// Kotlin 2.x makes `android.kotlinOptions { jvmTarget = ... }` a hard compile error (moved to
// compilerOptions); this is the direct replacement, one level up from the `android {}` block.
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":hop-sdk"))   // Bearer/LinkSink/HopRole contract + transport helpers

    // quality-net-03 / cov/android-bearers: pure-JVM unit tests for the LAN wire codec + one-pipe-per-peer
    // dedup keep-rule (LanWire.kt / LanDedup are deliberately Android-free), PLUS Robolectric-backed
    // loopback-socket integration tests of the REAL LanBearer/LanLink (link up/bytes/down, dedup survivor,
    // dial+accept, restart) once android.util.Log is shadowed.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.7.0")
}

// quality-cov / cov/android-bearers: line-coverage report + 80% floor over the LAN bearer's OWN Kotlin.
// Pin the JaCoCo tool to 0.8.11 so it matches the class-file version Robolectric's instrumenting
// classloader emits; a skew otherwise reads every Robolectric-run class as 0%.
jacoco { toolVersion = "0.8.11" }

// The online JaCoCo agent (not AGP's offline enableUnitTestCoverage) so Robolectric-sandboxed production
// classes are still recorded. includeNoLocationClasses is the documented fix for the version-skew 0%.
tasks.withType<Test>().configureEach {
    extensions.configure(JacocoTaskExtension::class) {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

// DOCUMENTED DENOMINATOR (mirrors the driver's uniffi/** exclusion + KeystoreSecret rationale): the
// device-bound NSD glue is excluded. NsdManager registration/discovery/resolve callbacks are delivered
// by the Android platform daemon; Robolectric's NSD shadow never fires them, and an emulator's mDNS is
// non-deterministic. The pure gating logic (skip-self, greater-id-dials, in-flight dedup), the real
// TCP dial, accept, framing, keepalive, dedup survivor + linkUp/linkBytes/linkDown are all exercised
// over loopback sockets; only the un-drivable listener callback classes are excluded here.
private val lanCoverageExclusions = listOf(
    "**/LanBearer\$startNsd\$*.class", // NSD Registration/DiscoveryListener callbacks (platform-delivered)
    "**/LanBearer\$pumpResolve\$*.class", // NSD ResolveListener callback (platform-delivered)
)

tasks.register<JacocoReport>("jacocoLanReport") {
    dependsOn("testDebugUnitTest")
    reports { xml.required.set(true); html.required.set(true) }
    classDirectories.setFrom(
        fileTree("${buildDir}/tmp/kotlin-classes/debug") { exclude(lanCoverageExclusions) },
    )
    sourceDirectories.setFrom(files("src/main/java"))
    executionData.setFrom(fileTree(buildDir) { include("jacoco/testDebugUnitTest.exec") })
}

tasks.register<JacocoCoverageVerification>("jacocoLanCoverageVerification") {
    dependsOn("testDebugUnitTest")
    classDirectories.setFrom(
        fileTree("${buildDir}/tmp/kotlin-classes/debug") { exclude(lanCoverageExclusions) },
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
