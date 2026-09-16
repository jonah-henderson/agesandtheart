package co.voik.agesandtheart.worldgen.structure

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.age.aspect.Rung
import com.mojang.serialization.Dynamic
import com.mojang.serialization.JsonOps
import net.minecraft.core.Holder
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.levelgen.structure.StructureSet
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * How often an Age builds a thing — [Density] applied to a structure set's *placement*.
 *
 * **Placement, not selection weights.** A set's weights look like emphasis and are almost inert: vanilla
 * rolls a weighted entry, tries it, and on failure removes it and re-rolls until one fits the biome — so
 * raising a desert village's weight cannot do what the biome had not already decided.
 *
 * Two placement shapes, both honest density controls: [RandomSpreadStructurePlacement] (19 of vanilla's 20
 * sets) where fewer chunks per cell is more structures, and [ConcentricRingsStructurePlacement]
 * (strongholds) whose `count` is literally how many there are.
 *
 * **Rebuilt through the codec, which is why this needs no access widener.** A faithful rebuild wants
 * `salt`, `frequency`, the reduction method and any exclusion zone, and all four accessors are protected —
 * three of vanilla's sets carry non-default ones, so dropping them would change more than density.
 * `StructurePlacement.CODEC` round-trips every field, so editing one number preserves the rest.
 */
object StructureDensity {
    /**
     * [set] rebuilt to occur as often as [density] asks, or [set] itself untouched at [Density.ORDINARY].
     *
     * **A rebuilt set is a *direct* holder**, no registry having heard of it — registries freeze at startup
     * and an Age is written long afterwards. That is what
     * [co.voik.agesandtheart.generation.AgeChunkGenerator.createState] branches on.
     *
     * Returns [set] unchanged on failure rather than dropping it: an Age that asked for more villages and
     * got the usual number is a disappointment, where one that lost its villages is a broken sentence.
     */
    fun applied(server: MinecraftServer, set: Holder<StructureSet>, density: Double): Holder<StructureSet> {
        if (Rung.isOrdinary(density)) return set
        val thinned = rescaled(server, set.value().placement(), density) ?: return set
        return Holder.direct(StructureSet(set.value().structures(), thinned))
    }

    /** The same placement, occurring [density] times as often — or null where we cannot say how. */
    private fun rescaled(
        server: MinecraftServer,
        placement: StructurePlacement,
        density: Double,
    ): StructurePlacement? = when (placement) {
        // `count` *is* the number of them, so the occurrence scale applies directly and the two other
        // dimensions — how far out the rings start, how wide they spread — are left as vanilla tuned them.
        is ConcentricRingsStructurePlacement -> ConcentricRingsStructurePlacement(
            placement.distance(),
            placement.spread(),
            (placement.count() * density).roundToInt().coerceAtLeast(AT_LEAST_ONE),
            placement.preferredBiomes(),
        )
        is RandomSpreadStructurePlacement -> placement.spacedBy(server, spacingFor(placement, density))
        // A placement type some mod invented. We have no idea which of its numbers means "how often", and
        // guessing would be worse than declining — so the set stays exactly as its author tuned it.
        else -> null
    }

    /**
     * What [placement]'s spacing becomes at this amount, kept legal.
     *
     * **Spacing is a distance and structures sit on a grid**, so the count goes as the square of it: asking
     * for four times as many means halving the spacing, not quartering it. Vanilla also requires
     * `separation < spacing`, and an amount dense enough to violate that fails the codec on the way back
     * in — so the floor is one chunk above the separation rather than one chunk absolute.
     */
    private fun spacingFor(placement: RandomSpreadStructurePlacement, density: Double): Int {
        val asked = (placement.spacing() / sqrt(density)).roundToInt()
        return asked.coerceAtLeast(placement.separation() + 1)
    }

    /**
     * The same spread placement at a new [spacing], every other field carried across by the codec —
     * encode, change one number, decode. Preferred over the public four-argument constructor, which
     * defaults `frequency`, the reduction method and the exclusion zone, silently un-tuning three sets.
     *
     * **It must be the registries' own ops and never plain `JsonOps`** (Jonah, 2026-08-08, crashed). A
     * placement may carry an *exclusion zone*, which holds a `Holder<StructureSet>`, which holds a
     * placement — and vanilla's sets exclude one another in a ring. `RegistryFileCodec` writes a holder as
     * an **id** only when the ops can show it a registry; handed a plain one it inlines the value instead,
     * so encoding a single placement walked that ring until the stack ran out. `foreboding age
     * minecraft:packed_ice landmass frozen atmosphere` is the book that found it.
     */
    private fun RandomSpreadStructurePlacement.spacedBy(server: MinecraftServer, spacing: Int): StructurePlacement? {
        val ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE)
        val written = StructurePlacement.CODEC.encodeStart(ops, this).result().orElse(null)
            ?: return complaint("would not encode")
        val rung = Dynamic(ops, ops.createInt(spacing))
        val edited = Dynamic(ops, written).set(SPACING_FIELD, rung)
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
