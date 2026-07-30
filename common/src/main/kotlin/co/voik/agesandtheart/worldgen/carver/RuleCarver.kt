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
 * Whether a block is cut away, as a pure function of where it is. The loop is the fiddly part — the mask,
 * the aquifer, the per-source-chunk seeding — so it is shared and the *judgement* is not.
 *
 * **Purity is load-bearing, not tidiness**: nothing an implementation does may touch a chunk, a registry
 * or a running server, which is what lets the terrain preview evaluate the exact object that generates.
 */
interface CarvingRule {
    /** The band this rule works in; rock outside it is untouched. */
    val fromY: Int
    val toY: Int

    /** Whether the block at this position is cut away. */
    fun cuts(worldX: Int, worldY: Int, worldZ: Int): Boolean
}

/**
 * Cuts rock away wherever a [CarvingRule] says so — the loop, not the judgement.
 *
 * **Because a rule is a pure function of position it cannot seam**, two chunks asking about one block
 * necessarily agreeing. So this runs *once* for the chunk being built rather than once per surrounding
 * source chunk, which is what keeps a per-block test affordable.
 */
class RuleCarver(
    codec: Codec<CarverConfiguration>,
    /** The judgement itself, held separately so the offline preview can evaluate the same object. */
    private val rule: CarvingRule,
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

        val lowestY = maxOf(context.minGenY + 1, rule.fromY)
        val highestY = minOf(context.minGenY + context.genDepth - 2, rule.toY)
        if (highestY <= lowestY) return false

        val position = BlockPos.MutableBlockPos()
        val below = BlockPos.MutableBlockPos()
        var cutAnything = false

        for (localX in 0..<CHUNK_WIDTH) {
            val worldX = chunkPos.minBlockX + localX
            for (localZ in 0..<CHUNK_WIDTH) {
                val worldZ = chunkPos.minBlockZ + localZ

                for (worldY in lowestY..highestY) {
                    if (carvingMask.get(localX, worldY, localZ)) continue
                    // Air first, and deliberately before the rule: a rule is noise, and most of a tall
                    // column is sky. Asking the cheap question first is what keeps a whole-world band
                    // affordable — see the band [Porosity] works over.
                    position.set(worldX, worldY, worldZ)
                    if (chunk.getBlockState(position).isAir) continue
                    if (!rule.cuts(worldX, worldY, worldZ)) continue

                    carvingMask.set(localX, worldY, localZ)
                    val reachedSurface = MutableBoolean(false)
                    val cut = carveBlock(
                        context, config, chunk, biomeAccessor, carvingMask, position, below, aquifer, reachedSurface,
                    )
                    cutAnything = cut || cutAnything
                }
            }
        }
        return cutAnything
    }

    companion object {
        private const val CHUNK_WIDTH = 16
    }
}
