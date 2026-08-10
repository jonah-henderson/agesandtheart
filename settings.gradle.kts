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
// Ages and the Art.
include("common")
include("fabric")
include("neoforge")

// Ephemeris — the runtime-dimension library, a mod of its own that Ages and the Art depends on. Same
// build so the library is developed against its first consumer; separable by moving the folder out.
include("ephemeris:common")
include("ephemeris:fabric")
include("ephemeris:neoforge")