package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.generation.AgeChunkGenerator
import co.voik.ephemeris.sky.LevelLooks
import co.voik.ephemeris.sky.SkyReading
import co.voik.ephemeris.sky.SkySpec
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.material.FlowingFluid
import java.util.Collections
import java.util.WeakHashMap

/**
 * The sea rising and falling with the moons — `tidal`, the flood cycle the paper tree's root lives by
 * (design §7.1.2).
 *
 * **A three-block band**: low, mid and high, with mid wherever the sea stands now — the written sea, or the
 * deluge's where one is raising it, so a rising sea still tides. **The moons set it**: the average altitude
 * of every moon in the sky, high tide with them well up and low with them well down, so a second moon
 * deepens, flattens or skews the cycle by where it is. With no moon there is no tide.
 *
 * **The level is a pure function of the clock**, with nothing stored, so a chunk nobody has seen catches up
 * on the one pass that reaches it.
 *
 * **The deluge's rise, with an ebb beside it**, and both at one position per column — the top of the column,
 * which is the one candidate the heightmap names. The flood: at the top of a column, inside the band and
 * below where the tide stands, flowing sea becomes a source and a source grows up to the tide. The ebb: a
 * source at the top of a column, inside the band and above the tide, is taken away, and vanilla's own flow
 * drains the rest. **Neither acts outside the band**, so a mountain lake, a cavern pool and water placed above
 * high water are never touched, and generation keeps the written sea.
 */
object Tide {

    /** Where the tide stands against mid, one of these three. */
    enum class Stage(val offset: Int) { LOW(-1), MID(0), HIGH(1) }

    /**
     * Ages where `/age weather tidal` has set a tide running, and what it is pinned at — null to follow the
     * moons. Not saved: a walk should not leave a tide behind.
     */
    private val forced: MutableMap<ServerLevel, Stage?> = Collections.synchronizedMap(WeakHashMap())

    fun force(level: ServerLevel, pinnedAt: Stage? = null) {
        forced[level] = pinnedAt
    }

    fun release(level: ServerLevel) {
        forced -= level
    }

    fun isForcedIn(level: ServerLevel): Boolean = level in forced

    /**
     * Where the tide stands in [level] now, or null where there is no tide: no sea to move, or no moon
     * to move it.
     */
    fun stageIn(level: ServerLevel): Stage? {
        forced[level]?.let { return it }
        val sky = LevelLooks.anywhere(level)?.sky ?: SkySpec.VANILLA
        return stageFor(SkyReading.of(sky, level.defaultClockTime))
    }

    /** The stage a sky sets, or null where it has no moon. Pure, so it can be asked without a world. */
    fun stageFor(reading: SkyReading): Stage? {
        val moons = reading.moons
        if (moons.isEmpty()) return null
        val pull = moons.map { it.altitudeDegrees }.average()
        return when {
            pull >= HIGH_WITH_THE_MOONS_ABOVE -> Stage.HIGH
            pull <= LOW_WITH_THE_MOONS_BELOW -> Stage.LOW
            else -> Stage.MID
        }
    }

    /** The sea a tide moves: the top block it fills at mid tide, and what it is made of. */
    data class Sea(val top: Int, val block: BlockState)

    /**
     * [generator]'s sea as it stands now, or null where it has none. **Ours where the Age laid its own sea**,
     * the deluge's level included; **vanilla's where its rock is vanilla's**, whose ocean is the noise
     * settings' default fluid up to their sea level, and which our sea fill therefore never names.
     */
    fun seaOf(generator: ChunkGenerator): Sea? {
        val age = generator as? AgeChunkGenerator ?: return null
        age.seaFill.surfaceY?.let { return Sea(it, age.seaFill.representative) }
        val fluid = age.generatorSettings().value().defaultFluid()
        if (fluid.fluidState.isEmpty) return null
        return Sea((age as ChunkGenerator).seaLevel - 1, fluid)
    }

