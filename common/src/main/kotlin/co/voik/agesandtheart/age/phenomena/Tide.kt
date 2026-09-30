package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.SkyBodies
import co.voik.agesandtheart.generation.AgeChunkGenerator
import co.voik.ephemeris.sky.LevelLooks
import co.voik.ephemeris.sky.SkyReading
import co.voik.ephemeris.sky.SkySpec
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.material.FlowingFluid
import net.minecraft.world.level.material.Fluid
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.ceil

/**
 * The sea rising and falling with the moons — `tidal`, the flood cycle the paper tree's root lives by
 * (design §7.1.2).
 *
 * **The moons raise it, and only a moon that pulls** (`SkyBodies.PULL`, which `tidal` sets): a moon nobody
 * wrote a pull for raises nothing, so an Age tides only where a book said so. The pulling moons' altitudes,
 * weighed by how hard each pulls, set how high it stands — high with them well up, low with them well
 * down — and their pull together sets how far it reaches either side of mid, held for now at one block
 * ([WIDEST_REACH]): **a three-block band**, low, mid and high.
 *
 * **High water is the sea as it stands** — the written sea, or the deluge's where one is raising it, so a
 * rising sea still tides — **and the tide only ever falls from it** (Jonah, 2026-09-30). A tide rising over
 * the sea the world was generated around poured over every low rim into the chasms and hollows beside it.
 *
 * **The level is a pure function of the clock**, with nothing stored, so a chunk nobody has seen catches up
 * on the one pass that reaches it.
 *
 * **The deluge's rise, with an ebb beside it**, and both at one position per column — the top of the column,
 * which is the one candidate the heightmap names. The flood: at the top of a column, inside the band and
 * below where the tide stands, flowing sea becomes a source and a source grows up to the tide. The ebb: a
 * source at the top of a column, inside the band and above the tide, is taken away, and vanilla's own flow
 * drains the rest. **Neither acts outside the band**, so a mountain lake, a cavern pool and water placed above
 * high water are never touched, and generation keeps the written sea, which is high water.
 */
object Tide {

    /** Where the tide stands against mid, one of these three. */
    enum class Stage(val offset: Int) { LOW(-1), MID(0), HIGH(1) }

    /**
     * Ages where `/age tide` has set a tide running, and what it is pinned at — null to follow the moons,
     * every one of them pulling. Not saved: a walk should not leave a tide behind.
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
     * The band each Age's tide was last worked at — set by [flow] while a tide runs, and gone the pass it
     * stops. Not saved, and needs no saving: the next pass sets it again from the clock.
     */
    private val working: MutableMap<ServerLevel, Holding> = Collections.synchronizedMap(WeakHashMap())

    /** The band a tide is working in [level], and the fluid of the sea it moves. */
    private data class Holding(val band: Band, val sea: Fluid)

    /**
     * Whether the tide is holding [fluid] back at [at] — the sea's own, in the open, inside the band and above
     * where the tide stands. There vanilla's infinite-water rule may not make a new source (`FlowingFluidMixin`),
     * or the sea beside each gap the ebb leaves turns the flow into it back into sea as fast as it drains. Water
     * still flows there as it always does; it only cannot become more sea. **In the open** leaves a cavern pool
     * at sea level under a hill alone; outside the band, below the tide, and in an Age with no tide, water is
     * vanilla's.
     */
    fun holdsBack(level: ServerLevel, at: BlockPos, fluid: Fluid): Boolean {
        val holding = working[level] ?: return false
        val isAboveTheTide = at.y > holding.band.standing && at.y <= holding.band.high
        if (!isAboveTheTide || !fluid.isSame(holding.sea)) return false
        val isInTheOpen = at.y >= level.getHeight(Heightmap.Types.MOTION_BLOCKING, at.x, at.z) - 1
        return isInTheOpen
    }

    /**
     * How hard each moon [composition] describes pulls the sea, nought to one, in the order the sky draws
     * them. Empty where the Age has no moon, and nought for a moon nothing gave a pull.
     */
    fun pullsIn(composition: AgeComposition, seed: Long): List<Double> {
        if (composition.optionsFor(Aspect.MOON).isTrue(SkyBodies.ABSENT)) return emptyList()
        return (0..<composition.membersIn(Aspect.MOON)).map { moon ->
            SkyBodies.pullOf(composition.optionsFor(Aspect.MOON, moon), seed)
        }
    }

    /** Whether any moon of [pulls] raises a tide at all. */
    fun isTidal(pulls: List<Double>): Boolean = pulls.any { it > NO_PULL }

