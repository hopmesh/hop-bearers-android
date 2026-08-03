plugins {
    // AGP 9.3 + Gradle 9.6.1 + Kotlin 2.4.10, aligned with apps/android/HopDemo (the same module dirs
    // compile under BOTH builds, so their toolchains move together; this bump is what unblocked
    // core-ktx 1.19.0, whose AAR metadata hard-requires AGP >= 9.1 and compileSdk >= 37).
    // below. Both builds are now on AGP 9.x and must be bumped together (they share module dirs).
    id("com.android.library") version "9.3.1" apply false
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.10" apply false
}

// The version every published bearer carries. THREE things read this one line, so they cannot disagree:
// tools/release/plan.py resolves the mirror's tag from it (build.gradle.kts is one of the manifests it
// parses), the mirror's release.yml asserts the pushed tag equals it, and the publications below stamp
// it into every POM. tools/version-align-guard.sh keeps it within major/minor of the Rust workspace
// anchor. Before this existed, hop-bearers-android had no release.yml at all, so plan.py skipped it and
// the Android bearers were mirrored but never published, while every Apple counterpart shipped.
version = "0.0.2"
group = "sh.hop"

// ---- Publishing convention for every bearer module -------------------------------------------------
//
// Each bearer publishes its OWN AAR to Maven Central as `sh.hop:hop-bearer-<transport>`, so a consumer
// pulls in only the transports it wants (the same "1 isolated lib per bearer" promise the READMEs make,
// finally backed by real artifacts).
//
// WHY `sh.hop` AND NOT `sh.hopme.bearers`: Maven Central verifies namespaces per groupId. `sh.hop` is
// already verified and already publishing (sdk/android ships `sh.hop:hop` there), so reusing it means
// these artifacts can ship without a second namespace verification. Kotlin package names
// (`sh.hopme.bearers.ble`) are a separate namespace and are unchanged. The READMEs previously advertised
// `sh.hopme.bearers:bearer-ble`, a coordinate nothing has ever published to; they are corrected here.
//
// WHY A CONVENTION AND NOT FOUR COPIED BLOCKS: it applies to every `bearer-*` module, so a NEW bearer is
// publishable the moment it is added to settings.gradle.kts, with nothing to remember. bearer-meshtastic
// (PR #382) is not on main yet and is covered by this the moment it lands.
//
// WHY THE POM IS BUILT BY HAND RATHER THAN `from(components["release"])`: these module dirs are compiled
// by BOTH gradle builds (bearers/android and apps/android/HopDemo) and depend on `project(":hop-sdk")`,
// an in-tree shim that recompiles sdk/android's shared source and is NOT itself published. Letting
// Gradle generate the POM would emit that internal project as a dependency coordinate no consumer can
// resolve. So the dependency list is DERIVED from each module's real `implementation` configuration and
// the shim is rewritten to the published `sh.hop:hop` AAR it stands in for. Derived, not hardcoded: add
// okhttp to a bearer and its POM gains okhttp automatically. This mirrors sdk/android, which also
// hand-authors its POM dependencies, but keeps them in sync with the build by construction.
subprojects {
    if (!name.startsWith("bearer-")) return@subprojects

    apply(plugin = "maven-publish")

    group = rootProject.group
    version = rootProject.version

    // bearer-ble -> hop-bearer-ble. The `hop-` prefix keeps the artifact self-describing inside a flat
    // Maven namespace shared with `sh.hop:hop`.
    val artifact = "hop-$name"
    val transport = name.removePrefix("bearer-")
    // Display name only. Acronyms would otherwise render as "Hop Ble bearer" on Maven Central.
    val transportLabel = when (transport) {
        "ble" -> "BLE"
        "lan" -> "LAN"
        else -> transport.replaceFirstChar(Char::uppercase)
    }

    // Everything below hangs off the plugin callback, NOT off `subprojects { afterEvaluate { ... } }`.
    // The ordering is load-bearing: a callback registered while the ROOT script evaluates is queued
    // before AGP registers its own (AGP is applied later, when the module script runs), and Gradle runs
    // afterEvaluate callbacks in registration order. Registered at root level, this code therefore ran
    // BEFORE AGP had created its variant tasks and software components, and failed with
    // "SoftwareComponent with name 'release' not found". Registering from inside the plugin callback
    // queues it after AGP's, so the `release` component exists by the time it is read.
    plugins.withId("com.android.library") {
        // A library module publishes nothing until a variant is selected. `release` is the only one
        // consumers should ever get, and AGP builds the sources jar alongside it.
        extensions.configure<com.android.build.api.dsl.LibraryExtension>("android") {
            publishing { singleVariant("release") { withSourcesJar() } }
        }

        // Publish the POM as the ONLY metadata. Gradle module metadata (.module) is generated from the
        // component's real configurations, so it carried `HopBearersAndroid:hop-sdk:unspecified` for the
        // in-tree shim, and Gradle consumers PREFER .module over the POM. That combination is the worst
        // case: a correct POM that every Android app silently ignores in favour of a coordinate that
        // cannot resolve. Caught by reading the generated .module rather than trusting the POM alone.
        tasks.withType<GenerateModuleMetadata>().configureEach { enabled = false }

        // Maven Central REJECTS a deployment that has no javadoc jar. These are Kotlin libraries whose
        // real reference is the source jar and the READMEs, so this carries the licence and the module
        // README rather than generated HTML, exactly as sdk/android's `docsJar` already does. Without it
        // the upload succeeds file by file and the deployment then fails validation at the very end.
        val javadocJar = tasks.register<Jar>("bearerJavadocJar") {
            archiveClassifier.set("javadoc")
            from(rootProject.file("README.md"), rootProject.file("LICENSE.md"))
        }

        afterEvaluate {
        extensions.configure<PublishingExtension>("publishing") {
            publications {
                create<MavenPublication>("bearer") {
                    groupId = rootProject.group.toString()
                    artifactId = artifact
                    version = rootProject.version.toString()
                    // AGP's `release` component: the AAR the build actually produced, plus its sources
                    // jar. Its GENERATED dependency list is discarded in withXml below, because it names
                    // the internal `:hop-sdk` shim; everything else about the component is what we want.
                    from(components["release"])
                    artifact(javadocJar)
                    pom {
                        name.set("Hop $transportLabel bearer for Android")
                        description.set(
                            "Hop mesh transport for Android over $transportLabel. Implements the Bearer / " +
                                "LinkSink contract from the Hop Android SDK; carries no protocol logic.",
                        )
                        url.set("https://github.com/hopmesh/hop-bearers-android")
                        licenses {
                            license {
                                name.set("Apache License, Version 2.0")
                                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                            }
                        }
                        scm {
                            connection.set("scm:git:https://github.com/hopmesh/hop-bearers-android.git")
                            developerConnection.set("scm:git:ssh://git@github.com/hopmesh/hop-bearers-android.git")
                            url.set("https://github.com/hopmesh/hop-bearers-android")
                            tag.set("v${rootProject.version}")
                        }
                        developers {
                            developer {
                                id.set("hopmesh")
                                name.set("Hop Mesh, LLC")
                                url.set("https://hopme.sh")
                            }
                        }
                        withXml {
                            val rootNode = asNode()
                            // Drop AGP's generated dependency list first. It resolves `project(":hop-sdk")`
                            // to the internal shim's coordinates, which no consumer can fetch. Replacing
                            // it (rather than appending) guarantees exactly ONE dependencies block.
                            rootNode.children()
                                .filterIsInstance<groovy.util.Node>()
                                .filter { (it.name() as? groovy.namespace.QName)?.localPart == "dependencies" || it.name() == "dependencies" }
                                .forEach { rootNode.remove(it) }
                            val dependencies = rootNode.appendNode("dependencies")
                            fun declare(group: String, artifactId: String, ver: String, type: String?) {
                                val node = dependencies.appendNode("dependency")
                                node.appendNode("groupId", group)
                                node.appendNode("artifactId", artifactId)
                                node.appendNode("version", ver)
                                if (type != null) node.appendNode("type", type)
                                node.appendNode("scope", "runtime")
                            }
                            // Derived from what the module actually compiles against, so the POM cannot
                            // drift from the build. The in-tree `:hop-sdk` shim is the local stand-in for
                            // the PUBLISHED Android SDK, so it is declared as that artifact; every other
                            // dependency (okhttp on the relay bearer, say) passes through untouched.
                            configurations.getByName("implementation").allDependencies.forEach { dep ->
                                if (dep is ProjectDependency) {
                                    if (dep.name == "hop-sdk") {
                                        declare("sh.hop", "hop", rootProject.version.toString(), "aar")
                                    }
                                    return@forEach
                                }
                                val depGroup = dep.group ?: return@forEach
                                val depVersion = dep.version ?: return@forEach
                                declare(depGroup, dep.name, depVersion, null)
                            }
                        }
                    }
                }
            }
            repositories {
                // Default to a local staging directory. The release workflow builds the Maven tree here,
                // then signs and uploads those exact files to Central, so nothing is signed or published
                // from a workstation (rules: deploys run in CI).
                maven {
                    name = "hop"
                    url = uri(
                        providers.gradleProperty("hopMavenRepository")
                            .orElse(layout.buildDirectory.dir("maven-repository").map { it.asFile.absolutePath })
                            .get(),
                    )
                }
            }
            }
        }
    }
}
