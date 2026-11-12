package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.age.aspect.Options
import co.voik.agesandtheart.age.aspect.Surface
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.levelgen.Heightmap

/**
 * The ground pocked with round pits, like pumice — `porous surface`. A pit opens at the top of a column and runs down
 * only while the holes do, so nothing is left hollow under an unbroken skin, and never more than [DEEPEST] blocks.
 * Cut before anything grows, so what grows stands in the pits; never where water or lava stands over the ground.
 */
class SurfacePits(private val holes: Perforation) {

    fun pitInto(chunk: ChunkAccess) {
        val origin = chunk.pos
        val cursor = BlockPos.MutableBlockPos()
        for (localX in 0..<CHUNK) for (localZ in 0..<CHUNK) {
            val x = origin.minBlockX + localX
            val z = origin.minBlockZ + localZ
            val ground = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, localX, localZ)
            val isUnderSomething = !chunk.getFluidState(cursor.set(x, ground + 1, z)).isEmpty
            if (isUnderSomething) continue
            for (y in ground downTo ground - DEEPEST + 1) {
                val state = chunk.getBlockState(cursor.set(x, y, z))
                val isPitHere = !state.isAir && !state.hasBlockEntity() && state.fluidState.isEmpty && holes.isHoleAt(x, y, z)
                if (!isPitHere) break
                chunk.setBlockState(cursor, Blocks.AIR.defaultBlockState())
            }
        }
    }

    companion object {
        /** The deepest a pit runs, in blocks: about as deep as it is wide. */
        const val DEEPEST = 5

        /** The scale that makes a pit four or five blocks across ([Perforation]). */
        private const val HOLE_SIZE = 6.0
        private const val HOLES_SALT = 0x5049_5453L
        private const val CHUNK = 16

        /** The pits [options] ask of the ground, or null where it is whole. */
        fun of(options: Options, salt: Long): SurfacePits? {
            val porosity = (options.steer(Surface.POROSITY, salt) ?: return null).coerceIn(0.0, 1.0)
            val holes = Perforation(salt xor HOLES_SALT, HOLE_SIZE, porosity)
            return if (holes.isNothing) null else SurfacePits(holes)
        }
    }
}
