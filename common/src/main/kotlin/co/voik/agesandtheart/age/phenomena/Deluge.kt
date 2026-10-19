package co.voik.agesandtheart.age.phenomena

import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.material.Fluid
import net.minecraft.world.level.block.Block
import net.minecraft.server.level.ServerPlayer
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket
import net.minecraft.tags.FluidTags
import net.minecraft.world.phys.AABB
import net.minecraft.world.entity.vehicle.boat.AbstractBoat
import co.voik.agesandtheart.age.AgeSavedData
import co.voik.agesandtheart.age.Manifestation
import co.voik.agesandtheart.age.Spending
import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.aspect.Recurrence
import co.voik.agesandtheart.age.aspect.Phenomenon
import co.voik.agesandtheart.age.aspect.Rung
import co.voik.agesandtheart.generation.AgeChunkGenerator
import co.voik.agesandtheart.platform.Services
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.material.FlowingFluid
import net.minecraft.world.level.material.Fluids
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The rising sea — design §5.2's deluge, and the second gate on the deep-ocean material of §7.1.2.
 *
 * **The water comes up, and it comes up under torrential rain.** The phenomenon is a downpour that will not
 * stop, and the sea arriving is what the downpour does.
 *
 * **How fast, how often and how far are the Age's instability's to say** ([Rising]), one dial each, so an
 * Age can earn a near-constant downpour cheaply and only one about to come apart drowns to the build limit.
 * The sea climbs only while it rains, so the rain is visibly what brings it.
 *
 * **Written first, risen after.** An Age nothing has begun to drown generates at exactly the sea it was
 * written with, which is what the rest of it is built against: standing the sea short of the written one
 * to climb back to it left vanilla's ocean monuments, and its ocean decoration generally, at a level the
 * water had walked away from.
 *
 * **It resolves.** Once the rise is complete the phenomenon is over and what is left is a drowned Age — a
 * standing condition rather than a permanent tax.
 *
 * **Three parts, and only the first of them is stored.**
 *
 * - **The sea's own level**, which is the counter: [AgeSavedData.presenceIn] ticks of rain, turned into
 *   blocks by [risenAt], handed to the generator by [stand]. This is §5.4's one licensed number, and what buys it
 *   is that the sea surface has to be *globally coherent* — a chunk generated three thousand blocks out
 *   must flood to the same line as the one under your feet, and no sampler can promise that.
 * - **The rise made real**, [raise], which is the block work. New chunks come out of the generator already
 *   at the right height; everything made earlier is brought up to it a layer at a time, at one position per
 *   column, over every chunk anybody can see.
 * - **The pooling**, [pool], which needs no number at all — the water is the state. It is what keeps a
 *   ceiling from being an immunity: any writer who reads their own book knows a safe altitude, and rain
 *   that stands where it falls reaches them there.
 */
object Deluge {

    /**
     * Ages where `/age weather deluge` has made the rain pool, whether or not a deluge befalls them. The sea
     * does not climb in one: its level is a saved counter, and a walk should not leave that moved.
     */
    private val forced: MutableSet<ServerLevel> = Collections.newSetFromMap(Collections.synchronizedMap(WeakHashMap()))

    fun force(level: ServerLevel) {
        forced += level
    }

    fun release(level: ServerLevel) {
        forced -= level
    }

    fun isForcedIn(level: ServerLevel): Boolean = level in forced

    /** Ages a deluge is falling on now, as `Happenings` last found — where it rains whatever the biome. */
    private val pouring: MutableSet<ServerLevel> = Collections.newSetFromMap(Collections.synchronizedMap(WeakHashMap()))

    /** Whether a deluge befalls [level] now. Told by `Happenings` on every tick it is watched. */
    fun pours(level: ServerLevel, isPouring: Boolean) {
        if (isPouring) pouring += level else pouring -= level
    }

    /**
     * What falls at a place whose biome answers [biomes]: rain, under a deluge, where the biome would have none.
     * Snow stays snow. Asked by `LevelRainMixin`, inside vanilla's own refusals for no rain, no sky and cover.
     */
    @JvmStatic
    fun precipitationIn(level: Level, biomes: Biome.Precipitation): Biome.Precipitation {
        val isDryButDrowning = biomes == Biome.Precipitation.NONE && level is ServerLevel && level in pouring
        return if (isDryButDrowning) Biome.Precipitation.RAIN else biomes
    }

