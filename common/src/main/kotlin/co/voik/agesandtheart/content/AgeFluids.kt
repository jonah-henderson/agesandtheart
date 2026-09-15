package co.voik.agesandtheart.content

import co.voik.agesandtheart.age.word.InkTier
import co.voik.agesandtheart.location
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.level.material.PushReaction

/**
 * The inks, as identity rather than as objects.
 *
 * **No `Fluid` instances here, and that is forced.** NeoForge declares `getFluidType()` abstract on
 * `Fluid`, and `FluidType` is a NeoForge class — so no fluid subclass can be written in common at all.
 * What common *can* own is everything that is not the object: the ids, the colours, the capacities, how
 * each fluid flows, and the properties of its block and bucket. Each loader builds its own pair against
 * these and hands them back through [InkFluids].
 */
object AgeFluids {
    /** The four figures `FlowingFluid` asks a fluid for, which each loader hands to its own fluid class. */
    data class Flow(
        val slopeFindDistance: Int,
        val dropOff: Int,
        val tickDelay: Int,
        val explosionResistance: Float,
    )

    /** The ids and appearance of one ink. */
    data class InkIdentity(
        val still: Identifier,
        val flowing: Identifier,
        val block: Identifier,
        val bucket: Identifier,
        /** Tint applied to the fluid texture, ARGB. */
        val tint: Int,
        val flow: Flow,
    )

    /** Thick: ink pools rather than running for the horizon. */
    private val INK_FLOW = Flow(
        // Water is 4. Ink barely finds its way downhill.
        slopeFindDistance = 2,
        // Water is 1 in the overworld. Ink loses depth fast, so a spill stays a puddle.
        dropOff = 2,
        // Water is 5.
        tickDelay = 12,
        explosionResistance = 100.0f,
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
        flow = INK_FLOW,
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
     * Water's own figures, against the inks' deliberately thick ones: deep water is water that happens to be
     * under a great deal of itself, so a diver should find it moves and spreads exactly as water does.
     */
    private val WATERS_FLOW = Flow(slopeFindDistance = 4, dropOff = 1, tickDelay = 5, explosionResistance = 100.0f)

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
        flow = WATERS_FLOW,
    )

    /** A fluid that is not an ink: the pair of ids and the block, with no bucket and no tint of ours. */
    data class FluidIdentity(
        val still: Identifier,
        val flowing: Identifier,
        val block: Identifier,
        val flow: Flow,
    )

    /**
     * The properties of one of our fluids' blocks: **water's own**, so it reads as water to everything that
     * asks a block a question, but for the map colour — black rather than `MapColor.WATER`, since neither an
     * abyss nor a pool of ink should draw on a map as the sea.
     */
    fun liquidBlockProperties(block: Identifier): BlockBehaviour.Properties =
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, block))
            .mapColor(MapColor.COLOR_BLACK)
            .replaceable()
            .noCollision()
            .strength(LIQUID_BLOCK_STRENGTH)
            .pushReaction(PushReaction.DESTROY)
            .noLootTable()
            .liquid()
            .sound(SoundType.EMPTY)

    /** The properties of an ink's bucket. */
    fun bucketProperties(bucket: Identifier): Item.Properties =
        Item.Properties()
            .setId(ResourceKey.create(Registries.ITEM, bucket))
            .craftRemainder(Items.BUCKET)
            .stacksTo(1)

    /** Water's, so a pool behaves like any other liquid to a shovel. */
    private const val LIQUID_BLOCK_STRENGTH = 100.0f
}
