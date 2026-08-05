package co.voik.agesandtheart.worldgen.field

import co.voik.agesandtheart.worldgen.NEEDS_LANDFORMS
import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.content.AgeContent
import com.mojang.serialization.JsonOps
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * The condition that keeps a cave floor bare, and the one thing about it that walking would never catch:
 * **a palette is serialised**, unlike the noise settings beside it, so this has to survive a round trip
 * with the shape it was asked about still attached. Lose the shape and every column answers about the
 * wrong terrain; lose the round trip and an Age cannot be written down at all.
 *
 * What is *not* checked here is that `MATERIAL_CONDITION` carries it, because it cannot be: the registry
 * throws before `Bootstrap.bootStrap()` and is frozen after it, and a loader's entrypoint is the only
 * thing that gets to run in between. [AgeContent] is where that list lives and neither loader may skip it.
 */
@Tags(NEEDS_REGISTRIES, NEEDS_LANDFORMS)
class NearTheSurfaceCheck : FunSpec({

    val ground = Slab(lowY = -64, highY = 64)

    test("the condition keeps the shape it was asked about across a round trip") {
        MinecraftRegistries.ensureStoodUp()

        val written = NearTheSurface.CODEC.codec()
            .codec()
            .encodeStart(JsonOps.INSTANCE, NearTheSurface(ground))
            .getOrThrow()
        val read = NearTheSurface.CODEC.codec().codec().parse(JsonOps.INSTANCE, written).getOrThrow()

        check(read == NearTheSurface(ground)) { "The condition lost its terrain in the round trip: $written" }
    }
})