    /**
     * What one Age's deluge does — its three dials, read off its rung and what its instability bought.
     *
     * A pure function of the recipe, so the counter's ticks turn into the same height on every open.
     */
    data class Rising(
        /** How many ticks of rain, with somebody present, the sea takes to gain a block. */
        val ticksPerBlock: Long,
        /** How far toward the build limit the sea may climb past [ORDINARY_RISE], nought to one. */
        val heightReach: Double,
        /** The share of the time the Age asks to be raining, as `WeatherConditions.rainfall` reads it. */
        val rainShare: Double,
        /** How heavy the rain looks, nought to one — what a client is told to draw. */
        val heaviness: Double,
    )

    /**
     * What the deluge does in an Age whose book claims [written] and whose instability bought [spending],
     * or null where no deluge befalls it from either direction.
     */
    fun risingIn(composition: AgeComposition, seed: Long, spending: Spending): Rising? =
        Happenings.befallingIn(composition, seed, spending)[Phenomenon.DELUGE]?.let { risingOf(it.recurrence, spending, it.strength) }

    /**
     * What a deluge written to come this [recurrence] and this hard ([written], -1 to 1, which moves how fast
     * the sea rises) does in an Age that bought [spending].
     */
    fun risingOf(recurrence: Recurrence, spending: Spending, written: Double = UNSAID_STRENGTH): Rising {
        val rate = harderBy(spending.reach(Manifestation.DELUGE, Manifestation.RISE_RATE), written)
        val downpour = spending.reach(Manifestation.DELUGE, Manifestation.DOWNPOUR)
        val height = spending.reach(Manifestation.DELUGE, Manifestation.RISE_HEIGHT)
        // Geometric, so every step of rate is the same proportion quicker rather than the last ones counting
        // for nothing against a figure already small.
        val quickening = (FASTEST_TICKS_PER_BLOCK.toDouble() / ORDINARY_TICKS_PER_BLOCK).pow(rate)
        val asked = (ORDINARY_DOWNPOUR * recurrence.often).coerceAtMost(ALMOST_CONSTANT)
        return Rising(
            ticksPerBlock = (ORDINARY_TICKS_PER_BLOCK * quickening).roundToLong(),
            heightReach = height,
            rainShare = if (recurrence.isConstant) ALWAYS else asked + (ALMOST_CONSTANT - asked) * downpour,
            heaviness = spending.reach(Manifestation.DELUGE),
        )
    }

    /**
     * How far over its written surface [writtenSurface] the sea can be carried in a level whose highest
     * block is [topY]: [ORDINARY_RISE], and [Rising.heightReach] of the room left above that.
     */
    fun climbFor(heightReach: Double, writtenSurface: Int, topY: Int): Int {
        val room = (topY - HEADROOM - writtenSurface).coerceAtLeast(0)
        val ordinary = ORDINARY_RISE.coerceAtMost(room)
        return ordinary + ((room - ordinary) * heightReach.coerceIn(0.0, 1.0)).roundToInt()
    }

    /** How far above its written level the sea stands after [ticks] of rain with somebody present. */
    fun risenAt(ticks: Long, ticksPerBlock: Long, climb: Int): Int =
        (ticks / ticksPerBlock.coerceAtLeast(1L)).coerceIn(0L, climb.toLong()).toInt()

    /**
     * Tell [level]'s generator where its sea stands, where [rising] is null for an Age nothing is drowning.
     *
     * **Done for every Age on every tick, including the ones nobody is in.** The *counter* only advances
     * while somebody is present and it is raining, but the generator must be right whenever a chunk is made
     * — and a chunk can be made in an empty Age by a forceload, a teleport arriving, or a neighbouring
     * player's view. It is an integer compare when nothing has changed.
     */
    fun stand(level: ServerLevel, rising: Rising?, ticks: Long) {
        val generator = level.chunkSource.generator as? AgeChunkGenerator ?: return
        val written = generator.writtenSeaSurfaceY
        if (rising == null || written == null) return generator.standAbove(NOT_DROWNING)
        val climb = climbFor(rising.heightReach, written, level.maxY)
        generator.standAbove(risenAt(ticks, rising.ticksPerBlock, climb))
    }

