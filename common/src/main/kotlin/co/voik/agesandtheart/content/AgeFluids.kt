package co.voik.agesandtheart.content

import co.voik.agesandtheart.age.word.InkTier
import co.voik.agesandtheart.location
import net.minecraft.resources.Identifier

/**
 * The inks, as identity rather than as objects.
 *
 * **No `Fluid` instances here, and that is forced.** NeoForge declares `getFluidType()` abstract on
 * `Fluid`, and `FluidType` is a NeoForge class — so no fluid subclass can be written in common at all.
 * What common *can* own is everything that is not the object: the ids, the colours, the capacities. Each
 * loader builds its own pair against these and hands them back through [InkFluids].
 */
object AgeFluids {
    /** The ids and appearance of one ink. */
    data class InkIdentity(
        val still: Identifier,
        val flowing: Identifier,
        val block: Identifier,
        val bucket: Identifier,
        /** Tint applied to the fluid texture, ARGB. */
        val tint: Int,
    )

    private fun ink(name: String, tint: Int) = InkIdentity(
        still = name.location(),
        flowing = "flowing_$name".location(),
        // **The block takes the fluid's own name, as vanilla's do**: `minecraft:water` is both a fluid
        // and a block. A `_block` suffix put the two in different registries under different names, and
        // the corpus is built from the *block* registry — so the word for our own ink was `ink_block`,
        // and `ink springs` (world model §8.1.2) named nothing.
        block = name.location(),
        bucket = "${name}_bucket".location(),
        tint = tint,
    )

    /**
     * Named for what they are rather than for the setting, like the script — a pack renaming "d'ni ink"
     * changes a lang entry, not an id.
     */
    val INKS: Map<InkTier, InkIdentity> = mapOf(
        InkTier.COMMON to ink("ink", 0xFF1A1A22.toInt()),
        InkTier.FINE to ink("fine_ink", 0xFF1B2A4A.toInt()),
        InkTier.MASTERWORK to ink("masterwork_ink", 0xFF3A1F52.toInt()),
    )

    /**
     * What one tank holds, **in buckets** rather than in units — the unit is the loader's, so a capacity
     * in absolute numbers could only be right on one of them.
     */
    const val TANK_CAPACITY_BUCKETS = 64L

    /**
     * The abyss, as identity — design §7.1.2's deep water.
     *
     * **Here rather than in `AgeContent` for the reason at the top of this file**: it is a fluid, and no
     * `Fluid` subclass can be written in common at all. What common owns is the ids and the rules
     * ([DeepWater]); each loader builds the pair and hands them back through `DeepWaterFluid`.
     *
     * **No bucket of its own, and that is a ruling rather than an omission.** Deep water taken out of the
     * deep is water — [DeepWater.DEEPEST_VANILLA_SEA] guarantees a bucket's worth can never be deep
     * anywhere you could pour it — so a `deep_water_bucket` would be a water bucket with a different
     * texture and a lie on it. `LiquidBlock.pickupBlock` hands back `getBucket()`, which is
     * `minecraft:water_bucket` here.
     */
    val DEEP_WATER = FluidIdentity(
        still = "deep_water".location(),
        flowing = "flowing_deep_water".location(),
        // The block takes the fluid's own name, as vanilla's do and as the inks learned to — see [ink].
        block = "deep_water".location(),
    )

    /** A fluid that is not an ink: the pair of ids and the block, with no bucket and no tint of ours. */
    data class FluidIdentity(
        val still: Identifier,
        val flowing: Identifier,
        val block: Identifier,
    )
}
