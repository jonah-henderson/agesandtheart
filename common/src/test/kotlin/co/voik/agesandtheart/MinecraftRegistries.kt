package co.voik.agesandtheart

import net.minecraft.SharedConstants
import net.minecraft.core.HolderLookup
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.data.registries.VanillaRegistries
import net.minecraft.network.chat.Component
import net.minecraft.server.Bootstrap
import net.minecraft.server.packs.PackLocationInfo
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.PathPackResources
import net.minecraft.server.packs.repository.PackSource
import net.minecraft.server.packs.resources.MultiPackResourceManager
import net.minecraft.server.packs.resources.ResourceManager
import java.nio.file.Path
import java.util.Optional
import kotlin.io.path.isDirectory

/**
 * Marks a spec that needs Minecraft's registries standing up — the only slow thing in the suite, a few
 * seconds paid once per JVM. `./gradlew :common:test -Pfast` skips them.
 *
 * **Written as `@Tags(NEEDS_REGISTRIES)` on the class, never `tags(…)` in the spec body.** Kotest
 * *constructs* a spec to discover its tests, so anything the constructor touches is paid before any
 * filter applies — the annotation is read off the class, so a filtered-out spec is never built. Fixtures
 * inside a spec should be `by lazy` for the same reason.
 */
const val NEEDS_REGISTRIES = "NeedsRegistries"

/**
 * Minecraft's registries, stood up once per JVM.
 *
 * **Not just a call to `Bootstrap.bootStrap()`**, which sets its own done-flag *before* doing the work: if
 * it throws — most easily by not finding `en_us.json` on the classpath — every later call returns early
 * and reports success over half-built registries. A test then looks like it ran and checked nothing, which
 * is what the assertion below exists to catch.
 */
object MinecraftRegistries {

    /**
     * Synchronised by `lazy`, which matters: specs run concurrently, so several may arrive here at once
     * and exactly one must do the work.
     */
    private val bootstrapped: Unit by lazy {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
        check(BuiltInRegistries.BLOCK.keySet().isNotEmpty()) {
            "the block registry is empty after bootstrap — Minecraft's runtime classpath is missing from " +
                "the test source set, and every registry-dependent check below would have passed on nothing"
        }
    }

    /** Call from a spec before touching anything that reads a registry. */
    fun ensureStoodUp() = bootstrapped

    /** The shipped `art/spawning.json`, for the checks about what a written creature arrives as. */
    val spawning: co.voik.agesandtheart.age.aspect.Spawning by lazy {
        ensureStoodUp()
        co.voik.agesandtheart.age.word.Vocabulary.load(shippedData()).spawning
    }

    /**
     * Vanilla's **worldgen** registries — biomes, placed features, structure sets — built offline.
     *
     * `Bootstrap.bootStrap()` stands up the built-in registries only, so a corpus loaded without this has
     * §8's materials and creatures and **none of the other three populations**: no biome, no feature, no
     * structure set. That was true of every offline check until 2026-08-12, which is roughly eleven hundred
     * of the twelve hundred words in the corpus going unexercised — and it is why `in <biome>` could refuse
     * every biome in the game without a check noticing.
     *
     * Vanilla's own data, so it does not carry the mod's own biomes or structure sets. Those still want a
     * server. What it buys is that anything derived from *vanilla* content is now reachable in the
     * seventeen-second suite rather than the four-minute one.
     */
    val worldgen: HolderLookup.Provider by lazy {
        ensureStoodUp()
        VanillaRegistries.createLookup().also {
            check(it.lookupOrThrow(Registries.BIOME).listElements().findAny().isPresent) {
                "the biome registry is empty, so every derived-population check would pass on nothing"
            }
        }
    }

    /**
     * The mod's own data as a resource manager — the same class the server reads packs through, over the
     * source tree rather than a built jar so this runs in a second and needs no build.
     */
    fun shippedData(): ResourceManager {
        ensureStoodUp()
        val root = listOf(Path.of("src/main/resources"), Path.of("common/src/main/resources"))
            .firstOrNull { it.isDirectory() }
            ?: error("Cannot find the mod's resources from ${Path.of("").toAbsolutePath()}")
        val where = PackLocationInfo(
            "agesandtheart",
            Component.literal("Ages and the Art"),
            PackSource.BUILT_IN,
            Optional.empty(),
        )
        return MultiPackResourceManager(PackType.SERVER_DATA, listOf(PathPackResources(where, root)))
    }
}
