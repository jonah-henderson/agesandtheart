package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.age.aspect.Phenomenon
import co.voik.agesandtheart.content.AgeContent
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.tags.TagKey
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.item.FallingBlockEntity
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import co.voik.agesandtheart.location
import co.voik.agesandtheart.math.mix64
import co.voik.agesandtheart.math.unitDouble
import kotlin.math.roundToLong

/**
 * One cave-in: a fissure-shaped swathe of an Age that cracks and then crumbles away (design §5.3).
 *
 * **An entity rather than a list on the server, and that is §5.4 honoured rather than worked around**
 * (Jonah, 2026-09-11). The rule is that the world is the state; an entity *is* world state, saved with its
 * chunk and restored with it exactly as a block is, where a server-side register of collapses in progress
 * is the ledger the rule warns against. It also buys persistence and chunk-locality for nothing: a cave-in
 * only runs while somebody is near enough for its chunk to tick.
 *
 * **It saves a seed, a time and its dials, and reads the rest off the world.** The swathe is [Swathe.of] on
 * the seed, which is one of several shapes the ground can give way in. Which blocks are *left* is the
 * world's own record of how far this has got, so nothing about the frontier is persisted: a cave-in
 * reloaded mid-collapse looks at what is still standing and carries on from there.
 *
 * **The crack is vanilla's mining overlay**, which is what lets any of this touch an Age's existing blocks.
 * A `BlockState`'s properties are fixed at registration, so no ladder can be hung on somebody's granite —
 * but `destroyBlockProgress` draws on anything, of any shape, with no block change at all.
 */
class CaveIn(type: EntityType<out CaveIn>, level: Level) : Entity(type, level) {

    /** The shape's own seed. Everything about the swathe's outline comes out of this. */
    private var shape: Long = 0

    /** When it began, on the level's clock. */
    private var startedAt: Long = NOT_STARTED

    /** Which shape the ground gives way in — drawn from [shape] where nothing said. */
    private var form: CollapseShape? = null

    /** How far the swathe spreads and how fast it falls — from the Age's own fury where nothing said. */
    private var reach: Int = UNDRAWN
    private var pace: Double = UNDRAWN.toDouble()

    /** Drawn once and kept, the sweep asking for it several times a second. */
    private var drawnSwathe: Swathe? = null

    /**
     * What is cracking and since when — **rebuilt from the world, never saved**.
     *
     * A reloaded cave-in rescans its swathe, finds what is still standing and exposed, and starts those
     * cracking again. The cost of that is a block restarting its warning, which is the right way to be
     * wrong: nobody is killed by a crack that was further along before they logged out.
     */
    private val cracking = HashMap<BlockPos, Long>()

    /** When the first block gave way, on the level's clock — rebuilt rather than saved, as [cracking] is. */
    private var firstGaveWayAt = NOT_YET

    /** Which columns threw rubble and from what height, so one does not throw again on the layer below. */
    private val threwRubble = HashMap<Long, Int>()

    override fun defineSynchedData(builder: SynchedEntityData.Builder) = Unit

    /** Nothing draws it and nothing can touch it: it is a place where something is happening. */
    override fun isPickable(): Boolean = false

    override fun canBeCollidedWith(against: Entity?): Boolean = false

    /** Nothing can hurt a collapse. It is not a creature; it is the ground deciding to go. */
    override fun hurtServer(level: ServerLevel, source: DamageSource, amount: Float): Boolean = false

    override fun tick() {
        val level = level()
        if (level !is ServerLevel) return
        if (startedAt == NOT_STARTED) settleIn(level)
        val elapsed = level.gameTime - startedAt
        // **It can always die**, which an entity must be able to do: this one is saved, so a cave-in that
        // somehow found nothing to eat would otherwise sit in the chunk for the life of the world.
        if (elapsed > livesFor()) return discard()
        // **Crumbling every tick, scanning seldom.** Advancing what is already cracking is a walk of a small
        // map and has to look continuous — a stage is a tick, so gating it made the fall four times slower
        // than the stages say. Finding what is newly exposed is a sweep of the whole swathe and does not.
        crumbleWhatIsReady(level)
        if (level.gameTime % looksEvery() != 0L) return
        crackWhatIsExposed(level)
        if (cracking.isEmpty() && elapsed > SETTLES_AFTER) discard()
    }

