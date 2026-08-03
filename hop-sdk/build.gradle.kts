plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-library`
}
// The Kotlin SDK (sh.hop) compiled as a plain JVM library so Android bearer modules can depend on it.
// It is a BUILD-TIME shim with no sources of its own; it never publishes (the bearers' POMs name the
// published `sh.hop:hop` AAR instead, see the publishing convention in the root build).
//
// SELF-ADAPTING SOURCE. In the monorepo it compiles the SHARED source under sdk/android (one source of
// truth, no copy). That path is OUTSIDE `bearers/android`, which is the whole subtree copybara exports
// to the hop-bearers-android mirror, so in the mirror the directory simply does not exist and the shim
// would silently compile zero classes, leaving every bearer to fail against an empty jar. Rather than
// teach copybara another transform, the shim detects which tree it is in: shared source when present,
// the published SDK artifact when not. One file, correct in both repos, nothing to keep in sync.
val sharedSdkSource = file("../../../sdk/android/src/main/kotlin")
val inMonorepo = sharedSdkSource.isDirectory
if (inMonorepo) {
    sourceSets["main"].java.srcDir(sharedSdkSource)
}
kotlin { jvmToolchain(17) }
dependencies {
    // JNA is provided by the app (as the Android @aar, for UniFFI); compileOnly avoids a jar+aar clash.
    compileOnly("net.java.dev.jna:jna:5.19.1")
    if (!inMonorepo) {
        // Mirror build: re-expose the PUBLISHED Android SDK so `sh.hop` types resolve for compilation.
        // `api` so the bearers see the Bearer / LinkSink / HopRole contract transitively, exactly as
        // they do from the shared source in the monorepo.
        api("sh.hop:hop:${providers.gradleProperty("hopSdkVersion").get()}")
    }
}
