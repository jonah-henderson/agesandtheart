package co.voik.agesandtheart.worldgen.field

import com.mojang.datafixers.util.Either
import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState

/**
 * A [co.voik.agesandtheart.age.aspect.Sea] once it has a height — the background a shape sits in, orthogonal
 * to the shape itself. Everything below [level] that the shape leaves empty is filled with one of [blocks];
 * [NONE] fills nothing.
 *
 * **The substance is positional but the height is not**, and that asymmetry is the design's rather than an
 * implementation shortcut. Where a shape's sea belongs is the terrain's to declare (§3.4), and when several
 * terrains disagree one of them simply wins — so an Age has exactly one waterline. What can vary across it is
 * *what the sea is made of*: an ocean of water meeting an ocean of lava along a line, at the same level, with
 * no barrier and no obsidian. Nothing in Minecraft does that, which is the point.
 *
 * Above [level] there is air and only air. An earlier design gave this an *atmosphere* — a block standing
 * where air normally stands — and it was dropped for building Ages with nowhere to stand; the plan's step 7
 * records why, including the parts of it that did work.
 *
 * **Was `AmbientMedium` until 2026-07-29**, and named for holding whatever the rock was not. Dropping the
 * atmosphere left it holding only the sea, so "ambient" stopped being true of it — vanilla's `default_fluid`
 * and `sea_level`, and the [map] that lets there be more than one of the former. Not called `DefaultFluid`
 * after them, deliberately: vanilla's key is singular and this is a list on purpose, so that name would name
 * it after the very limitation it exists to lift. [representative] is the one fluid vanilla can hold.
 */
data class SeaFill(val blocks: List<BlockState>, val level: Int, val map: RegionMap = RegionMap.whole()) {

    /** What fills the empty space at this column. */
    fun blockAt(worldX: Int, worldZ: Int): BlockState =
        blocks[map.memberAt(worldX, worldZ).coerceIn(blocks.indices)]

    /**
     * One substance standing for the sea where only one will do — vanilla's generation settings want a single
     * default fluid for the whole dimension, and there is nowhere to say "it depends".
     *
     * Only ever reaches things that do not care: the settings' default fluid feeds an inert router and a
     * disabled aquifer. Anything a player can see asks [blockAt] instead.
     */
    val representative: BlockState = blocks.firstOrNull() ?: Blocks.AIR.defaultBlockState()

    fun fillsAt(y: Int): Boolean = y < level && !representative.isAir

    /**
     * The highest block this sea fills, or `null` when it fills nothing — which is how [NONE] answers, and the
     * reason no caller has to know that its [level] is a sentinel rather than a height.
     */
    val surfaceY: Int? = if (representative.isAir) null else level - 1

    companion object {
        val CODEC: MapCodec<SeaFill> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                // A bare `block` is how this was written before a sea could be two substances, and how it is
                // still written whenever it is only one.
                Codec.either(BlockState.CODEC.listOf(), BlockState.CODEC)
                    .xmap(
                        { either -> either.map({ many -> many }, ::listOf) },
                        { many -> if (many.size == 1) Either.right(many.first()) else Either.left(many) },
                    )
                    .fieldOf("block").forGetter(SeaFill::blocks),
                Codec.INT.fieldOf("level").forGetter(SeaFill::level),
                RegionMap.MAP_CODEC.codec().optionalFieldOf("regions", RegionMap.whole())
                    .forGetter(SeaFill::map),
            ).apply(instance, ::SeaFill)
        }

        /** Empty space all the way down. */
        val NONE = SeaFill(listOf(Blocks.AIR.defaultBlockState()), Int.MIN_VALUE)

        /** A sea filling everything below [level]. */
        fun of(block: BlockState, level: Int) = SeaFill(listOf(block), level)

        /** Several substances at one level, each filling its own territory. */
        fun divided(blocks: List<BlockState>, level: Int, map: RegionMap) = SeaFill(blocks, level, map)
    }
}
