package co.voik.agesandtheart.client

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.age.aspect.Biomes
import co.voik.agesandtheart.content.PalmBeach
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.tags.FluidTags
import net.minecraft.util.Mth
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3

/**
 * **Waves breaking on a palm beach** — `notes/palm-beach-design.md`, "Surf". On the client alone: nothing is
 * sent, nothing is saved, and nothing runs on the server.
 *
 * **The shore** is still water open to the sky with white sand level beside it, in a palm beach — the waves
 * are the biome's and nobody else's (Jonah, 2026-09-30), so a beach built at home out of white sand stays
 * calm, and the reason to build the holiday house in the Age is the sea. A tide moves the shore and this
 * follows it, since the shore is found by looking rather than remembered.
 *
 * **Found by a rolling scan** of the columns around the player, a slice a tick, so a sweep of the whole
 * neighbourhood takes a fraction of a second and the cost of any one tick is a few hundred heightmap reads.
 * The sweep is gathered into the next map and swapped in whole, so a half-finished sweep is never read.
 *
 * **The waves** keep a clock per stretch of shore, a [STRETCH]-block cell: a period of six to nine seconds and
 * a phase, both drawn from a hash of the cell and read off game time. So a stretch breaks on the same beat
 * however recently its chunk arrived, the next stretch breaks at another moment, and the whole beach never
 * breaks at once. When a stretch's wave comes in, each of its shore blocks puts out foam a block or two out
 * that rides up the sand and back ([SurfParticles.Foam]). The sound is one loop for the whole shore.
 */
object Surf {

    /** One block of shore: the water at the line, and which way the sand is from it. */
    private data class Shore(val water: BlockPos, val landward: Direction)

    private var current: Map<Long, List<Shore>> = emptyMap()
    private var gathering = HashMap<Long, MutableList<Shore>>()
    private var sweptTo = 0
    private var sweepingAround: BlockPos = BlockPos.ZERO

    /** The level the shore was found in — another level's shore is not shore here. */
    private var sweptIn: ClientLevel? = null

    private val PALM_BEACH = ResourceKey.create(Registries.BIOME, Biomes.PALM_BEACH_BIOME)

    fun tick(minecraft: Minecraft) {
        val level = minecraft.level ?: return
        val player = minecraft.player ?: return
        // An Age is the only place a palm beach can be, so nowhere else pays for the scan.
        if (level.dimension().identifier().namespace != Constants.MOD_ID) {
            if (current.isNotEmpty()) forget()
            return
        }
        if (level !== sweptIn) {
            forget()
            sweptIn = level
        }
        sweep(level, player.blockPosition())
        breakWaves(level)
        keepTheSeaSounding(minecraft, player.position())
    }

    /** Leaving a level: what was found there is not shore anywhere else. */
    fun forget() {
        loop?.toward = null
        current = emptyMap()
        gathering = HashMap()
        sweptTo = 0
    }

    private fun sweep(level: ClientLevel, around: BlockPos) {
        if (sweptTo == 0) sweepingAround = around
        val stop = minOf(sweptTo + COLUMNS_A_TICK, COLUMNS)
        for (index in sweptTo..<stop) {
            val x = sweepingAround.x - REACH + index % SIDE
            val z = sweepingAround.z - REACH + index / SIDE
            shoreAt(level, x, z)?.let { shore -> gathering.getOrPut(stretchOf(shore.water)) { mutableListOf() } += shore }
        }
        sweptTo = stop
        if (sweptTo == COLUMNS) {
            current = gathering
            gathering = HashMap()
            sweptTo = 0
        }
    }

    /** The shore at this column, if it is one — the cheap questions first and the biome last. */
    private fun shoreAt(level: ClientLevel, x: Int, z: Int): Shore? {
        val water = BlockPos(x, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1, z)
        val fluid = level.getFluidState(water)
        if (!fluid.`is`(FluidTags.WATER) || !fluid.isSource) return null
        val landward = Direction.Plane.HORIZONTAL.firstOrNull { side ->
            val beside = water.relative(side)
            level.getBlockState(beside).`is`(PalmBeach.SURF_BREAKS_ON) && level.getBlockState(beside.above()).isAir
        } ?: return null
        if (!opensOntoWater(level, water, landward.opposite)) return null
        if (!level.getBiome(water).`is`(PALM_BEACH)) return null
        return Shore(water, landward)
    }

    /**
     * Whether open water runs out from this shore, [OPEN_WATER_NEAR] and [OPEN_WATER_FAR] blocks seaward —
     * so a pool inland, a few blocks across, makes no waves (Jonah, walked 2026-10-01). Two reads, and only
     * for a column that is already a shore.
     */
    private fun opensOntoWater(level: ClientLevel, water: BlockPos, seaward: Direction): Boolean =
        listOf(OPEN_WATER_NEAR, OPEN_WATER_FAR).all { out ->
            level.getFluidState(water.relative(seaward, out)).`is`(FluidTags.WATER)
        }

