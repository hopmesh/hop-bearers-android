plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-library`
}
// The Kotlin SDK (sh.hop) compiled as a plain JVM library so Android bearer modules can depend on it.
// Points at the SHARED source under sdk/wrappers/android (one source of truth — no copy). JNA is `api`
// so HopNode can load libhop.so at runtime; bearers only use the Bearer/LinkSink/HopRole contract.
sourceSets["main"].java.srcDir("../../../sdk/wrappers/android/src/main/kotlin")
kotlin { jvmToolchain(17) }
dependencies {
    // JNA is provided by the app (as the Android @aar, for UniFFI); compileOnly avoids a jar+aar clash.
    compileOnly("net.java.dev.jna:jna:5.19.1")
}