    /**
     * Tell everybody in [level] how heavy the rain is, or that there is no deluge here.
     *
     * Sent on the same slow beat as the blizzard's, for the same reason — see [DelugePayload].
     */
    fun tellTheClients(level: ServerLevel, rising: Rising?) {
        val age = level.dimension().identifier()
        val payload = if (rising == null) DelugePayload.noneIn(age) else DelugePayload(age, rising.heaviness)
        for (player in level.players()) Services.NETWORK.sendToPlayer(player, payload)
    }

    /**
     * Bring the sea up — **and the whole of it is one position per column** (Jonah's rule, 2026-09-12).
     *
     * At every block of the sea's own fluid that is *outside*:
     *
     * - a **flowing** block becomes a source, and
     * - a **source** with room over it grows a source into that room.
     *
     * Neither ever reaches above where the sea stands, which is what keeps a waterfall on a hillside a
     * waterfall and a puddle on a roof a puddle.
     *
     * **"Outside" means it is the top of its column, and that is the realisation the second attempt turned
     * on.** A block with nothing above it in its column *is* the block the heightmap names — so there is
     * exactly one candidate per column, never a range of levels to walk. The version before this swept
     * every level under the sea in every chunk and needed a cheap pre-probe to afford it; this reads one
     * height and at most one block state per column and needs no probe at all, because there is nothing
     * left to make cheaper.
     *
     * **A pond thirty blocks down is reached by the same rule as the open ocean**, which is what the
     * version before this got wrong. It skipped any chunk whose *highest* water already stood at the line,
     * so a low pool in a coastal chunk was never touched while the same pool inland filled. There is no
     * "is this chunk behind" question any more — every column is asked, every pass, and the ones with
     * nothing to do cost a heightmap read.
     *
     * **A column comes all the way up on the pass that reaches it.** The sea gains a block every
     * [Rising.ticksPerBlock] of rain, so ordinarily that is the one block the column is behind; what it buys is the
     * chunk nobody has been near, which arrives at the line in one visit rather than over as many passes as
     * it is behind. A block a pass had the water climbing in strips — the near columns a block, then the
     * ring beyond them a block — where a flood is a surface arriving.
     */
    fun raise(level: ServerLevel) {
        val sea = level.seaBlock() ?: return
        val standing = level.seaSurfaceY() ?: return
        val inView = Sampling.inViewNearestFirst(level)
        if (inView.isEmpty()) return
        // **A step of the sea starts the sweep over from the nearest chunk** (2026-10-19), so each block of rise comes
        // in as a ring rolling out from whoever is there. The list is sorted near to far, and the sweep goes on
        // round it after, cheaply, for whatever comes back into view behind.
        val sweep = sweeps.getOrPut(level) { Sweep(standing) }
        sweep.restartIfTheSeaHasMovedTo(standing)
        // **No more than [CHUNKS_RAISED_A_TICK] whole chunks a tick**, which is the client's limit rather than the
        // server's: each block of rise re-lights the water under it and rebuilds every section it touches, and
        // sixteen chunks a tick of that dropped frames. Whole, so each section is rebuilt once a step.
        // Counters rather than a fold, being the two things this loop carries.
        var raisedChunks = 0
        var lookedAt = 0
        while (lookedAt < CHUNKS_A_TICK && raisedChunks < CHUNKS_RAISED_A_TICK) {
            val packed = inView[sweep.nextOf(inView.size)]
            lookedAt++
            val chunk = level.chunkSource.getChunkNow(ChunkPos.getX(packed), ChunkPos.getZ(packed)) ?: continue
            if (raiseIn(level, chunk, standing, sea)) {
                raisedChunks++
                floatTheBoatsIn(level, chunk, standing)
            }
        }
    }

    /** Where in the chunks in view [raise] has got to, and the sea it was raising them to. */
    private class Sweep(private var standing: Int) {
        private var next = 0

