import org.gradle.internal.extensions.stdlib.capitalized

plugins {
    id("multiloader-loader")
    alias(libs.plugins.moddev)
}

val modId: String by project

neoForge {
    version = libs.versions.neoforge
    // Automatically enable neoforge AccessTransformers if the file exists
    val at = project(":common").file("src/main/resources/META-INF/accesstransformer.cfg")
    if (at.exists()) {
        accessTransformers.from(at.absolutePath)
    }
    parchment {
        minecraftVersion = libs.versions.parchmentMC
        mappingsVersion = libs.versions.parchment
    }
    runs {
        configureEach {
            systemProperty("neoforge.enabledGameTestNamespaces", modId)
            ideName = "NeoForge ${name.capitalized()} (${project.path})" // Unify the run config names with fabric
        }
        register("client") {
            client()
        }
        register("data") {
            data()
        }
        register("server") {
            server()
        }
    }
    mods {
        register(modId) {
            sourceSet(sourceSets.main.get())
        }
    }
}

sourceSets.main.get().resources { srcDir("src/generated/resources") }

dependencies {
    implementation(libs.kff)
    // No runtime-dimension backend on NeoForge yet — see NeoForgeAgeBackend (unsupported stub).

    // The Art's parser runtime, declared three times because NeoForge needs all three.
    //  - `implementation` for the compile classpath.
    //  - `jarJar` nests it in the shipped jar. A version RANGE, not a pin: jar-in-jar has to pick a single
    //    copy when several mods bundle the same library, and a pin makes that unresolvable.
    //  - `additionalRuntimeClasspath` because on 1.21.8 and below NeoForge loads a nested artifact in a run
    //    only if it is a mod or declares FMLModType. Fixed upstream in 1.21.9, which Fantasy pins us out of.
    implementation(libs.antlrRuntime)
    additionalRuntimeClasspath(libs.antlrRuntime)
    jarJar(implementation("org.antlr:antlr4-runtime") {
        version {
            strictly("[4.13,5.0)")
            prefer(libs.versions.antlr.get())
        }
    })
}