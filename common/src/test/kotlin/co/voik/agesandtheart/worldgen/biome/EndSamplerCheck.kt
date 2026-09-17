package co.voik.agesandtheart.worldgen.biome

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.worldgen.field.Slab
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.registries.Registries
import net.minecraft.util.KeyDispatchDataCodec
import net.minecraft.world.level.biome.Climate
import net.minecraft.world.level.biome.TheEndBiomeSource
import net.minecraft.world.level.levelgen.DensityFunction

/**
 * **What the End is handed to choose its biomes with**, which has to be the sampler it was given.
 *
 * The End picks by distance from the centre and one reading of erosion, so there is no table here to bend
 * — and Fabric's biome API hangs its own End overrides off a seed that a `Climate.Sampler` carries only
 * when a `RandomState` made it. A sampler built in [AgeBiomeSource] has none, and the failure is a hard
 * crash out of chunk generation rather than a wrong biome: `MultiNoiseSampler doesn't have a seed set`
 * (Jonah, 2026-08-15, walked, on an End Age with hills under it).
 *
 * It went unseen because an Age that bends nothing hands the sampler on anyway, so every dark void Age up
 * to that point took the safe path by accident. **Naming a landform is what starts the wrapping**, which
 * is why every Age here is grounded — an ungrounded one would pass whatever this checked.
 *
 * The crash is Fabric's and cannot be reached offline, so what is checked instead is the cause, which is
 * ours: an End that reads the erosion it was given cannot have been handed a sampler we built.
 */
@Tags(NEEDS_REGISTRIES)
class EndSamplerCheck : FunSpec({

    /** Uniform on purpose: a grounded reading off *this* is constant, which is what gives it away. */
    val land = Slab(lowY = -64, highY = 64)

    fun endAge() = AgeBiomeSource(
        TheEndBiomeSource.create(MinecraftRegistries.worldgen.lookupOrThrow(Registries.BIOME)),
    )

    /** Grounded the way [co.voik.agesandtheart.age.AgeGeneration] grounds an Age with a shape of its own. */
    fun groundedEndAge() = endAge().groundedIn(land).suitedTo(Grounding(land, waterline = SEA_LEVEL))

    test("the erosion an End Age is handed is the erosion it reads") {
        val grounded = groundedEndAge()

        val worn = grounded.getNoiseBiome(FAR_OUT, ABOVE_GROUND, FAR_OUT, samplerReading { _, _ -> WORN_FLAT })
        val standing = grounded.getNoiseBiome(FAR_OUT, ABOVE_GROUND, FAR_OUT, samplerReading { _, _ -> STANDING })

        check(worn != standing) {
            "the End answered $worn for both of two erosions that name different biomes, so it is reading " +
                "a sampler of ours rather than the one it was given — which on Fabric is a crash, not a " +
                "wrong biome"
        }
    }

    test("so an End Age under a landform grows what the same End grows without one") {
        val plain = endAge()
        val grounded = groundedEndAge()

        val asIs = FAR_OUT_POINTS.map { (x, z) -> plain.getNoiseBiome(x, ABOVE_GROUND, z, samplerReading(::byPlace)) }
        val grown = FAR_OUT_POINTS.map { (x, z) -> grounded.getNoiseBiome(x, ABOVE_GROUND, z, samplerReading(::byPlace)) }

        // The control, and it is not ceremony: two sources that each answered one biome everywhere would
        // agree for a reason that has nothing to do with which sampler either of them read.
        check(asIs.toSet().size > 1) {
            "every point sampled came out ${asIs.first()}, so nothing here would notice a sampler swap"
        }
        val disagreed = asIs.indices.count { asIs[it] != grown[it] }
        check(disagreed == 0) {
            "a landform moved the End's biomes at $disagreed of ${FAR_OUT_POINTS.size} points, so something " +
                "is bending a world that picks by a rule of its own"
        }
    }
})

/** Far enough from the centre that the End stops answering `the_end` and starts reading erosion. */
private const val FAR_OUT = 500

/** A spread of them, at a stride that is not the End's chunk grid. */
private val FAR_OUT_POINTS = (1..8).flatMap { alongX -> (1..8).map { alongZ -> alongX * 373 to alongZ * 419 } }

private const val ABOVE_GROUND = 16
private const val SEA_LEVEL = 0

/** Two readings either side of `TheEndBiomeSource`'s own thresholds, so the two name different biomes. */
private const val WORN_FLAT = 0.5
private const val STANDING = -0.5

/** Every band the End divides erosion into, so a spread of points comes back as a spread of biomes. */
private val BANDS = listOf(0.5, 0.0, -0.15, -0.5)

/** A reading that varies with where it is asked, which is what a constant one cannot be mistaken for. */
private fun byPlace(blockX: Int, blockZ: Int): Double = BANDS[(blockX / 16 + blockZ / 16).mod(BANDS.size)]

/** A sampler that answers flatly on every axis but erosion, which is the only one the End reads. */
private fun samplerReading(erosion: (blockX: Int, blockZ: Int) -> Double): Climate.Sampler {
    val flat = Reading { _, _ -> 0.0 }
    return Climate.Sampler(flat, flat, flat, Reading(erosion), flat, flat, emptyList())
}

/** One axis, answered from the place it is asked about and nothing else. */
private class Reading(private val at: (blockX: Int, blockZ: Int) -> Double) : DensityFunction.SimpleFunction {
    override fun compute(context: DensityFunction.FunctionContext): Double = at(context.blockX(), context.blockZ())

    override fun minValue(): Double = -1.0
    override fun maxValue(): Double = 1.0

    override fun codec(): KeyDispatchDataCodec<out DensityFunction> =
        error("a reading belongs to one check and is never written down")
}