    /**
     * What a cave-in nobody gave anything to has to work out for itself, once.
     *
     * One that [begin] made arrives knowing all of it. A summoned one has neither a seed nor its dials, so
     * it takes the size and pace **the Age it landed in would have given it** — which is what makes
     * `/summon` in a badly torn Age show that Age's collapse rather than an ordinary one.
     */
    private fun settleIn(level: ServerLevel) {
        startedAt = level.gameTime
        if (shape == NO_SHAPE) shape = level.random.nextLong()
        val undrawn = form == null || reach <= UNDRAWN || pace <= UNDRAWN
        if (!undrawn) return
        val behaviour = TectonicsBehaviour.of(level.server)
        val fury = Happenings.furyIn(level, Phenomenon.TECTONICS)
        if (form == null) form = behaviour.shapeDrawnFrom(shape)
        if (reach <= UNDRAWN) reach = behaviour.reachAt(fury)
        if (pace <= UNDRAWN) pace = behaviour.paceAt(fury)
    }

    private fun swathe(): Swathe =
        drawnSwathe ?: Swathe.of(form ?: CollapseShape.FISSURE, shape, reach).also { drawnSwathe = it }

    /** How long a block spends on each stage of its crumbling, in ticks — never less than a tick. */
    private fun aStage(): Long = (A_STAGE * pace).roundToLong().coerceAtLeast(ONE_TICK)

    /** How often the swathe is rescanned for what the last course exposed, in ticks. */
    private fun looksEvery(): Long = (LOOKS_EVERY * pace).roundToLong().coerceIn(ONE_TICK, SELDOMEST_LOOK)

    /**
     * How long this one may live at all, in ticks.
     *
     * Stretched by both dials, since the bound exists to stop a cave-in that found nothing to eat sitting
     * in the chunk forever — and a large or a slow one has honest work left long after the walked one is
     * done. Never shorter than the walked bound, however small and quick.
     */
    private fun livesFor(): Long =
        (LIVES_FOR * pace * reach / ORDINARY_REACH).roundToLong().coerceAtLeast(LIVES_FOR)

    /**
     * A bound on the frontier, so a cave-in in a cavern wall cannot walk the whole cavern at once.
     *
     * Raised with the reach rather than with the footprint, which grows as its square: a swathe four times
     * the ground gets twice the frontier, so a large collapse eats faster than a small one without the
     * per-tick cost of the crumbling walk going up with the area.
     */
    private fun mostAtOnce(): Int =
        (MOST_AT_ONCE * reach / ORDINARY_REACH).coerceIn(MOST_AT_ONCE, MOST_EVER_AT_ONCE)

    /**
     * Advance everything that is cracking, and take what has reached the end.
     *
     * **Each block counts from when its own crack appears**, rather than one clock for the whole swathe —
     * which is what makes a collapse eat inward in waves: the skin goes, and the layer behind it is exposed
     * and cracks. **Only the first cracks carry the warning.** Once the ground has started going, what it
     * exposes follows at once: the first crack was the warning, and a fresh wait on every course reads as
     * the collapse pausing.
     */
    private fun crumbleWhatIsReady(level: ServerLevel) {
        val gone = mutableListOf<BlockPos>()
        for ((at, began) in cracking) {
            val since = level.gameTime - began
            val standing = level.getBlockState(at)
            // Something else took it, or it was never ours to take.
            if (standing.isAir || immune(level, at)) {
                gone += at
                clearCrack(level, at)
                continue
            }
            // Each block waits a little longer than its neighbour, so a course breaks up rather than going
            // in one instant — see [staggerAt].
            val waits = (if (began >= firstGaveWayAt) FOLLOWS_FOR else WARNS_FOR) + staggerAt(at)
            if (since < waits) continue
            val stage = CRACKED_TO + ((since - waits) / aStage()).toInt()
            if (stage < GONE) {
                level.destroyBlockProgress(breakerFor(at), at, stage)
                continue
            }
            gone += at
            giveWay(level, at)
        }
        gone.forEach(cracking::remove)
    }

    /**
     * One block gives way.
     *
     * **Bedrock is taken like anything else and leaves the stars behind it** (Jonah, 2026-09-11), which is
     * what stops a cave-in at the world's floor being a hole into the void — the failure `Collapse` keeps a
     * layer underfoot to avoid. A star fissure goes on the far side of it, below a floor and above a
     * ceiling, so breaking through the bottom of the world is a way *out* of the Age rather than a death:
     * §7.8 already makes a fissure the recovery path for a stranded player.
     *
     * Everything else is simply removed, and sometimes throws a little rubble down the slope.
     */
    private fun giveWay(level: ServerLevel, at: BlockPos) {
        val standing = level.getBlockState(at)
        clearCrack(level, at)
        if (firstGaveWayAt == NOT_YET) firstGaveWayAt = level.gameTime
        if (standing.`is`(Blocks.BEDROCK)) {
            level.setBlock(at, AIR, Block.UPDATE_ALL)
            standTheStarsBehind(level, at)
            return
        }
        // **No drops.** A collapse that showered its contents would be a mining tool rather than a hazard,
        // and `Collapse` sets air for the same reason.
        level.setBlock(at, AIR, Block.UPDATE_ALL)
        // **Not the column that threw the last one.** A share drawn per block alone let one column shed a
        // block on every course, which reads as a chute rather than as a slope shedding rubble.
        val column = BlockPos.asLong(at.x, 0, at.z)
        if (random.nextFloat() < LEAVES_RUBBLE && standing.isSolidRender && threwRubble[column] != at.y + 1) {
            threwRubble[column] = at.y
            FallingBlockEntity.fall(level, at, standing)
        }
    }

