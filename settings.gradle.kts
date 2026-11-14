// This should match the folder name of the project, or else IDEA may complain (see https://youtrack.jetbrains.com/issue/IDEA-317606)
rootProject.name = "AgesAndTheArt"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        exclusiveContent {
            forRepository {
                maven {
                    name = "Fabric"
                    url = uri("https://maven.fabricmc.net")
                }
            }
            filter {
                // Regex so subgroups (e.g. net.fabricmc.unpick, pulled by newer Loom) are covered too.
                includeGroupByRegex("net\\.fabricmc.*")
                includeGroup("fabric-loom")
            }
        }
    }
}

dependencyResolutionManagement {
    versionCatalogs {
        register("libs") {
            from(files("libs.versions.toml"))
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

/**
 * Ephemeris's checkout: the nearest folder named `ephemeris` beside this checkout or beside a folder above it, so it is
 * found from `codebase/` and from `worktrees/<name>/` alike, and a release's own copy beside its work tree comes first.
 */
val ephemerisCheckout: File = generateSequence(settingsDir.parentFile) { it.parentFile }
    .map { it.resolve("ephemeris") }
    .firstOrNull { it.resolve("settings.gradle.kts").exists() }
    ?: error("No Ephemeris checkout named `ephemeris` beside $settingsDir or any folder above it")

/**
 * Ephemeris, the runtime-dimension library this mod is built on, as a **composite build**.
 *
 * Its own project next door rather than a subtree here, because that is what it is: a library with its own
 * version, its own checks and its own release. Including the build rather than consuming a published jar
 * keeps one edit-compile-run loop across both — Gradle rebuilds it when it changes, and neither project
 * needs the other to build alone.
 *
 * **The substitutions have to be spelled out.** Gradle registers automatic ones from each included
 * project's `group:name`, which here is `co.voik.ephemeris:common` — not the artifact coordinates the
 * catalog names — so without these the dependency is looked for in a repository and is simply not found.
 */
includeBuild(ephemerisCheckout) {
    dependencySubstitution {
        substitute(module("co.voik.ephemeris:ephemeris-common-26.3")).using(project(":common"))
        substitute(module("co.voik.ephemeris:ephemeris-fabric-26.3")).using(project(":fabric"))
        substitute(module("co.voik.ephemeris:ephemeris-neoforge-26.3")).using(project(":neoforge"))
    }
}

/**
 * Warns when [ephemerisCheckout] is not at the release the catalog names, since the composite build uses whatever is
 * checked out there while a release ships the published jar. Only a warning: building against unreleased
 * Ephemeris is the normal loop.
 */
fun warnWhereEphemerisDrifts() {
    val catalog = file("libs.versions.toml").readText()
    fun catalogVersion(name: String) = Regex("""(?m)^$name\s*=\s*"([^"]+)"""").find(catalog)?.groupValues?.get(1)
    val ephemerisVersion = catalogVersion("ephemeris") ?: return
    val minecraftVersion = catalogVersion("minecraft") ?: return
    val releasedTag = "v$ephemerisVersion-mc$minecraftVersion"
    if (!ephemerisCheckout.resolve(".git").exists()) return
    val described = providers.exec {
        workingDir = ephemerisCheckout
        commandLine("git", "describe", "--tags", "--long", "--dirty", "--match", "v*-mc$minecraftVersion")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
    if (described.isEmpty()) return
    val isTheReleasedTag = described.startsWith("$releasedTag-0-g") && !described.endsWith("-dirty")
    if (!isTheReleasedTag) {
        logger.warn("Ephemeris in $ephemerisCheckout is $described, not the catalog's $releasedTag: this build is not what a release ships")
    }
}
warnWhereEphemerisDrifts()
// Ages and the Art.
include("common")
include("fabric")
include("neoforge")
