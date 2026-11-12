package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.age.aspect.Options
import co.voik.agesandtheart.age.aspect.Sea
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.levelgen.Heightmap

/**
 * Bubbles of air suspended in the sea — `porous sea`, as Riven's water holds them. Round, about four or five blocks
 * across, and kept clear of the sea's floor and its surface so each one hangs in the water.
 *
 * **Tenuous on purpose** (Jonah): plain air, cut while the chunk is generated, when nothing schedules the water around
 * it to move, so a bubble stands until something disturbs the water beside it — a block placed or broken, water let
 * in — and then floods, each block of water that flows waking the next. Cut before anything grows.
 */
class SeaBubbles(private val holes: Perforation) {

    fun bubbleInto(chunk: ChunkAccess) {
        val origin = chunk.pos
        val cursor = BlockPos.MutableBlockPos()
        val air = Blocks.AIR.defaultBlockState()
        for (localX in 0..<CHUNK) for (localZ in 0..<CHUNK) {
            val x = origin.minBlockX + localX
            val z = origin.minBlockZ + localZ
            val floor = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, localX, localZ)
            val surface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, localX, localZ)
            for (y in floor + CLEAR_OF_THE_FLOOR..surface - CLEAR_OF_THE_SURFACE) {
                val state = chunk.getBlockState(cursor.set(x, y, z))
                val isTheSea = state.block is LiquidBlock && state.fluidState.isSource
                if (isTheSea && holes.isHoleAt(x, y, z)) chunk.setBlockState(cursor, air)
            }
        }
    }

    companion object {
        /** Blocks of water kept between a bubble and the sea floor, and between it and the surface. */
        private const val CLEAR_OF_THE_FLOOR = 2
        private const val CLEAR_OF_THE_SURFACE = 2

        /** The scale that makes a bubble four or five blocks across ([Perforation]). */
        private const val HOLE_SIZE = 6.0

        /** How much of the void a porosity asks for the sea keeps as bubbles: an eighth of the water at the most. */
        private const val SHARE_OF_THE_VOID = 0.36

        private const val HOLES_SALT = 0x4255_4242_4CL
        private const val CHUNK = 16

        /** The bubbles [options] ask of the sea, or null where it holds none. */
        fun of(options: Options, salt: Long): SeaBubbles? {
            val porosity = (options.steer(Sea.POROSITY, salt) ?: return null).coerceIn(0.0, 1.0) * SHARE_OF_THE_VOID
            val holes = Perforation(salt xor HOLES_SALT, HOLE_SIZE, porosity)
            return if (holes.isNothing) null else SeaBubbles(holes)
        }
    }
}
