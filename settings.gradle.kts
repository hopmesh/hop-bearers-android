pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "HopBearersAndroid"
// The Kotlin SDK (sh.hop) as a JVM lib + one isolated module per Android bearer (mirrors bearers/apple).
include(":hop-sdk", ":bearer-ble", ":bearer-lan", ":bearer-relay")
// The driver lives at drivers/android/hop-driver (north-star), built as part of this gradle build.
//
// Conditional for the same reason the hop-sdk shim is: that path is OUTSIDE `bearers/android`, the
// subtree copybara exports to the hop-bearers-android mirror, so in the mirror it does not exist and an
// unconditional include fails settings evaluation before any task can run, which would make the mirror
// unbuildable and therefore unpublishable. In the monorepo the directory is present and the driver is
// built here exactly as before.
val driverDir = file("../../drivers/android/hop-driver")
if (driverDir.isDirectory) {
    include(":hop-driver")
    project(":hop-driver").projectDir = driverDir
}