    private fun breakWaves(level: ClientLevel) {
        val now = level.gameTime
        for ((stretch, shore) in current) {
            val hash = Mth.murmurHash3Mixer(stretch.toInt() xor (stretch ushr Int.SIZE_BITS).toInt())
            val period = SHORTEST_WAVE + Math.floorMod(hash, LONGEST_WAVE - SHORTEST_WAVE)
            val phase = Math.floorMod(hash ushr PHASE_BITS, period)
            if ((now + phase) % period != 0L) continue
            shore.forEach { foamFrom(level, it) }
        }
    }

    /**
     * **The sea's sound: one loop, kept going while there is shore in reach** (Jonah, 2026-10-01: a long
     * recording, so never played over itself). It sits on the nearest shore and follows it as the player
     * walks, fades in when a shore comes into reach and out when the last one goes — see [SurfLoop].
     */
    private var loop: SurfLoop? = null

    private fun keepTheSeaSounding(minecraft: Minecraft, near: Vec3) {
        val nearest = current.values.asSequence().flatten().minByOrNull { it.water.distToCenterSqr(near) }
        val at = nearest?.let { Vec3.atCenterOf(it.water).add(0.0, HALF_A_BLOCK, 0.0) }
        // **Still playing as far as the engine knows**, not only as far as the loop does. Changing level stops
        // every sound without telling it, so a loop started in the last Age looked alive and was never
        // replaced, and the sea was silent on any beach reached by travelling (Jonah, walked 2026-10-01).
        val playing = loop?.takeIf { !it.isStopped && minecraft.soundManager.isActive(it) }
        if (at != null && playing == null) {
            loop = SurfLoop(at).also { minecraft.soundManager.play(it) }
        } else {
            playing?.toward = at
        }
    }

    /** A few flecks of foam riding in to this block of shore. */
    private fun foamFrom(level: ClientLevel, shore: Shore) {
        val random = level.random
        val landward = Vec3.atLowerCornerOf(shore.landward.unitVec3i)
        val across = Vec3.atLowerCornerOf(shore.landward.clockWise.unitVec3i)
        // The line itself: the edge of the water block on the sand's side.
        val line = Vec3.atBottomCenterOf(shore.water).add(landward.scale(HALF_A_BLOCK))
        val waterTop = shore.water.y + WATER_FACE
        val sandTop = shore.water.y + 1.0
        // A wall rather than a beach beyond the first block stops the run-up short.
        val beyond = shore.water.relative(shore.landward, 2)
        val runsOn = level.getBlockState(beyond).`is`(PalmBeach.SURF_BREAKS_ON) && level.getBlockState(beyond.above()).isAir
        repeat(FLECKS) {
            val start = line.add(across.scale(Mth.nextDouble(random, -HALF_A_BLOCK, HALF_A_BLOCK)))
            val furthest = if (runsOn) Mth.nextDouble(random, SHORTEST_RUN, LONGEST_RUN) else SHORT_RUN
            Minecraft.getInstance().particleEngine.add(
                SurfParticles.Foam(
                    level,
                    waterline = start,
                    landward = landward,
                    from = -Mth.nextDouble(random, NEAREST_SWELL, FURTHEST_SWELL),
                    furthest = furthest,
                    waterTop = waterTop,
                    sandTop = sandTop,
                    lifetime = Mth.nextInt(random, SHORTEST_FLECK, LONGEST_FLECK),
                    size = Mth.nextFloat(random, SMALLEST_FLECK, LARGEST_FLECK),
                    yaw = random.nextFloat() * Mth.TWO_PI,
                ),
            )
        }
    }

    /** Which stretch of shore a block belongs to. */
    private fun stretchOf(at: BlockPos): Long = BlockPos.asLong(at.x shr STRETCH_BITS, 0, at.z shr STRETCH_BITS)

    /** How far out the scan looks, in blocks — past it the shore is calm. */
    private const val REACH = 40
    private const val SIDE = REACH * 2 + 1
    private const val COLUMNS = SIDE * SIDE
    private const val COLUMNS_A_TICK = 512

    /** A stretch of shore is this many blocks on a side: 2^[STRETCH_BITS]. */
    private const val STRETCH_BITS = 3
    const val STRETCH = 1 shl STRETCH_BITS

    /** Six to nine seconds between waves on any one stretch, in ticks. */
    private const val SHORTEST_WAVE = 120
    private const val LONGEST_WAVE = 180
    private const val PHASE_BITS = 8

    private const val HALF_A_BLOCK = 0.5

    /** Where vanilla draws a still water block's surface, as a share of the block. */
    private const val WATER_FACE = 0.8889

    private const val FLECKS = 3

    /** How far out to sea a fleck starts and how far up the sand it runs, in blocks from the waterline. */
    private const val NEAREST_SWELL = 1.0
    private const val FURTHEST_SWELL = 2.0
    private const val SHORTEST_RUN = 0.3
    private const val LONGEST_RUN = 1.4
    private const val SHORT_RUN = 0.3

    private const val SHORTEST_FLECK = 50
    private const val LONGEST_FLECK = 70
    private const val SMALLEST_FLECK = 0.35f
    private const val LARGEST_FLECK = 0.6f

    /** How far seaward a shore needs open water, so a small pool inland breaks no waves. */
    private const val OPEN_WATER_NEAR = 2
    private const val OPEN_WATER_FAR = 4
}
