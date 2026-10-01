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
import net.minecraft.core.SectionPos
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
import net.minecraft.world.level.material.Fluids
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
 * **The rise puts back what generation laid, and runs on a little from it** ([rise]); **the ebb takes the
 * sea from the top of each column** above the tide, and from under the cover beside it ([ebb]). **Neither
 * acts outside the band**, so a mountain lake, a cavern pool and water placed above high water are never
 * touched, and generation keeps the written sea, which is high water.
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
                val here = chunk.getBlockState(cursor).fluidState
                val isTheSea = here.type == seaFluid || here.type == flowingSea
                if (isTheSea && top > band.standing) ebb(level, BlockPos(x, top, z), seaFluid)
            }
        }
        rise(level, chunk, band, sea)
    }

    /**
     * The sea back up to where the tide stands, **wherever generation laid it, and on from there** (Jonah,
     * 2026-09-30): each pass the sea runs **one block further** into the room beside it or over it, back over
     * everything generation filled with it, open or under cover, and on as far as [RUNS_ON_FOR] past that —
     * so a covered pool, an inlet a block wide and a trench a player digs on the shore all fill, the trench a
     * block at a time.
     *
     * What generation laid is asked of the generator ([GeneratedSea]) rather than stored, which is why a
     * pond a player makes is never taken for the sea. **Only beside the sea**, so a room a player sealed and
     * pumped dry in the shallows stays dry, and only where a cell would hold the water — ground or the sea
     * under it — so none is poured into a cave, where vanilla's flow takes it as it always did. Every cell is
     * decided before any is filled, so the sea runs one block a pass.
     */
    private fun rise(level: ServerLevel, chunk: LevelChunk, band: Band, sea: BlockState) {
        val generated = generatedSeaIn(level, band.high)
        val seaFluid = sea.fluidState.type
        fun isSeaAt(at: BlockPos): Boolean {
            if (!level.isLoaded(at)) return false
            val fluid = level.getFluidState(at)
            return fluid.isSource && fluid.type.isSame(seaFluid)
        }
        val filled = mutableListOf<Pair<BlockPos, BlockState>>()
        for (offsetX in 0..<CHUNK_WIDTH) {
            for (offsetZ in 0..<CHUNK_WIDTH) {
                val x = chunk.pos.minBlockX + offsetX
                val z = chunk.pos.minBlockZ + offsetZ
                for (y in band.low..band.standing) {
                    val at = BlockPos(x, y, z)
                    val state = chunk.getBlockState(at)
                    val becomes = withTheSeaIn(state, sea) ?: continue
                    val below = at.below()
                    val isOverTheSea = isSeaAt(below)
                    val wouldHoldIt = isOverTheSea || chunk.getBlockState(below).isFaceSturdy(chunk, below, Direction.UP)
                    val isBesideTheSea = isOverTheSea || Direction.Plane.HORIZONTAL.any { isSeaAt(at.relative(it)) }
                    if (!wouldHoldIt || !isBesideTheSea) continue
                    if (generated.isWithin(RUNS_ON_FOR, x, y, z)) filled += at to becomes
                }
            }
        }
        for ((at, state) in filled) level.setBlockAndUpdate(at, state)
    }

    /**
     * [state] with the sea in it, or null where the sea cannot come in or is there already: room becomes the
     * sea, a flow of it becomes the sea itself, and a dry block that could stand in water is waterlogged.
     */
    private fun withTheSeaIn(state: BlockState, sea: BlockState): BlockState? {
        val seaFluid = sea.fluidState.type
        val isAFlowOfIt = state.`is`(sea.block) && !state.fluidState.isSource
        if (state.isAir || isAFlowOfIt) return sea
        val couldStandInIt = state.getOptionalValue(BlockStateProperties.WATERLOGGED).map { !it }.orElse(false)
        val isWater = seaFluid.isSame(Fluids.WATER)
        return if (couldStandInIt && isWater) state.setValue(BlockStateProperties.WATERLOGGED, true) else null
    }

    /** What generation laid of the sea in [level]'s band under [high], kept for the chunks a tide works. */
    private fun generatedSeaIn(level: ServerLevel, high: Int): GeneratedSea {
        val known = generatedSea[level]
        if (known != null && known.high == high) return known
        return GeneratedSea(level, high).also { generatedSea[level] = it }
    }

    private val generatedSea: MutableMap<ServerLevel, GeneratedSea> = Collections.synchronizedMap(WeakHashMap())

    /**
     * Which cells of the band under [high] generation filled with the sea — open water standing on the
     * generated floor — asked of the generator's own height, a pure function, so nothing need be saved.
     *
     * **Asked a column at a time, and only for a cell about to fill near the shore** ([isOpenSea] answers the
     * rest): the generator answers by running a whole noise column, and asking it for every column of every
     * chunk in view held the server up for seconds a tick. Remembered in memory only, a byte a column for the [MOST_CHUNKS_REMEMBERED] chunks last
     * asked about, and rebuilt whole where high water moves, as a deluge moves it.
     */
    private class GeneratedSea(private val level: ServerLevel, val high: Int) {

        private val byChunk = object : LinkedHashMap<Long, ByteArray>(MOST_CHUNKS_REMEMBERED, LOAD, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean =
                size > MOST_CHUNKS_REMEMBERED
        }

        fun isSeaAt(x: Int, y: Int, z: Int): Boolean {
            val layer = high - y
            if (layer !in 0..<LAYERS) return false
            if (isOpenSea(x, z)) return true
            return layersAt(x, z) and (1 shl layer) != 0
        }

        /**
         * A column whose floor lies under the whole band now, read off the live heightmap — the open sea, which
         * generation laid, and which is nearly every cell a rising tide refills. Answered without the generator,
         * which is what keeps the open sea cheap: refilling it by asking cost seconds a pass.
         */
        private fun isOpenSea(x: Int, z: Int): Boolean {
            val at = BlockPos(x, high, z)
            if (!level.isLoaded(at)) return false
            return level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z) <= high - (LAYERS - 1)
        }

        /**
         * Whether generation laid the sea at height [y] within [reach] blocks of the column at [x], [z] —
         * looked for ring by ring outwards, since a cell beside the sea nearly always has it next door.
         */
        fun isWithin(reach: Int, x: Int, y: Int, z: Int): Boolean {
            if (isSeaAt(x, y, z)) return true
            for (ring in 1..reach) {
                for (along in -ring..ring) {
                    val onTheRing = isSeaAt(x + along, y, z - ring) || isSeaAt(x + along, y, z + ring) ||
                        isSeaAt(x - ring, y, z + along) || isSeaAt(x + ring, y, z + along)
                    if (onTheRing) return true
                }
            }
            return false
        }

        private fun layersAt(x: Int, z: Int): Int {
            val chunk = ChunkPos.pack(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z))
            val columns = byChunk.getOrPut(chunk) { ByteArray(CHUNK_WIDTH * CHUNK_WIDTH) { UNASKED } }
            val index = (x and CHUNK_MASK) * CHUNK_WIDTH + (z and CHUNK_MASK)
            if (columns[index] == UNASKED) columns[index] = askedOf(x, z)
            return columns[index].toInt()
        }

        private fun askedOf(x: Int, z: Int): Byte {
            val generator = level.chunkSource.generator
            val randomState = level.chunkSource.randomState()
            // Each the space over the top block of its kind: the floor, and whatever stood on it — so a hollow
            // generation kept dry under the waterline is not taken for the sea.
            val overTheFloor = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState)
            if (overTheFloor > high) return NONE_OF_THE_BAND
            val overTheTop = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, randomState)
            var layers = 0
            for (layer in 0..<LAYERS) {
                val y = high - layer
                if (y >= overTheFloor && y < overTheTop) layers = layers or (1 shl layer)
            }
            return layers.toByte()
        }

        private companion object {
            /** Every layer a band can reach, under high water: the widest reach either side of mid. */
            const val LAYERS = 2 * WIDEST_REACH + 1
            const val CHUNK_MASK = CHUNK_WIDTH - 1
            const val LOAD = 0.75f

            /** A column not asked about yet — never a set of layers, since [LAYERS] stays under eight. */
            const val UNASKED: Byte = -1

            const val NONE_OF_THE_BAND: Byte = 0
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

    /** How far the rising sea runs on past where generation laid it — as far as the ebb follows it. */
    private const val RUNS_ON_FOR = COVERED_REACH

    /** About what four players at a long view distance keep loaded, at a byte a column. */
    private const val MOST_CHUNKS_REMEMBERED = 16384

    /**
     * The furthest a tide reaches either side of mid, however hard its moons pull — **one block for now**
     * (Jonah, 2026-09-30), a three-block band, until playing it says a wider tide is not too hard to keep.
     */
    const val WIDEST_REACH = 1
}
