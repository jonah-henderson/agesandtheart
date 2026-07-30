package co.voik.agesandtheart.worldgen.structure

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.age.aspect.Density
import com.mojang.serialization.Dynamic
import com.mojang.serialization.JsonOps
import net.minecraft.core.Holder
import net.minecraft.world.level.levelgen.structure.StructureSet
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement
import kotlin.math.roundToInt

/**
 * How often an Age builds a thing — [Density] applied to a structure set's *placement*.
 *
 * **This is the emphasis knob for structures** (Jonah, 2026-07-29), and it works where the obvious one does
 * not. A set's selection weights look like emphasis and are almost inert: vanilla rolls a weighted entry, tries
 * it, and on failure **removes it and re-rolls until one fits the biome** — so raising a desert village's weight
 * cannot do anything the biome had not already decided. Placement is where "more of them" actually lives.
 *
 * Two placement shapes, and both are honest density controls:
 *
 * - **[RandomSpreadStructurePlacement]** — 19 of vanilla's 20 sets. Sites sit on a grid of `spacing` chunks,
 *   jittered, no closer than `separation`. Fewer chunks per cell is more structures.
 * - **[ConcentricRingsStructurePlacement]** — strongholds alone. `count` is literally how many there are, so
 *   density needs no arithmetic at all.
 *
 * **Rebuilt through the codec, which is why this needs no access widener.** A faithful rebuild wants
 * `salt`, `frequency`, the frequency-reduction method and any exclusion zone, and all four accessors are
 * `protected` — three of vanilla's sets carry non-default ones (`pillager_outposts` has two exclusion zones,
 * `buried_treasures` and `mineshafts` each a legacy reduction method), so dropping them would quietly change
 * more than density. `StructurePlacement.CODEC` is public and round-trips every field, so editing the one
 * number in the serialised form preserves the rest by construction.
 */
object StructureDensity {
    /**
     * [set] rebuilt to occur as often as [density] asks — or [set] itself, untouched, at
     * [Density.ORDINARY].
     *
     * **A rebuilt set is a *direct* holder**, because no registry has heard of it and none can: registries
     * freeze at startup and an Age is written long afterwards. That is what
     * [co.voik.agesandtheart.worldgen.AgeChunkGenerator.createState] has to branch on, and the note there about
     * upgrading to forged registry references applies to every set this function returns.
     *
     * Returns [set] unchanged on any failure rather than dropping it: an Age that asked for more villages and
     * got the usual number is a disappointment, where an Age that lost its villages is a broken sentence.
     */
    fun applied(set: Holder<StructureSet>, density: Density): Holder<StructureSet> {
        if (density.isOrdinary) return set
        val thinned = rescaled(set.value().placement(), density) ?: return set
        return Holder.direct(StructureSet(set.value().structures(), thinned))
    }

    /** The same placement, occurring [density] times as often — or null where we cannot say how. */
    private fun rescaled(placement: StructurePlacement, density: Density): StructurePlacement? = when (placement) {
        // `count` *is* the number of them, so the occurrence scale applies directly and the two other
        // dimensions — how far out the rings start, how wide they spread — are left as vanilla tuned them.
        is ConcentricRingsStructurePlacement -> ConcentricRingsStructurePlacement(
            placement.distance(),
            placement.spread(),
            (placement.count() * density.occurrenceScale).roundToInt().coerceAtLeast(AT_LEAST_ONE),
            placement.preferredBiomes(),
        )
        is RandomSpreadStructurePlacement -> placement.spacedBy(spacingFor(placement, density))
        // A placement type some mod invented. We have no idea which of its numbers means "how often", and
        // guessing would be worse than declining — so the set stays exactly as its author tuned it.
        else -> null
    }

    /**
     * What [placement]'s spacing becomes at this rung, kept legal.
     *
     * Vanilla requires `separation < spacing`, and a rung dense enough to violate it would fail the codec on
     * the way back in, so the floor is one chunk above the separation rather than one chunk absolute.
     */
    private fun spacingFor(placement: RandomSpreadStructurePlacement, density: Density): Int {
        val asked = (placement.spacing() * density.spacingScale).roundToInt()
        return asked.coerceAtLeast(placement.separation() + 1)
    }

    /**
     * The same spread placement at a new [spacing], every other field carried across by the codec.
     *
     * The one narrow step out: encode, change one number, decode. It is worth preferring over the public
     * four-argument constructor because that one defaults `frequency` to 1, the reduction method to `DEFAULT`
     * and the exclusion zone to absent — which would silently un-tune the three sets that set them.
     */
    private fun RandomSpreadStructurePlacement.spacedBy(spacing: Int): StructurePlacement? {
        val written = StructurePlacement.CODEC.encodeStart(JsonOps.INSTANCE, this).result().orElse(null)
            ?: return complaint("would not encode")
        val rung = Dynamic(JsonOps.INSTANCE, JsonOps.INSTANCE.createInt(spacing))
        val edited = Dynamic(JsonOps.INSTANCE, written).set(SPACING_FIELD, rung)
        return StructurePlacement.CODEC.parse(edited).result().orElse(null) ?: complaint("would not read back")
    }

    private fun complaint(what: String): StructurePlacement? {
        Constants.LOG.warn("A structure placement {} while being rescaled; its density is left alone", what)
        return null
    }

    /** Vanilla's own field name in `RandomSpreadStructurePlacement.CODEC`. */
    private const val SPACING_FIELD = "spacing"

    /** A set that occurs nowhere at all is a set that should have been struck out instead. */
    private const val AT_LEAST_ONE = 1
}