    /** The y of the top of the sea at mid tide in [level], which is where it stands now. */
    fun midIn(level: ServerLevel): Int? = seaOf(level.chunkSource.generator)?.top

    /** The tide's block work, over every chunk anybody can see, a few chunks a tick. */
    fun flow(level: ServerLevel) {
        val standing = seaOf(level.chunkSource.generator) ?: return
        val mid = standing.top
        val sea = standing.block
        val stage = stageIn(level) ?: return
        val inView = Sampling.inViewNearestFirst(level)
        if (inView.isEmpty()) return
        val band = Band(low = mid + Stage.LOW.offset, standing = mid + stage.offset, high = mid + Stage.HIGH.offset)
        // As the deluge does it: where a pass starts is derived from the clock, so nothing holds a cursor.
        val from = ((level.gameTime * CHUNKS_A_TICK) % inView.size).toInt()
        for (step in 0..<CHUNKS_A_TICK) {
            val packed = inView[(from + step) % inView.size]
            val chunk = level.chunkSource.getChunkNow(ChunkPos.getX(packed), ChunkPos.getZ(packed)) ?: continue
            flowIn(level, chunk, band, sea)
        }
    }

    /** The band's three heights: its bottom, where the tide stands now, and its top. */
    private data class Band(val low: Int, val standing: Int, val high: Int)

    private fun flowIn(level: ServerLevel, chunk: LevelChunk, band: Band, sea: BlockState) {
        val seaFluid = sea.fluidState.type
        val flowingSea = (seaFluid as? FlowingFluid)?.flowing ?: seaFluid
        val cursor = BlockPos.MutableBlockPos()
        val originX = chunk.pos.minBlockX
        val originZ = chunk.pos.minBlockZ
        for (offsetX in 0..<CHUNK_WIDTH) {
            for (offsetZ in 0..<CHUNK_WIDTH) {
                val top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, offsetX, offsetZ) - 1
                if (top < band.low || top > band.high) continue
                val x = originX + offsetX
                val z = originZ + offsetZ
                cursor.set(x, top, z)
                val here = chunk.getBlockState(cursor).fluidState
                val isTheSea = here.type == seaFluid || here.type == flowingSea
                if (!isTheSea) continue
                when {
                    top > band.standing -> ebb(level, BlockPos(x, top, z), here.isSource)
                    top < band.standing -> flood(level, chunk, BlockPos(x, top, z), band.standing, sea, here.isSource)
                }
            }
        }
    }

    /** A source above the tide goes; a flow there is left for vanilla to drain once its sources have. */
    private fun ebb(level: ServerLevel, at: BlockPos, isSource: Boolean) {
        if (isSource) level.setBlockAndUpdate(at, Blocks.AIR.defaultBlockState())
    }

    /** The deluge's own rule, stopped at the tide: a flow becomes a source, a source rises to the line. */
    private fun flood(
        level: ServerLevel,
        chunk: LevelChunk,
        top: BlockPos,
        standing: Int,
        sea: BlockState,
        isSource: Boolean,
    ) {
        if (!isSource) {
            level.setBlockAndUpdate(top, sea)
            return
        }
        var risen = top
        while (risen.y < standing) {
            val above = risen.above()
            if (!chunk.getBlockState(above).canBeReplaced(sea.fluidState.type)) break
            level.setBlockAndUpdate(above, sea)
            risen = above
        }
    }

    /**
     * How far above or below the horizon the moons' average must be for high or low water. At thirty
     * degrees a single moon on vanilla's path spends about a third of its day at each of the three, which is
     * the even split the band is meant to have; the thresholds are for playtest to tune.
     */
    private const val HIGH_WITH_THE_MOONS_ABOVE = 30.0
    private const val LOW_WITH_THE_MOONS_BELOW = -30.0

    /**
     * The deluge's pace, and **not yet measured for the tide** (design §7.1.2 asks for a benchmark first):
     * the deluge moves a layer every few hours and a tide one every few minutes, both ways.
     */
    private const val CHUNKS_A_TICK = 32

    private const val CHUNK_WIDTH = 16
}
