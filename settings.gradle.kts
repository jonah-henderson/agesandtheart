enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

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
includeBuild("../ephemeris") {
    dependencySubstitution {
        substitute(module("co.voik.ephemeris:ephemeris-common-26.2")).using(project(":common"))
        substitute(module("co.voik.ephemeris:ephemeris-fabric-26.2")).using(project(":fabric"))
        substitute(module("co.voik.ephemeris:ephemeris-neoforge-26.2")).using(project(":neoforge"))
    }
}
// Ages and the Art.
include("common")
include("fabric")
include("neoforge")
