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
}
