import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    jacoco
}
// bearer-relay - an INDEPENDENT Android bearer library depending only on the Kotlin SDK (sh.hop).
android {
    namespace = "sh.hopme.bearers.relay"
    // okhttp 5.4.0 requires compileSdk >= 36 and core-ktx 1.19.0 requires >= 37 from every consumer.
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // quality-cov: Robolectric shadows android.util.Log so the WebSocket callback bodies (which log on
    // every lifecycle edge) run under a plain JVM MockWebServer drive.
    testOptions { unitTests { isIncludeAndroidResources = true } }
}
// Kotlin 2.x makes `android.kotlinOptions { jvmTarget = ... }` a hard compile error (moved to
// compilerOptions); this is the direct replacement, one level up from the `android {}` block.
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":hop-sdk"))   // Bearer/LinkSink/HopRole contract + transport helpers
    implementation("com.squareup.okhttp3:okhttp:5.4.0")

    // quality-net-03 / cov/android-bearers: pure-JVM unit tests for the reconnect/backoff schedule
    // (RelayBackoff.kt is Android-free), PLUS a Robolectric + MockWebServer drive of the REAL
    // RelayBearer reconnect state machine (up/bytes/down + reconnect + 429 backoff).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.7.0")
    // OkHttp 5 split MockWebServer into a new `mockwebserver3` module + coordinate, but Square kept
    // publishing this legacy `com.squareup.okhttp3:mockwebserver` artifact as a deprecated bridge over the
    // OLD `okhttp3.mockwebserver` package (MockResponse/MockWebServer/WebSocket upgrade helpers unchanged),
    // specifically so callers don't have to migrate immediately. RelayBearerSocketTest still imports
    // okhttp3.mockwebserver.* unmodified.
    testImplementation("com.squareup.okhttp3:mockwebserver:5.4.0")
}

// quality-cov / cov/android-bearers: line-coverage report + 80% floor over the relay bearer's OWN Kotlin.
// Pin the JaCoCo tool to 0.8.11 (matches Robolectric's instrumented class-file version; a skew reads
// Robolectric-run classes as 0%).
jacoco { toolVersion = "0.8.11" }

// Online JaCoCo agent (not AGP offline) so Robolectric-sandboxed production classes are recorded.
tasks.withType<Test>().configureEach {
    extensions.configure(JacocoTaskExtension::class) {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

// DOCUMENTED DENOMINATOR: the ONLY excluded surface is the stable-link backoff-reset lambda, which the
// state machine schedules RELAY_STABLE_MS (20s) after a link opens. Firing it in a unit test would mean
// a real 20s wall-clock wait per run; the SCHEDULING of it is covered, and the reset arithmetic it
// performs is pinned independently by RelayBackoffTest (stableLinkResetsBaseAndStreak). Same rationale
// class as the driver's uniffi/** exclusion: a genuinely un-unit-testable seam, covered elsewhere.
private val relayCoverageExclusions = listOf(
    "**/RelayBearer\$dial\$1\$onOpen\$1\$1.class", // the 20s stable-reset lambda (scheduling is covered)
)

tasks.register<JacocoReport>("jacocoRelayReport") {
    dependsOn("testDebugUnitTest")
    reports { xml.required.set(true); html.required.set(true) }
    classDirectories.setFrom(
        fileTree("${buildDir}/tmp/kotlin-classes/debug") { exclude(relayCoverageExclusions) },
    )
    sourceDirectories.setFrom(files("src/main/java"))
    executionData.setFrom(fileTree(buildDir) { include("jacoco/testDebugUnitTest.exec") })
}

tasks.register<JacocoCoverageVerification>("jacocoRelayCoverageVerification") {
    dependsOn("testDebugUnitTest")
    classDirectories.setFrom(
        fileTree("${buildDir}/tmp/kotlin-classes/debug") { exclude(relayCoverageExclusions) },
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
