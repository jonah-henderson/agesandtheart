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
}