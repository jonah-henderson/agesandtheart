plugins {
    // The NON-REMAPPING Loom plugin, which is a different id from the old `fabric-loom`. Minecraft is
    // unobfuscated from 26.1, so there is nothing to remap and the plain configurations are the only ones.
    id("net.fabricmc.fabric-loom") version "1.17.17"
}

repositories {
    maven("https://maven.fabricmc.net/") { name = "Fabric" }
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:26.3")
    // NO MAPPINGS AT ALL — Mojang stopped obfuscating Java Edition at 26.1, so there are none published
    // and nothing for them to have mapped.
    implementation("net.fabricmc:fabric-loader:0.19.5")
    // Wanted by the tools rather than by us: worldgen-devtools is a Fabric mod and expects the API.
    implementation("net.fabricmc.fabric-api:fabric-api:0.161.0+26.3")
}

loom {
    runs {
        // A server is not wanted: this is for building something by hand and saving it. The client keeps
        // Loom's default `run/` directory, and `run/mods` is where the authoring tools land — see
        // `scripts/vanilla-26.3.sh`.
        remove(getByName("server"))
    }
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}
