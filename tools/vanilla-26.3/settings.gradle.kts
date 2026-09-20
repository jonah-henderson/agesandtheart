/**
 * A standalone Gradle build, deliberately not part of the mod's.
 *
 * It exists to launch a **vanilla 26.3 client with the authoring tools in it**, so a structure can be
 * built while the mod itself is still being ported. Nothing here is shipped and nothing here is compiled:
 * there is no source set, only a run configuration.
 *
 * It is its own build rather than a module because it names a *different Minecraft version* from the one
 * the mod is being built against, and a single Gradle build resolves one of those.
 */
pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "vanilla-26-3"
