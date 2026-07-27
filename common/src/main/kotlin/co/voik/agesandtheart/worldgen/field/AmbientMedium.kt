package co.voik.agesandtheart.worldgen.field

import com.mojang.datafixers.util.Either
import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState

/**
 * The background a shape sits in — orthogonal to the shape itself. Everything below [level] that the
 * shape leaves empty is filled with one of [blocks]; [VOID] fills nothing.
 *
 * **The substance is positional but the height is not**, and that asymmetry is the design's rather than
 * an implementation shortcut. Where a shape's sea belongs is the landform's to declare (§3.4), and when
 * several landforms disagree one of them simply wins — so an Age has exactly one waterline. What can
 * vary across it is *what the sea is made of*: an ocean of water meeting an ocean of lava along a line,
 * at the same level, with no barrier and no obsidian. Nothing in Minecraft does that, which is the point.
 */
data class AmbientMedium(val blocks: List<BlockState>, val level: Int, val map: RegionMap = RegionMap.whole()) {

    /** What fills the empty space at this column. */
    fun blockAt(worldX: Int, worldZ: Int): BlockState =
        blocks[map.memberAt(worldX, worldZ).coerceIn(blocks.indices)]

    /**
     * One substance standing for the medium where only one will do — vanilla's generation settings want
     * a single default fluid for the whole dimension, and there is nowhere to say "it depends".
     *
     * Only ever reaches things that do not care: the settings' default fluid feeds an inert router and a
     * disabled aquifer. Anything a player can see asks [blockAt] instead.
     */
    val representative: BlockState = blocks.firstOrNull() ?: Blocks.AIR.defaultBlockState()

    fun fillsAt(y: Int): Boolean = y < level && !representative.isAir

    /**
     * The highest block this medium fills, or `null` when it fills nothing — which is how [VOID] answers,
     * and the reason no caller has to know that its [level] is a sentinel rather than a height.
     */
    val surfaceY: Int? = if (representative.isAir) null else level - 1

    companion object {
        val CODEC: MapCodec<AmbientMedium> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                // A bare `block` is how this was written before a sea could be two substances, and how
                // it is still written whenever it is only one.
                Codec.either(BlockState.CODEC.listOf(), BlockState.CODEC)
                    .xmap(
                        { either -> either.map({ many -> many }, ::listOf) },
                        { many -> if (many.size == 1) Either.right(many.first()) else Either.left(many) },
                    )
                    .fieldOf("block").forGetter(AmbientMedium::blocks),
                Codec.INT.fieldOf("level").forGetter(AmbientMedium::level),
                RegionMap.MAP_CODEC.codec().optionalFieldOf("regions", RegionMap.whole())
                    .forGetter(AmbientMedium::map),
            ).apply(instance, ::AmbientMedium)
        }

        /** Empty space all the way down. */
        val VOID = AmbientMedium(listOf(Blocks.AIR.defaultBlockState()), Int.MIN_VALUE)

        /** A fluid sea filling everything below [level]. */
        fun sea(block: BlockState, level: Int) = AmbientMedium(listOf(block), level)

        /** Several substances at one level, each filling its own territory. */
        fun seas(blocks: List<BlockState>, level: Int, map: RegionMap) = AmbientMedium(blocks, level, map)
    }
}
