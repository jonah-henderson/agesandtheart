package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.worldgen.fissure.Crack
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

/**
 * One cave-in: a fissure-shaped swathe of an Age that cracks and then crumbles away (design §5.3).
 *
 * **An entity rather than a list on the server, and that is §5.4 honoured rather than worked around**
 * (Jonah, 2026-09-11). The rule is that the world is the state; an entity *is* world state, saved with its
 * chunk and restored with it exactly as a block is, where a server-side register of collapses in progress
 * is the ledger the rule warns against. It also buys persistence and chunk-locality for nothing: a cave-in
 * only runs while somebody is near enough for its chunk to tick.
 *
 * **It saves a seed and a time, and reads the rest off the world.** The swathe is [Crack.of] on the seed,
 * which is the same shape — and the same maths — a star fissure and a collapse tear are cut with. Which
 * blocks are *left* is the world's own record of how far this has got, so nothing about the frontier is
 * persisted: a cave-in reloaded mid-collapse looks at what is still standing and carries on from there.
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
        if (startedAt == NOT_STARTED) {
            startedAt = level.gameTime
            // A summoned one arrives with no shape, so it draws its own — see the entity type's comment.
            if (shape == NO_SHAPE) shape = level.random.nextLong()
        }
        val elapsed = level.gameTime - startedAt
        // **It can always die**, which an entity must be able to do: this one is saved, so a cave-in that
        // somehow found nothing to eat would otherwise sit in the chunk for the life of the world.
        if (elapsed > LIVES_FOR) return discard()
        // **Crumbling every tick, scanning seldom.** Advancing what is already cracking is a walk of a small
        // map and has to look continuous — a stage is a tick, so gating it made the fall four times slower
        // than the stages say. Finding what is newly exposed is a sweep of the whole swathe and does not.
        crumbleWhatIsReady(level)
        if (level.gameTime % LOOKS_EVERY != 0L) return
        crackWhatIsExposed(level)
        if (cracking.isEmpty() && elapsed > SETTLES_AFTER) discard()
    }

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
            val stage = CRACKED_TO + ((since - waits) / A_STAGE).toInt()
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
     * a fissure-shaped trench down, each course becoming exposed as the one above it goes, into the trough
     * [claimedAt] cuts. It is frosted ice's rim-first idea asked a better way — *can this be seen* rather than
     * *how many neighbours has it*.
     */
    private fun crackWhatIsExposed(level: ServerLevel) {
        if (cracking.size >= MOST_AT_ONCE) return
        val middle = blockPosition()
        val crack = Crack.of(shape)
        val cursor = BlockPos.MutableBlockPos()
        for (awayX in -REACHES..REACHES) {
            for (awayZ in -REACHES..REACHES) {
                val central = crack.centralityAt(awayX, awayZ)
                if (central < OUTSIDE_IT) continue
                for (awayY in -DEEPENS..DEEPENS) {
                    if (central < claimedAt(awayX, awayY, awayZ)) continue
                    cursor.set(middle.x + awayX, middle.y + awayY, middle.z + awayZ)
                    if (cursor.y < level.minY) continue
                    val at = cursor.immutable()
                    if (at in cracking) continue
                    val standing = level.getBlockState(at)
                    if (standing.isAir || immune(level, at)) continue
                    if (!openToTheAir(level, at)) continue
                    cracking[at] = level.gameTime
                    level.destroyBlockProgress(breakerFor(at), at, CRACKED_TO)
                    if (cracking.size >= MOST_AT_ONCE) return
                }
            }
        }
    }

    /**
     * **How central a column has to be for this course to take it**: nothing at all at the top, and
     * tightening with depth, so the swathe narrows to a trough down the crack's own middle rather than
     * dropping a shaft with vertical walls and a level floor.
     *
     * Roughened per block, which is what keeps the sides from reading as a cone. Read off the shape's seed
     * and the position, so a reloaded cave-in cuts the same trough it was cutting before.
     */
    private fun claimedAt(awayX: Int, awayY: Int, awayZ: Int): Double {
        if (awayY >= AT_THE_TOP) return NOTHING_REQUIRED
        val depth = -awayY.toDouble() / DEEPENS
        val rough = (unitDouble(mix64(shape xor BlockPos.asLong(awayX, awayY, awayZ))) * TWICE - ONE) * ROUGHNESS
        return (depth * depth + rough).coerceAtLeast(NOTHING_REQUIRED)
    }

    /**
     * How long this block waits past its course's own grace, in ticks.
     *
     * Read off the shape's seed and the position rather than rolled, so a reloaded cave-in staggers the
     * same way it was staggering before.
     */
    private fun staggerAt(at: BlockPos): Long =
        (unitDouble(mix64(shape xor at.asLong())) * STAGGERED_BY).toLong()

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

    /** The seed and the clock, which is all a cave-in is — see the class doc. */
    override fun readAdditionalSaveData(input: ValueInput) {
        shape = input.getLongOr(SHAPE_KEY, 0L)
        startedAt = input.getLongOr(STARTED_KEY, NOT_STARTED)
    }

    override fun addAdditionalSaveData(output: ValueOutput) {
        output.putLong(SHAPE_KEY, shape)
        output.putLong(STARTED_KEY, startedAt)
    }

    companion object {
        /** Set once by whatever spawns it — see [CaveIns]. */
        fun begin(level: ServerLevel, at: BlockPos, shape: Long): CaveIn? {
            val born = AgeContent.CAVE_IN.create(level, EntitySpawnReason.EVENT) ?: return null
            born.shape = shape
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

        /** And then it goes, very fast — about a quarter of a second from first movement to gone. */
        private const val A_STAGE = 1L

        /** How far apart the blocks of one course start going, so a layer does not vanish all at once. */
        private const val STAGGERED_BY = 6.0

        /** Often enough to look continuous, seldom enough that a swathe is not rescanned every tick. */
        private const val LOOKS_EVERY = 4L

        /** A bound on the frontier, so a cave-in in a cavern wall cannot walk the whole cavern at once. */
        private const val MOST_AT_ONCE = 512

        /** How far the plan shape reaches, which is a crack's own length and wander. */
        private const val REACHES = 24

        /** How far it cuts up from where it began, and the most it cuts down — see [claimedAt]. */
        private const val DEEPENS = 12

        /** How far a block may stand off the trough's own wall, as a share of the crack's half-width. */
        private const val ROUGHNESS = 0.08

        /** A column the crack does not reach at all — see [Crack.centralityAt]. */
        private const val OUTSIDE_IT = 0.0

        /** At and above where it began, the whole swathe goes. */
        private const val AT_THE_TOP = 0
        private const val NOTHING_REQUIRED = 0.0
        private const val TWICE = 2.0
        private const val ONE = 1.0

        /** Quiet for this long with nothing left cracking means it has finished. */
        private const val SETTLES_AFTER = 200L

        /** Whatever happens, it is gone after two minutes. */
        private const val LIVES_FOR = 2400L

        private const val LEAVES_RUBBLE = 0.08f

        private const val NOT_STARTED = Long.MIN_VALUE

        /** Nothing has given way yet: later than any crack could have begun. */
        private const val NOT_YET = Long.MAX_VALUE

        /** What a summoned cave-in has instead of a seed, until its first tick draws one. */
        private const val NO_SHAPE = 0L
        private const val HALF = 0.5

        private const val SHAPE_KEY = "shape"
        private const val STARTED_KEY = "started_at"
    }
}
