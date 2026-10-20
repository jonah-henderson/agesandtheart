package co.voik.agesandtheart.age.resolution

import co.voik.agesandtheart.age.Flaw
import co.voik.agesandtheart.age.Register
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.word.MaterialTable
import net.minecraft.world.level.levelgen.XoroshiroRandomSource

/**
 * One book being resolved: the world it is resolved against, what landed, the Age's draw, and the flaws found so
 * far. Each part of the resolver is written against it — catalogues, steering, populations, mintings.
 */
internal class Resolving(val world: WorldFacts, landings: List<Landing>, val draw: Long) {

    /** What landed, with every material asked for by tag settled to one block for this Age. */
    val said: List<Landing> = landings.map(::materialised)

    val flaws = mutableListOf<Flaw>()

    /** What lands on [aspect]. */
    fun speakingTo(aspect: Aspect): List<Landing> = said.filter { it.aspect == aspect }

    /**
     * Firmest first: choosing and settling before barring before leaning, then aimed before bare, then the likelier,
     * then the higher bar — and where all of that ties, the seed, never the order the pages were laid in.
     */
    fun ranked(aspect: Aspect, landings: List<Landing>): List<Landing> = landings.sortedWith(
        compareByDescending<Landing> { it.firmness }
            .thenByDescending { it.aimed }
            .thenByDescending { it.chance }
            .thenByDescending { it.highestBar }
            .thenBy { tieBreak(aspect, it) },
    )

    /** A stable, unpredictable ordering key — how the seed arbitrates between equally firm landings. */
    fun tieBreak(aspect: Aspect, landing: Landing): Long =
        XoroshiroRandomSource(draw xor (aspect.ordinal * ASPECT_STRIDE) xor (landing.word.hashCode().toLong() * WORD_MIXER))
            .nextLong()

    fun flaw(register: Register, landings: List<Landing>, aspect: Aspect?, tags: List<String> = emptyList()) =
        Flaw(register, landings.map(Landing::word).distinct(), aspect, tags)

    /** [landing] with each material it asks for by tag — `"stone": "#frozen"` — drawn to one block for this Age. */
    private fun materialised(landing: Landing): Landing {
        val asksByTag = landing.effects.any { it is Effect.Sets && MaterialTable.asksByTag(it.parameter.name, it.value) }
        if (!asksByTag) return landing
        val drawn = landing.effects.mapNotNull { effect ->
            if (effect !is Effect.Sets || !MaterialTable.asksByTag(effect.parameter.name, effect.value)) return@mapNotNull effect
            val seed = draw xor landing.word.hashCode().toLong() xor effect.parameter.name.hashCode().toLong() xor MATERIAL_SALT
            world.drawnMaterial(effect.value, effect.parameter.name, seed)?.let { effect.copy(value = it) }
        }
        return landing.copy(effects = drawn)
    }

    companion object {
        // Arbitrary large odds, only ever needed to decorrelate one draw from another.
        const val ASPECT_STRIDE = 0x1F3B_5D79L
        const val TERRITORY_STRIDE = 0x4C9E_1A2BL
        const val COMPANY_SALT = 0x600D_C0A1L
        const val STRICTNESS_SALT = 0x5721_C7L
        const val WORD_MIXER = -0x61c8_8646_80b5_83ebL
        private const val MATERIAL_SALT = 0x3A7E_41A1L
    }
}
