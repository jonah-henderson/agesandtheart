package co.voik.agesandtheart.worldgen.carver

import com.mojang.serialization.Codec
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.util.RandomSource
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.chunk.CarvingMask
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.levelgen.Aquifer
import net.minecraft.world.level.levelgen.carver.CarverConfiguration
import net.minecraft.world.level.levelgen.carver.CarvingContext
import net.minecraft.world.level.levelgen.carver.WorldCarver
import org.apache.commons.lang3.mutable.MutableBoolean

/**
 * Wind erosion that leaves spires, approximated by the mechanism that actually shapes rock rather than
 * by simulating air.
 *
 * Erosion does not carve spires — **differential** erosion does. A hoodoo is soft rock that happened to
 * be capped by something harder; its neighbours had no such shield and went. So this asks of the *rock*,
 * not of the shape: how well does the stone here hold out against the wind? Where it holds, a spire is
 * left standing its full height. Where it does not, the column goes entirely.
 *
 * **That the decision is made per column, and barely varies with height, is the whole trick.** A
 * threshold that slides smoothly up a column crosses it exactly once, and one crossing per column is a
 * heightmap — gentle slopes, never a vertical face. Keeping the judgement essentially two-dimensional and
 * extruding it is what produces sheer walls and standing pillars instead.
 *
 * Two touches of real aeolian behaviour give it character, deliberately kept *weak* so they modulate the
 * shape rather than govern it:
 * - **Abrasion bites low**, since wind-borne grains travel close to a surface. A spire is therefore
 *   pinched at the waist and broader at the cap — the pedestal profile of a mushroom rock.
 * - **Wind combs rock into ridges along its own direction.** Yardangs are streamlined *parallel* to the
 *   prevailing wind, so resistance is stretched [WIND_STRETCH]× down the wind axis: survivors come out as
 *   aligned fins and files of pillars rather than an even scatter.
 *
 * The judgement itself lives in [Weathering], which is pure and therefore previewable offline.
 *
 * **Being a pure function of position has a pleasant consequence:** it needs no neighbourhood and cannot
 * seam, since two chunks asking about one block must get the same answer. So it runs *once*, for the
 * chunk being built, rather than once per surrounding source chunk — which is what keeps a per-block test
 * affordable.
 */
class ErosionCarver(
    codec: Codec<CarverConfiguration>,
    /** The rule itself, held separately so the offline preview can evaluate the same object. */
    private val weathering: Weathering = Weathering.SPIRE,
) : WorldCarver<CarverConfiguration>(codec) {

    override fun isStartChunk(config: CarverConfiguration, random: RandomSource): Boolean =
        random.nextFloat() <= config.probability

    override fun carve(
        context: CarvingContext,
        config: CarverConfiguration,
        chunk: ChunkAccess,
        biomeAccessor: java.util.function.Function<BlockPos, Holder<Biome>>,
        random: RandomSource,
        aquifer: Aquifer,
        chunkPos: ChunkPos,
        carvingMask: CarvingMask,
    ): Boolean {
        // Position-pure, so every surrounding source chunk would recompute the same answer. Do the work
        // only when asked about the chunk actually being built.
        if (chunkPos != chunk.pos) return false

        val lowestY = maxOf(context.minGenY + 1, weathering.fromY)
        val highestY = minOf(context.minGenY + context.genDepth - 2, weathering.toY)
        if (highestY <= lowestY) return false

        val position = BlockPos.MutableBlockPos()
        val below = BlockPos.MutableBlockPos()
        var erodedAnything = false

        for (localX in 0..<CHUNK_WIDTH) {
            val worldX = chunkPos.minBlockX + localX
            for (localZ in 0..<CHUNK_WIDTH) {
                val worldZ = chunkPos.minBlockZ + localZ

                for (worldY in lowestY..highestY) {
                    if (!weathering.erodes(worldX, worldY, worldZ)) continue
                    if (carvingMask.get(localX, worldY, localZ)) continue

                    carvingMask.set(localX, worldY, localZ)
                    position.set(worldX, worldY, worldZ)
                    val reachedSurface = MutableBoolean(false)
                    val eroded = carveBlock(
                        context, config, chunk, biomeAccessor, carvingMask, position, below, aquifer, reachedSurface,
                    )
                    erodedAnything = eroded || erodedAnything
                }
            }
        }
        return erodedAnything
    }

    companion object {
        private const val CHUNK_WIDTH = 16
    }
}
