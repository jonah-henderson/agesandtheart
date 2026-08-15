package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.AgeTemplate
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.registries.Registries
import net.minecraft.world.attribute.EnvironmentAttribute
import net.minecraft.world.level.biome.Biome
import kotlin.jvm.optionals.getOrNull
import kotlin.streams.asSequence

/**
 * **What [BorrowedAir] takes from a world must be the world's, never a place's.**
 *
 * The borrowed layer goes on *above* the biomes — that is where the seam is, on both the client and the
 * server — so anything a biome might also have said would be overruled by one flat answer for the whole
 * Age. That is the bug that flattened the nether's crimson, warped and soul-sand fog into a single grey,
 * twice, by two different routes. The list is the claim; this is what keeps it true.
 */
@Tags(NEEDS_REGISTRIES)
class BorrowedAirCheck : FunSpec({

    /** Every attribute any biome in the game answers for — the ones a place owns and a world may not. */
    fun answeredByAPlace(): Set<EnvironmentAttribute<*>> =
        MinecraftRegistries.worldgen.lookupOrThrow(Registries.BIOME)
            .listElements()
            .asSequence()
            .flatMap { biome: net.minecraft.core.Holder.Reference<Biome> -> biome.value().attributes.keySet() }
            .toSet()

    test("nothing borrowed from a world is something a biome answers for") {
        val places = answeredByAPlace()
        val clashing = (BorrowedAir.SEEN + BorrowedAir.PLAYED).filter { it in places }
        check(clashing.isEmpty()) {
            "$clashing would be laid over the biomes that set them, flattening every one to a single answer"
        }
    }

    /**
     * The control. If the reading above is taken over an empty registry it proves nothing, and a biome
     * registry that fails to load offline is exactly the sort of thing that passes silently.
     */
    test("biomes do answer for something, or the check above is vacuous") {
        check(answeredByAPlace().isNotEmpty()) { "no biome in the game sets any attribute at all" }
    }

    /**
     * **And there is something to borrow.** A list of attributes no template answers for would lay no
     * layer and read exactly like a working one — which is what the fog distances did before this existed:
     * `netherhills` had the overworld's reach because nothing anywhere said otherwise.
     */
    test("the nether lends an Age its own fog and its own light") {
        val types = MinecraftRegistries.worldgen.lookupOrThrow(Registries.DIMENSION_TYPE)
        val nether = types.get(AgeTemplate.INFERNAL.dimensionType).getOrNull()?.value()?.attributes
        check(nether != null) { "the nether's own dimension type is not there to borrow from" }

        val lent = BorrowedAir.SEEN.filter { nether.contains(it) }
        check(lent.size >= EXPECTED_AT_LEAST) {
            "the nether lends only $lent, so an infernal Age keeps the overworld's reach for the rest"
        }
    }
})

/** Its fog's two distances and the colour of its light, at the very least — the walked complaint. */
private const val EXPECTED_AT_LEAST = 3
