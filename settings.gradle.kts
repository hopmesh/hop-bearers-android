pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "HopBearersAndroid"
// The Kotlin SDK (sh.hop) as a JVM lib + one isolated module per Android bearer (mirrors bearers/apple).
include(":hop-sdk", ":bearer-ble", ":bearer-lan", ":bearer-wifidirect", ":bearer-relay")
// The driver lives at drivers/android/hop-driver (north-star), built as part of this gradle build.
include(":hop-driver")
project(":hop-driver").projectDir = file("../../drivers/android/hop-driver")