    /** The stars on the far side of a broken world-floor or world-ceiling — see [giveWay]. */
    private fun standTheStarsBehind(level: ServerLevel, at: BlockPos) {
        val floor = level.minY
        val ceiling = level.minY + level.height
        val underTheFloor = at.y - floor <= ceiling - at.y
        val beyond = if (underTheFloor) at.below() else at.above()
        val inside = beyond.y >= floor && beyond.y < ceiling
        val where = if (inside) beyond else at
        if (level.getBlockState(where).isAir || where == at) {
            level.setBlock(where, STARS, Block.UPDATE_ALL)
        }
    }

    /**
     * Crack everything in the swathe that the open air can reach.
     *
     * **Exposure is the whole of the rule**, and it is what makes the vertical extent look after itself: on
     * a hillside a cave-in starts at the skin and eats in, and on flat ground it starts at the top and digs
     * its shape down, each course becoming exposed as the one above it goes. It is frosted ice's rim-first
     * idea asked a better way — *can this be seen* rather than *how many neighbours has it*.
     *
     * **A column is asked once and skipped whole**, which is what the shapes are split along ([Swathe]):
     * everything outside the plan costs one test rather than one per course.
     */
    private fun crackWhatIsExposed(level: ServerLevel) {
        val frontier = mostAtOnce()
        if (cracking.size >= frontier) return
        val middle = blockPosition()
        val swathe = swathe()
        val cursor = BlockPos.MutableBlockPos()
        for (awayX in -swathe.reachesOut..swathe.reachesOut) {
            for (awayZ in -swathe.reachesOut..swathe.reachesOut) {
                val howCentral = swathe.howCentral(awayX, awayZ)
                if (howCentral < OUTSIDE_IT) continue
                for (awayY in -swathe.reachesDown..swathe.reachesUp) {
                    if (!swathe.takes(awayX, awayY, awayZ, howCentral)) continue
                    cursor.set(middle.x + awayX, middle.y + awayY, middle.z + awayZ)
                    if (cursor.y < level.minY) continue
                    val at = cursor.immutable()
                    if (at in cracking) continue
                    val standing = level.getBlockState(at)
                    if (standing.isAir || immune(level, at)) continue
                    if (!openToTheAir(level, at)) continue
                    cracking[at] = level.gameTime
                    level.destroyBlockProgress(breakerFor(at), at, CRACKED_TO)
                    if (cracking.size >= frontier) return
                }
            }
        }
    }

    /**
     * How long this block waits past its course's own grace, in ticks.
     *
     * Read off the shape's seed and the position rather than rolled, so a reloaded cave-in staggers the
     * same way it was staggering before.
     */
    private fun staggerAt(at: BlockPos): Long =
        (unitDouble(mix64(shape xor at.asLong())) * STAGGERED_BY * pace).toLong()

    /** Whether any of the six sides of [at] is open, which is the only way a crack on it could be seen. */
    private fun openToTheAir(level: ServerLevel, at: BlockPos): Boolean =
        Direction.entries.any { !level.getBlockState(at.relative(it)).isSolidRender }

    /** What a cave-in will not take — `#immune_to_collapse`, plus the way out it must never swallow. */
    private fun immune(level: ServerLevel, at: BlockPos): Boolean =
        level.getBlockState(at).`is`(IMMUNE_TO_COLLAPSE)

    private fun clearCrack(level: ServerLevel, at: BlockPos) =
        level.destroyBlockProgress(breakerFor(at), at, CLEARED)

    /**
     * A breaker id per position, and it has to be per position.
     *
     * Vanilla keys the overlay by breaker so that several players can mine different blocks at once, which
     * means one id cracks one block — a whole swathe sharing this entity's id would show cracks on exactly
     * one of its blocks and read as the feature half working. Negative, so it can never collide with a real
     * entity's id.
     */
    private fun breakerFor(at: BlockPos): Int = -(at.asLong().hashCode() and Int.MAX_VALUE) - 1

    /**
     * The seed, the clock and the dials, which is all a cave-in is — see the class doc.
     *
     * **Every one of them may be absent**, which is what makes `/summon agesandtheart:cave_in ~ ~ ~
     * {form:"bolt",reach:40}` the instrument for looking at one shape: what is written is obeyed and what
     * is not is drawn in [settleIn].
     */
    override fun readAdditionalSaveData(input: ValueInput) {
        shape = input.getLongOr(SHAPE_KEY, NO_SHAPE)
        startedAt = input.getLongOr(STARTED_KEY, NOT_STARTED)
        form = input.getString(FORM_KEY).orElse(null)?.let(CollapseShape::named)
        reach = input.getIntOr(REACH_KEY, UNDRAWN)
        pace = input.getDoubleOr(PACE_KEY, UNDRAWN.toDouble())
    }

