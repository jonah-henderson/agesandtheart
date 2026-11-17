plugins {
    id("multiloader-common")
    alias(libs.plugins.moddev)
}

neoForge {
    neoFormVersion = libs.versions.neoForm.get()
    val at = file("src/main/resources/META-INF/accesstransformer.cfg")
    if (at.exists()) {
        accessTransformers.from(at.absolutePath)
    }
}

configurations {
    create("commonJava") {
        isCanBeResolved = false
        isCanBeConsumed = true
    }
    create("commonKotlin") {
        isCanBeResolved = false
        isCanBeConsumed = true
    }
    create("commonResources") {
        isCanBeResolved = false
        isCanBeConsumed = true
    }
}

dependencies {
    // Ages and the Art, which this is a companion to. `compileOnly` because it ships as its own mod; a runtime
    // configuration here would compile a second copy of it into this one.
    compileOnly(project(":common")) {
        capabilities { requireCapability("co.voik.agesandtheart:agesandtheart") }
    }
    compileOnly(libs.mixin)
    // The config spec's classes, as Ages and the Art's `common` takes them (see there for the artifact and why).
    compileOnly(libs.forgeConfigApiPort.fabric) { isTransitive = false }
}

artifacts {
    add("commonJava", sourceSets.main.get().java.sourceDirectories.singleFile)
    add("commonKotlin", sourceSets.main.get().kotlin.sourceDirectories.filter { it.name == "kotlin" }.singleFile)
    add("commonResources", sourceSets.main.get().resources.sourceDirectories.singleFile)
}