    /**
     * How many blocks either side of mid a tide with [pulls] reaches — their pull together, at least one
     * and at most [WIDEST_REACH].
     */
    fun reachOf(pulls: List<Double>): Int =
        ceil(pulls.sum() * WIDEST_REACH).toInt().coerceIn(NARROWEST_REACH, WIDEST_REACH)

    /**
     * Where the tide stands in [level] now, or null where there is no tide: no moon to move it. [pulls] is
     * each moon's, or null for every moon pulling alike, as a tide forced by hand does.
     */
    fun stageIn(level: ServerLevel, pulls: List<Double>? = null): Stage? {
        forced[level]?.let { return it }
        val sky = LevelLooks.anywhere(level)?.sky ?: SkySpec.VANILLA
        return stageFor(SkyReading.of(sky, level.defaultClockTime), pulls)
    }

    /**
     * The stage a sky sets, or null where no moon in it pulls. [pulls] is each moon's, in the sky's order,
     * or null for every moon pulling alike. Pure, so it can be asked without a world.
     */
    fun stageFor(reading: SkyReading, pulls: List<Double>? = null): Stage? {
        val pulling = reading.moons.mapIndexedNotNull { at, moon ->
            val pull = if (pulls == null) EVERY_MOON_ALIKE else pulls.getOrNull(at) ?: NO_PULL
            if (pull > NO_PULL) moon.altitudeDegrees to pull else null
        }
        if (pulling.isEmpty()) return null
        val altitude = pulling.sumOf { (altitude, pull) -> altitude * pull } / pulling.sumOf { it.second }
        return when {
            altitude >= HIGH_WITH_THE_MOONS_ABOVE -> Stage.HIGH
            altitude <= LOW_WITH_THE_MOONS_BELOW -> Stage.LOW
            else -> Stage.MID
        }
    }

    /** The sea a tide moves: the top block it fills at high tide, and what it is made of. */
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

    /** The y of the top of the sea at mid tide in [level]: a reach under high water, which is the sea. */
    fun midIn(level: ServerLevel): Int? = midOf(level.chunkSource.generator)

    /**
     * The same, from [generator] — where a grove plants its trees. At the widest reach, which a tide's reach
     * never passes and, while [WIDEST_REACH] is one, always is.
     */
    fun midOf(generator: ChunkGenerator): Int? = seaOf(generator)?.top?.let { it - WIDEST_REACH }

    /**
     * The tide's block work, over every chunk anybody can see, a few chunks a tick — where some moon of
     * [pulls] pulls, or a tide was forced by hand.
     */
    fun flow(level: ServerLevel, pulls: List<Double>) {
        val inView = Sampling.inViewNearestFirst(level)
        if (inView.isEmpty()) return
        // As the deluge does it: where a pass starts is derived from the clock, so nothing holds a cursor.
        val from = ((level.gameTime * CHUNKS_A_TICK) % inView.size).toInt()
        flowOver(level, pulls, (0..<CHUNKS_A_TICK).map { step -> inView[(from + step) % inView.size] })
    }

    /**
     * One whole pass of the tide over every chunk within [radius] of [centre] — `/age tide pass`, so a check
     * can drive the tide where no player stands to see it.
     */
    fun passAround(level: ServerLevel, pulls: List<Double>, centre: ChunkPos, radius: Int) {
        val around = (-radius..radius).flatMap { dx ->
            (-radius..radius).map { dz -> ChunkPos.pack(centre.x + dx, centre.z + dz) }
        }
        flowOver(level, pulls, around)
    }

    private fun flowOver(level: ServerLevel, pulls: List<Double>, chunks: List<Long>) {
        val band = bandIn(level, pulls)
        val sea = seaOf(level.chunkSource.generator)?.block
        if (band == null || sea == null) {
            working -= level
            return
        }
        working[level] = Holding(band, sea.fluidState.type)
        for (packed in chunks) {
            val chunk = level.chunkSource.getChunkNow(ChunkPos.getX(packed), ChunkPos.getZ(packed)) ?: continue
            flowIn(level, chunk, band, sea)
        }
    }