    override fun addAdditionalSaveData(output: ValueOutput) {
        output.putLong(SHAPE_KEY, shape)
        output.putLong(STARTED_KEY, startedAt)
        form?.let { output.putString(FORM_KEY, it.key) }
        output.putInt(REACH_KEY, reach)
        output.putDouble(PACE_KEY, pace)
    }

    companion object {
        /** Set once by whatever spawns it — see [CaveIns]. */
        fun begin(
            level: ServerLevel,
            at: BlockPos,
            shape: Long,
            form: CollapseShape,
            reach: Int,
            pace: Double,
        ): CaveIn? {
            val born = AgeContent.CAVE_IN.create(level, EntitySpawnReason.EVENT) ?: return null
            born.shape = shape
            born.form = form
            born.reach = reach
            born.pace = pace
            born.startedAt = level.gameTime
            born.setPos(at.x + HALF, at.y.toDouble(), at.z + HALF)
            level.addFreshEntity(born)
            return born
        }

        /**
         * What a cave-in will not touch, however much else it takes.
         *
         * The tag is the seam (Jonah, 2026-09-11): the technical blocks, the portals, and above all the
         * **star fissures**, which are the guaranteed way out of an Age — a collapse that ate the escape
         * hatch would be the exact inverse of the mercy §5.3 designs for.
         */
        val IMMUNE_TO_COLLAPSE: TagKey<Block> =
            TagKey.create(net.minecraft.core.registries.Registries.BLOCK, "immune_to_collapse".location())

        private val AIR = Blocks.AIR.defaultBlockState()
        private val STARS = AgeContent.STAR_FISSURE_BLOCK.defaultBlockState()

        /** Stage 5 of vanilla's ten: cracked enough to be unmistakable, far from falling. */
        const val CRACKED_TO = 5

        /** Past the last stage, which is what removing the overlay looks like. */
        private const val GONE = 10
        private const val CLEARED = -1

        /**
         * How long a block sits at [CRACKED_TO] before it starts going.
         *
         * **The warning is the mechanism, not decoration** (§5.3): long enough to get off it, short enough
         * to frighten. Three seconds is a stride and a jump.
         */
        private const val WARNS_FOR = 60L

        /** How long what the ground exposes once it has started going waits: nothing — see [crumbleWhatIsReady]. */
        private const val FOLLOWS_FOR = 0L

        /**
         * And then it goes, very fast — about a quarter of a second from first movement to gone, at the
         * walked pace. A pace under one cannot quicken this, a stage already being a tick.
         */
        private const val A_STAGE = 1L
        private const val ONE_TICK = 1L

        /** How far apart the blocks of one course start going, so a layer does not vanish all at once. */
        private const val STAGGERED_BY = 6.0

        /** Often enough to look continuous, seldom enough that a swathe is not rescanned every tick. */
        private const val LOOKS_EVERY = 4L

        /** Past this the frontier stops moving between looks, whatever the pace says. */
        private const val SELDOMEST_LOOK = 10L

        /** A bound on the frontier at the walked reach — see [mostAtOnce]. */
        private const val MOST_AT_ONCE = 512
        private const val MOST_EVER_AT_ONCE = 1024

        /** The reach the two bounds above were walked at, which is what the dials are read against. */
        private const val ORDINARY_REACH = 24

        /** A column the swathe does not reach at all — see [Swathe.howCentral]. */
        private const val OUTSIDE_IT = 0.0

        /** Quiet for this long with nothing left cracking means it has finished. */
        private const val SETTLES_AFTER = 200L

        /** Whatever happens, an ordinary one is gone after two minutes — see [livesFor]. */
        private const val LIVES_FOR = 2400L

        private const val LEAVES_RUBBLE = 0.08f

        private const val NOT_STARTED = Long.MIN_VALUE

        /** Nothing has given way yet: later than any crack could have begun. */
        private const val NOT_YET = Long.MAX_VALUE

        /** What a summoned cave-in has instead of a seed, until its first tick draws one. */
        private const val NO_SHAPE = 0L

        /** What a dial nobody set reads as, so a summoned cave-in knows to ask the Age for it. */
        private const val UNDRAWN = 0

        private const val HALF = 0.5

        private const val SHAPE_KEY = "shape"
        private const val STARTED_KEY = "started_at"
        private const val FORM_KEY = "form"
        private const val REACH_KEY = "reach"
        private const val PACE_KEY = "pace"
    }
}
