import org.gradle.internal.extensions.stdlib.capitalized

plugins {
    id("multiloader-loader")
    alias(libs.plugins.moddev)
}

val modId = project.property("modId") as String

neoForge {
    version = libs.versions.neoforge.get()
    // Automatically enable neoforge AccessTransformers if the file exists
    val at = project(":common").file("src/main/resources/META-INF/accesstransformer.cfg")
    if (at.exists()) {
        accessTransformers.from(at.absolutePath)
    }
    val projectPath = project.path
    runs {
        configureEach {
            systemProperty("neoforge.enabledGameTestNamespaces", modId)
            ideName = "NeoForge ${name.capitalized()} ($projectPath)" // Unify the run config names with fabric
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

    // The Art's parser runtime, declared twice because NeoForge needs both.
    //  - `implementation` for the compile classpath.
    //  - `jarJar` nests it in the shipped jar. A version RANGE, not a pin: jar-in-jar has to pick a single
    //    copy when several mods bundle the same library, and a pin makes that unresolvable.
    //
    // It used to need a third, `additionalRuntimeClasspath`, because NeoForge would only load a nested
    // artifact in a run if it were a mod or declared FMLModType. That comment said it was fixed upstream in
    // 1.21.9 and that Fantasy pinned us out of reaching the fix — both true, and both now behind us.
    implementation(libs.antlrRuntime)
    jarJar(implementation("org.antlr:antlr4-runtime") {
        version {
            strictly("[4.13,5.0)")
            prefer(libs.versions.antlr.get())
        }
    })
}