        fun restartIfTheSeaHasMovedTo(now: Int) {
            if (now == standing) return
            standing = now
            next = 0
        }

        /** The index to look at next in a list of [size], round again from the nearest at its end. */
        fun nextOf(size: Int): Int {
            if (next >= size) next = 0
            return next++
        }
    }

    /** Each drowning level's sweep, kept as long as the level is. Read on the server thread only. */
    private val sweeps = WeakHashMap<ServerLevel, Sweep>()

    /** One chunk: the two rules, at the top of each of its two hundred and fifty-six columns. Answers whether any rose. */
    private fun raiseIn(level: ServerLevel, chunk: LevelChunk, standing: Int, sea: BlockState): Boolean {
        var raisedAny = false
        val seaFluid = sea.fluidState.type
        // **Both halves of the fluid count**, which is the whole of the flowing rule: a spill is
        // `flowing_water` and its `FluidState` is not of the source's type.
        val flowingSea = (seaFluid as? FlowingFluid)?.flowing ?: seaFluid
        val cursor = BlockPos.MutableBlockPos()
        val originX = chunk.pos.minBlockX
        val originZ = chunk.pos.minBlockZ
        val laidQuietly = ArrayList<BlockPos>()
        for (offsetX in 0..<CHUNK_WIDTH) {
            for (offsetZ in 0..<CHUNK_WIDTH) {
                // A chunk's `getHeight` is the top block itself, where a level's is the space over it. **Leaves are not a
                // roof** (Jonah, 2026-10-19): read with them, the top under a tree was its canopy, so the sea never rose
                // under it and left dry pockets round every tree it drowned.
                val top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, offsetX, offsetZ)
                if (top > standing) continue
                val x = originX + offsetX
                val z = originZ + offsetZ
                cursor.set(x, top, z)
                val here = chunk.getBlockState(cursor).fluidState
                if (here.type != seaFluid && here.type != flowingSea) continue
                if (!here.isSource) {
                    // Immutable: `setBlockAndUpdate` hands the position to neighbour updates, and a cursor
                    // that moves under them is the classic way to poison a tick queue.
                    level.setBlockAndUpdate(BlockPos(x, top, z), sea)
                    raisedAny = true
                    continue
                }
                // Up to the line in one visit, stopping under whatever roofs the column: every block of it
                // passes the same test the one below it did, so a sealed room stays dry however far the sea
                // outside it has come.
                var risen = top
                while (risen < standing) {
                    cursor.set(x, risen + 1, z)
                    val above = chunk.getBlockState(cursor)
                    if (above.`is`(BlockTags.LEAVES)) {
                        // Through a canopy, taking the leaves in as it goes, where the sea is water and they can hold it.
                        if (!takesIn(level, chunk, cursor, above, seaFluid, laidQuietly)) break
                        raisedAny = true
                        risen++
                        continue
                    }
                    if (!above.canBeReplaced(seaFluid)) break
                    val at = BlockPos(x, risen + 1, z)
                    if (lay(level, chunk, at, sea, flowingSea)) laidQuietly += at
                    raisedAny = true
                    risen++
                }
            }
        }
        letInWhereTheSeaMeetsAir(level, laidQuietly, seaFluid)
        return raisedAny
    }

    /**
     * Whether the sea goes on up through [leaves] at [at]: waterlogging them, written quietly as open sea is and
     * remembered with it, so the air beside them can be let in. A sea of anything but water stops at a canopy.
     */
    private fun takesIn(
        level: ServerLevel,
        chunk: LevelChunk,
        at: BlockPos,
        leaves: BlockState,
        seaFluid: Fluid,
        laidQuietly: MutableList<BlockPos>,
    ): Boolean {
        val canHoldTheSea = seaFluid.isSame(Fluids.WATER) && leaves.hasProperty(BlockStateProperties.WATERLOGGED)
        if (!canHoldTheSea) return false
        if (leaves.getValue(BlockStateProperties.WATERLOGGED)) return true
        val drowned = leaves.setValue(BlockStateProperties.WATERLOGGED, true)
        val held = at.immutable()
        if (chunk.setBlockState(held, drowned, Block.UPDATE_SKIP_ON_PLACE) != null) level.chunkSource.blockChanged(held)
        laidQuietly += held
        return true
    }

    /**
     * A fluid tick for every block laid quietly that still has air beside it once its chunk is up (2026-10-19), so the
     * sea spills in under an overhang or into a doorway as it did when every block was placed with all its updates.
     * Asked after the chunk rather than as each block goes down, because then the sea beside it in the same chunk has
     * risen too, and only the edges of what rose are left: the chunk's border, and the mouths of what stayed dry.
     */
    private fun letInWhereTheSeaMeetsAir(level: ServerLevel, laidQuietly: List<BlockPos>, seaFluid: Fluid) {
        for (at in laidQuietly) {
            val hasAirBeside = Direction.Plane.HORIZONTAL.any { side -> level.getBlockState(at.relative(side)).isAir }
            if (hasAirBeside) level.scheduleTick(at, seaFluid, seaFluid.getTickDelay(level))
        }
    }

    /**
     * One block of risen sea, **written into the chunk itself where nothing beside it could care** (2026-10-19). Out in
     * open sea, with only the sea and air beside it, everything a level's own placing adds is wasted: neighbour and
     * shape updates that nothing answers, and above all the water's own `onPlace`, which schedules a fluid tick for
     * every block laid — a search for somewhere to flow that open sea never has, thousands a tick. The chunk's own
     * write keeps what has to stay true, its heightmaps and its light, and the client is told as vanilla tells it,
     * batched a section a tick. Beside anything else — a shore, a plant, lava — it is placed with every update, so the
     * sea spills and meets as before. Answers whether it was laid quietly, so the air it may yet spill into can be
     * looked for once its chunk is up — see [letInWhereTheSeaMeetsAir].
     */
    private fun lay(level: ServerLevel, chunk: LevelChunk, at: BlockPos, sea: BlockState, flowingSea: Fluid): Boolean {
        val seaFluid = sea.fluidState.type
        fun isSeaOrAir(state: BlockState) = state.isAir || state.fluidState.type == seaFluid || state.fluidState.type == flowingSea
        val inOpenSea = Direction.Plane.HORIZONTAL.all { side -> isSeaOrAir(level.getBlockState(at.relative(side))) }
        if (!inOpenSea) {
            level.setBlock(at, sea, Block.UPDATE_ALL)
            return false
        }
        if (chunk.setBlockState(at, sea, Block.UPDATE_SKIP_ON_PLACE) != null) level.chunkSource.blockChanged(at)
        return true
    }

    /**
     * Back onto the surface, every boat the rise put under it (testing, 2026-10-19). The sea comes up over a boat
     * rather than under it, and a boat with water over it sinks, its buoyancy there a sliver of its weight, and throws
     * its riders out after three seconds of it. Set at vanilla's own floating height for the water there,
     * and only where nothing is in the way.
     */
    private fun floatTheBoatsIn(level: ServerLevel, chunk: LevelChunk, standing: Int) {
        val within = AABB(
            chunk.pos.minBlockX.toDouble(), level.minY.toDouble(), chunk.pos.minBlockZ.toDouble(),
            chunk.pos.maxBlockX + 1.0, standing + ABOVE_THE_LINE, chunk.pos.maxBlockZ + 1.0,
        )
        for (boat in level.getEntitiesOfClass(AbstractBoat::class.java, within)) {
            val floatsAt = floatingHeightOver(level, boat) ?: continue
            val lifted = boat.boundingBox.move(0.0, floatsAt - boat.y, 0.0)
            if (!level.noCollision(boat, lifted)) continue
            boat.setPos(boat.x, floatsAt, boat.z)
            boat.deltaMovement = boat.deltaMovement.multiply(1.0, 0.0, 1.0)
            // A boat somebody is steering goes where their client says, so the server's move has to be told to it,
            // as vanilla tells it when it corrects one — or the client sinks it again.
            (boat.controllingPassenger as? ServerPlayer)?.connection?.send(ClientboundMoveVehiclePacket.fromEntity(boat))
        }
    }

    /** Where [boat] floats, if water stands over it: vanilla's own height for a boat on that water, or null. */
    private fun floatingHeightOver(level: ServerLevel, boat: AbstractBoat): Double? {
        val overItsTop = BlockPos.containing(boat.x, boat.boundingBox.maxY + OVER_ITS_TOP, boat.z)
        val fluid = level.getFluidState(overItsTop)
        val isUnderTheSea = fluid.`is`(FluidTags.WATER) && boat.boundingBox.maxY < overItsTop.y + fluid.getHeight(level, overItsTop)
        if (!isUnderTheSea) return null
        val surface = BlockPos.MutableBlockPos(overItsTop.x, overItsTop.y, overItsTop.z)
        while (level.getFluidState(surface.above()).`is`(FluidTags.WATER)) surface.move(0, 1, 0)
        val waterTop = surface.y + level.getFluidState(surface).getHeight(level, surface)
        return waterTop - boat.bbHeight + VANILLAS_FLOATING_CLEARANCE
    }

    /**
     * Rain that stands where it falls — the half of the deluge that has nothing to do with the sea.
     *
     * **A ceiling the writer chose is also an immunity**, and this is what answers it: anybody who read
     * their own book knows a safe altitude and would otherwise be untouchable there forever. So water is
     * placed on sky-lit ground *above* the waterline, and vanilla's own fluid physics does the rest — open
     * ground sheds it, hollows keep it, a walled courtyard fills, and a sealed room with one hole in the
     * roof fills completely. No basin detection, no frontier, and it caps itself structurally: a full bowl
     * spills and stops.
     *
     * **Its counterplay is a roof**, which is the blizzard's bargain in a different costume — the thing
     * that protects your ground protects you.
     *
     * **Never water on water** (Jonah, 2026-09-17). A drop onto a puddle stacked a source on it, and
     * enough of those read as pillars of water standing at random. So a drop lands only on dry ground, or
     * turns a spreading puddle's flowing edge into a source where it lies — and only an edge lying on firm
     * ground (Jonah, 2026-09-18), never one over air, over other water, or falling, any of which pours off
     * as a column.
     *
     * Sampled near the players where the rise is swept over everything in view, and the difference is the
     * point: a sea is one surface and must be coherent everywhere, where a puddle is a local accident and
     * nobody can tell that the one in the next valley never happened.
     */
    fun pool(level: ServerLevel) {
        if (!level.isRaining) return
        val standing = level.seaSurfaceY() ?: return
        Sampling.sweep(level, ONE_SAMPLE) { _, at ->
            if (level.random.nextInt(ONE_DROP_IN) != 0) return@sweep
            val top = BlockPos(at.x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, at.x, at.z) - 1, at.z)
            // Below the waterline is the sea's business, and a source laid down there would put water
            // inside whatever the sea has not reached yet — the sealed room the promise above depends on.
            if (top.y <= standing) return@sweep
            val onto = top.above()
            // Only where rain falls — a deluge's falls on dry biomes too ([precipitationIn]), never as snow.
            if (level.precipitationAt(onto) != Biome.Precipitation.RAIN) return@sweep
            val ground = level.getBlockState(top)
            val lying = ground.fluidState
            if (lying.`is`(Fluids.WATER) || lying.`is`(Fluids.FLOWING_WATER)) {
                val isSpreading = !lying.isSource && ground.`is`(Blocks.WATER)
                val isFalling = lying.getValue(FlowingFluid.FALLING)
                val liesOnFirmGround = isFirmGround(level, top.below())
                if (isSpreading && !isFalling && liesOnFirmGround) {
                    level.setBlockAndUpdate(top, Blocks.WATER.defaultBlockState())
                }
                return@sweep
            }
            if (!isFirmGround(level, top)) return@sweep
            if (!level.getBlockState(onto).canBeReplaced(Fluids.WATER)) return@sweep
            level.setBlockAndUpdate(onto, Blocks.WATER.defaultBlockState())
        }
    }

    /** Whether [at]'s top face will hold standing water: sturdy, and not leaves, which it pours through. */
    private fun isFirmGround(level: ServerLevel, at: BlockPos): Boolean {
        val block = level.getBlockState(at)
        return block.isFaceSturdy(level, at, Direction.UP) && !block.`is`(BlockTags.LEAVES)
    }

    /**
     * This Age's own sea material, or null where it has no sea to raise — vanilla's rock's where it wears it,
     * whose new chunks come out at its own level and are raised to the line as they come into view.
     */
    private fun ServerLevel.seaBlock(): BlockState? = (chunkSource.generator as? AgeChunkGenerator)?.seaBlock

    /** Where this Age's sea currently stands, or null where it has none. */
    private fun ServerLevel.seaSurfaceY(): Int? = (chunkSource.generator as? AgeChunkGenerator)?.standingSeaSurfaceY

    /** An Age with nothing drowning it stands at what it was written with. */
    private const val NOT_DROWNING = 0

    /** Sixteen, and named because a chunk's own offsets are what the sweep walks. */
    private const val CHUNK_WIDTH = 16

    /**
     * How many chunks a tick the sweep looks at. A look costs one heightmap read per column and at most one block
     * state, so at a view distance of twelve, some six hundred chunks, it is round the whole of it in about a second.
     */
    private const val CHUNKS_A_TICK = 32

    /**
     * How many whole chunks one tick may raise — **the animation, and the number to walk against** (2026-10-19). Eighty
     * a second, so a ring of rise rolls from under your feet to the horizon in several seconds, which the client can
     * draw; sixteen chunks a tick dropped its frames.
     *
     * The near chunks cannot eat it before the far ones are reached: the sweep starts from the nearest only when the
     * sea steps up, and a chunk already at the line costs it nothing.
     */
    private const val CHUNKS_RAISED_A_TICK = 4

    /** How far over the line a boat may sit and still have been under it: it floats a little above the water. */
    private const val ABOVE_THE_LINE = 3.0

    /** `AbstractBoat.isUnderwater`'s own nudge over the box's top. */
    private const val OVER_ITS_TOP = 0.001

    /** What `AbstractBoat.floatBoat` adds to the water's surface less the boat's height, landing from the air. */
    private const val VANILLAS_FLOATING_CLEARANCE = 0.101

    /**
     * How far over its written level an ordinary deluge carries the sea, in blocks — what an Age that
     * bought no height climbs.
     *
     * High enough that the shape of play changes as it rises — shoreline, footpaths and cave mouths go
     * under within the arc rather than all at the end — and low enough that the Age is recognisably the one
     * that was written throughout.
     */
    const val ORDINARY_RISE = 24

    /** Blocks kept clear under the build limit, so the highest sea still has a surface to stand on. */
    private const val HEADROOM = 2

    /**
     * Ticks of rain per block for an ordinary deluge, and for one whose rate is bought out.
     *
     * At the ordinary figure and [ORDINARY_DOWNPOUR], [ORDINARY_RISE] takes about two hours of being in the
     * Age; bought out, a block every fifteen seconds of rain. UNWALKED.
     */
    const val ORDINARY_TICKS_PER_BLOCK = 3000L
    const val FASTEST_TICKS_PER_BLOCK = 300L

    /** The share of the time an ordinary deluge asks to be raining — what its word insists on. */
    private val ORDINARY_DOWNPOUR = Phenomenon.DELUGE.insistsOn.rainfall

    /** The most of the time any deluge can ask to be raining: dry spells of well under a minute. */
    private const val ALMOST_CONSTANT = 0.98
    private const val ALWAYS = 1.0
    private const val UNSAID_STRENGTH = 0.0

    /**
     * How the pooling rain is sampled: one chance per chunk per tick through [Sampling.sweep], and one drop in
     * this many of those — with the sweep's own one in [Sampling.BETWEEN_CHUNK_SAMPLES], a drop in each chunk
     * about once a minute (Jonah, 2026-09-18: thirty seconds pooled too much, and a minute is still ahead of
     * the sea, which gains a block every two and a half minutes of rain at the ordinary rate). A drop that
     * finds nowhere to land is skipped, so fewer are placed than drawn. That is some three drops a second
     * over the thirteen-chunk square the sweep covers. UNWALKED.
     */
    private const val ONE_SAMPLE = 1
    private const val ONE_DROP_IN = 24
}
