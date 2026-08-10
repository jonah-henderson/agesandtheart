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
/**
 * Let the console be driven from a pipe.
 *
 * MDG's run tasks are `JavaExec`, which defaults `standardInput` to an empty stream — so a server started
 * this way ignores anything piped at it and can only be stopped by killing Gradle. Forwarding stdin is what
 * makes `printf '/age list\nstop\n' | ./gradlew :neoforge:runServer` behave the way the Fabric checks do,
 * and it is the only way to exercise a NeoForge dedicated server here at all: there is no NeoForge
 * equivalent of `:fabric:exportServerLaunch`, so `DrivenServer` and its RCON harness cannot reach this side.
 */
tasks.withType<JavaExec>().configureEach {
    standardInput = System.`in`
}
