plugins {
    // AGP stays on 8.5.2 here (no Dependabot bump requested for this directory) - it is the documented
    // minimum AGP supported by Kotlin Gradle Plugin 2.4.0, so it's compatible as-is with the Kotlin bump
    // below. Only android/HopDemo moves to AGP 9.x (its com.android.application/library are bumped there).
    id("com.android.library") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.4.0" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.0" apply false
}