    /** Where the tide in [level] is working now, or null where it has no tide: no pull, no sea, or no moon. */
    private fun bandIn(level: ServerLevel, pulls: List<Double>): Band? {
        val written = pulls.takeIf(::isTidal)
        if (written == null && !isForcedIn(level)) return null
        val high = seaOf(level.chunkSource.generator)?.top ?: return null
        val stage = stageIn(level, written) ?: return null
        val reach = written?.let(::reachOf) ?: WIDEST_REACH
        val mid = high - reach
        return Band(low = mid - reach, standing = mid + stage.offset * reach, high = high)
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
                // A chunk's `getHeight` is the top block itself, where a level's is the space over it: one
                // under that was the tide working a block low, flooding right and ebbing the block beneath.
                val top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, offsetX, offsetZ)
                if (top < band.low || top > band.high) continue
                val x = originX + offsetX
                val z = originZ + offsetZ
                cursor.set(x, top, z)
                val state = chunk.getBlockState(cursor)
                val here = state.fluidState
                val isTheSea = here.type == seaFluid || here.type == flowingSea
                if (!isTheSea) {
                    if (top <= band.standing) soak(level, BlockPos(x, top, z), state, seaFluid)
                    continue
                }
                when {
                    top > band.standing -> ebb(level, BlockPos(x, top, z), seaFluid)
                    top < band.standing -> flood(level, chunk, BlockPos(x, top, z), band.standing, sea, here.isSource)
                }
            }
        }
    }

    /**
     * The sea above the tide goes, **a source or a flow alike**, and with it **the sea it reached under cover**.
     *
     * Taking only the sources left the flow that ran back into each gap standing, and the next pass passed it
     * over, so the shore never drained; a flow taken pass after pass is gone once the sources that fed it are,
     * since none can be made again here ([holdsBack]).
     *
     * High water runs in under an overhang or a tree's crown, where a column's top is the cover rather than
     * the sea, so no column's top ever shows it — and a source left there spilled into the open for every
     * pass to mop up. So the ebb follows the sea sideways at this height into the covered places beside it,
     * [COVERED_REACH] blocks at most, and drains them too (Jonah: a covered pool that adjoins the ocean is part
     * of it). Only covered places are followed; open ones are drained by their own columns, so this costs
     * nothing along a coast with nothing over it.
     */
    private fun ebb(level: ServerLevel, at: BlockPos, sea: Fluid) {
        dryOut(level, at)
        drainUnderCover(level, at, sea)
    }

    /**
     * The sea taken out of [at]: water goes, and **a block standing in it is only dried** — a waterlogged
     * root or a player's slab is not the sea, and taking it with the water left a yema's roots in pieces.
     */
    private fun dryOut(level: ServerLevel, at: BlockPos) {
        val state = level.getBlockState(at)
        val isStandingInIt = state.getOptionalValue(BlockStateProperties.WATERLOGGED).orElse(false)
        val dried = if (isStandingInIt) state.setValue(BlockStateProperties.WATERLOGGED, false) else AIR
        level.setBlockAndUpdate(at, dried)
    }

    /** A dry block that could stand in water, at or under the tide with the sea beside it, waterlogged again. */
    private fun soak(level: ServerLevel, at: BlockPos, state: BlockState, sea: Fluid) {
        val isDryButCouldStandInIt = state.getOptionalValue(BlockStateProperties.WATERLOGGED).map { !it }.orElse(false)
        if (!isDryButCouldStandInIt) return
        fun isSeaBeside(side: Direction): Boolean {
            val beside = level.getFluidState(at.relative(side))
            return beside.isSource && beside.type.isSame(sea)
        }
        if (Direction.Plane.HORIZONTAL.none(::isSeaBeside)) return
        level.setBlockAndUpdate(at, state.setValue(BlockStateProperties.WATERLOGGED, true))
    }

    private fun drainUnderCover(level: ServerLevel, from: BlockPos, sea: Fluid) {
        fun isCoveredSea(at: BlockPos): Boolean {
            if (!level.isLoaded(at) || !level.getFluidState(at).type.isSame(sea)) return false
            return at.y < level.getHeight(Heightmap.Types.MOTION_BLOCKING, at.x, at.z) - 1
        }
        val seen = mutableSetOf(from)
        val frontier = ArrayDeque(listOf(from))
        while (frontier.isNotEmpty()) {
            val at = frontier.removeFirst()
            for (side in Direction.Plane.HORIZONTAL) {
                val next = at.relative(side)
                val isWithinReach = maxOf(Math.abs(next.x - from.x), Math.abs(next.z - from.z)) <= COVERED_REACH
                if (!isWithinReach || !seen.add(next) || !isCoveredSea(next)) continue
                dryOut(level, next)
                frontier.addLast(next)
            }
        }
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

    private val AIR: BlockState = Blocks.AIR.defaultBlockState()

    private const val NO_PULL = 0.0

    /** What every moon pulls where nothing wrote one each — a tide forced by hand, or a check. */
    private const val EVERY_MOON_ALIKE = 1.0

    private const val NARROWEST_REACH = 1

    /** How far under cover the ebb follows the sea from the open water beside it. */
    private const val COVERED_REACH = 8

    /**
     * The furthest a tide reaches either side of mid, however hard its moons pull — **one block for now**
     * (Jonah, 2026-09-30), a three-block band, until playing it says a wider tide is not too hard to keep.
     */
    const val WIDEST_REACH = 1
}
