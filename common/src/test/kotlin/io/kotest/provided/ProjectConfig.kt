package io.kotest.provided

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import io.kotest.core.annotation.Tags
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import io.kotest.core.listeners.BeforeSpecListener
import io.kotest.core.spec.Spec

/**
 * The suite's one piece of project configuration, found by Kotest at this name in every test task.
 *
 * It holds a listener and nothing about concurrency — see the note on the `test` task for why scheduling is
 * deliberately left alone.
 */
class ProjectConfig : AbstractProjectConfig() {
    override val extensions: List<Extension> = listOf(RegistriesForTaggedSpecs)
}

/**
 * Stands Minecraft's registries up before any spec tagged [NEEDS_REGISTRIES] runs, so **the tag is the
 * fixture**: a spec that says it needs them has them, whether it runs alone or after another that happened
 * to bootstrap first.
 *
 * Before a spec rather than when it is built, because Kotest constructs every spec to discover its tests and
 * a filtered-out one must pay nothing.
 */
object RegistriesForTaggedSpecs : BeforeSpecListener {
    override suspend fun beforeSpec(spec: Spec) {
        val tags = spec.javaClass.getAnnotation(Tags::class.java)?.values.orEmpty()
        if (NEEDS_REGISTRIES in tags) MinecraftRegistries.ensureStoodUp()
    }
}
