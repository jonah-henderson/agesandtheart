plugins {
    id("multiloader-loader")
    alias(libs.plugins.loom)
}

val modId = project.property("modId") as String

dependencies {
    minecraft(libs.minecraft)
    implementation(libs.fabricLoader)
    implementation(libs.fabricApi)
    implementation(libs.flk)

    // Ages and the Art, compiled against and run beside. Its jar carries Ephemeris and the config port with it.
    implementation(project(":fabric"))

    // The config port's API, for the companion's own server settings; Ages and the Art's jar brings the port itself.
    compileOnly(libs.forgeConfigApiPort.fabric)
}

loom {
    // Loom would otherwise read Ages and the Art's widener off its unprocessed `fabric.mod.json`, where the name is
    // still a template. Its widener is the loader's to apply at runtime, as any dependency mod's is.
    enableTransitiveAccessWideners = false
    val aw = project(":imaging:common").file("src/main/resources/$modId.accesswidener")
    if (aw.exists()) {
        accessWidenerPath.set(aw)
    }
    runs {
        named("client") {
            client()
            displayName = "Fabric Client (Imaging)"
            runDirectory = layout.projectDirectory.dir("runs/client")
        }
        named("server") {
            server()
            displayName = "Fabric Server (Imaging)"
            runDirectory = layout.projectDirectory.dir("runs/server")
        }
    }
}
