package co.voik.agesandtheart

import net.minecraft.SharedConstants
import net.minecraft.core.registries.BuiltInRegistries
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
