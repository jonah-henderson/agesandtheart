plugins {
    id("multiloader-loader")
    alias(libs.plugins.moddev)
}

val modId = project.property("modId") as String

neoForge {
    version = libs.versions.neoforge.get()
    val at = project(":imaging:common").file("src/main/resources/META-INF/accesstransformer.cfg")
    if (at.exists()) {
        accessTransformers.from(at.absolutePath)
    }
    mods {
        register(modId) {
            sourceSet(sourceSets.main.get())
        }
    }
}

dependencies {
    implementation(libs.kff)

    // Ages and the Art, compiled against and run beside.
    implementation(project(":neoforge"))
}
