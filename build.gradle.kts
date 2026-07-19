plugins {
    // AGP 9.3 + Gradle 9.6.1 + Kotlin 2.4.10, aligned with apps/android/HopDemo (the same module dirs
    // compile under BOTH builds, so their toolchains move together; this bump is what unblocked
    // core-ktx 1.19.0, whose AAR metadata hard-requires AGP >= 9.1 and compileSdk >= 37).
    // below. Only apps/android/HopDemo moves to AGP 9.x (its com.android.application/library are bumped there).
    id("com.android.library") version "9.3.0" apply false
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.10" apply false
}
