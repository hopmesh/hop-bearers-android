pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "HopBearersAndroid"
// The Kotlin SDK (sh.hop) as a JVM lib + one isolated module per Android bearer (mirrors bearers/apple).
include(":hop-sdk", ":bearer-ble", ":bearer-lan", ":bearer-wifidirect", ":bearer-relay")
