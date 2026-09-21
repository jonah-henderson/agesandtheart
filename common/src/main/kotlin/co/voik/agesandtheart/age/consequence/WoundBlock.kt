package co.voik.agesandtheart.age.consequence

import co.voik.agesandtheart.location
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.registries.Registries
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.TagKey
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.Entity
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ExperienceOrb
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.redstone.Orientation
import net.minecraft.world.entity.InsideBlockEffectApplier
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BooleanProperty

/**
 * A tear in spacetime, one metre across — the primary register of an Age at odds with itself (design §5.1).
 *
 * The reference is the Riven remake: a hole discovered, later found crudely boxed up by Gehn, the patch
 * legible *as* a patch and hiding the wrongness without touching it. So this is not damage and not a
 * plague — nothing spreads, nothing is on a timer, and nothing threatens what a player built. It is an
 * **absence** that makes a place dreadful, and the only thing anyone can do about it is wall it in.
 *
 * **Unbreakable, and that is the mechanic rather than an obstacle.** "Containable, not curable": the true
 * repair is rewriting the book (§6), which is what a competent Writer does and what Gehn would not.
 *
 * **But sealing it quiets it** (Jonah, 2026-08-07), and only with materials equal to the job — [SEALS],
 * shipped with obsidian and waiting for nara. A wound boxed in on all six sides stops corrupting what is
 * around it and keeps hurting anything that reaches it, which is Gehn's patch made mechanical: the
 * wrongness is contained, not gone.
 */
class WoundBlock(properties: Properties) : Block(properties) {

    /** Nothing is drawn from a model: the flicker is a scale that changes every frame. */
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.INVISIBLE

    init {
        registerDefaultState(stateDefinition.any().setValue(SEALED, false))
    }

    /**
     * Telling [Wounds] where it is, so nothing has to search the world for black blocks.
     *
     * **A block hook rather than a block entity's**, which is the whole of what dropping the entity cost.
     * A wound arriving with its chunk is found by [Wounds.stocked]; this catches the other way in — one
     * torn open at runtime, and the block update that carries it to the client.
     *
     * Both sides, because both have a question that would otherwise be a search: the client draws the
     * corruption gradient out of the index and the server decides where the Age is dangerous ([Hostility]).
     */
    override fun onPlace(state: BlockState, level: Level, pos: BlockPos, oldState: BlockState, movedByPiston: Boolean) {
        super.onPlace(state, level, pos, oldState, movedByPiston)
        Wounds.arrived(level, pos)
    }

    /**
     * And as it goes, which only the Age itself can arrange — a wound is unbreakable, so nothing a player
     * does reaches here. Guarded on the block actually changing, since a *seal* rewrites the state through
     * this same path and the wound is still very much there.
     */
    override fun affectNeighborsAfterRemoval(state: BlockState, level: ServerLevel, pos: BlockPos, movedByPiston: Boolean) {
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston)
        if (!level.getBlockState(pos).`is`(this)) Wounds.gone(level, pos)
    }


    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(SEALED)
    }

    /**
     * What happens to whatever reaches it: the living are hurt, and **anything dropped in is gone.**
     *
     * A hole in a space that cannot sustain itself does not hold what falls into it, so an item is
     * discarded outright rather than damaged or dropped elsewhere — no death drop to recover, no
     * despawn timer, nothing on the floor underneath. It is the cheapest possible statement of what the
     * block *is*, and it makes a wound something to be careful near rather than only something to avoid
     * standing in (Jonah, 2026-08-07).
     *
     * A sealed wound is *contained*, never healed — the patch stops the corruption reaching outward and
     * changes nothing about the tear itself, so anybody who opens the box still meets what is inside.
     */
    override fun entityInside(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        entity: Entity,
        effects: InsideBlockEffectApplier,
        overlapping: Boolean,
    ) {
        val server = level as? ServerLevel ?: return
        when (entity) {
            // `discard` rather than `kill`: killing an item entity drops nothing but does fire death
            // handling, and what is wanted here is for the thing to have never arrived.
            is ItemEntity -> entity.discard()
            is ExperienceOrb -> entity.discard()
            is LivingEntity -> entity.hurtServer(server, level.damageSources().magic(), TOUCH_DAMAGE)
        }
    }

    /**
     * The seal follows the walls, so boxing one in takes effect as the last block goes down.
     *
     * Vanilla's own seam and the reason this is a state rather than a survey: a neighbour change is
     * exactly when the answer can have changed, and asking six blocks on every read would be six lookups
     * per query from anything wondering what an area is like.
     */
    override fun neighborChanged(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        neighbour: Block,
        orientation: Orientation?,
        movedByPiston: Boolean,
    ) {
        if (level.isClientSide) return
        val sealed = sealedAt(level, pos)
        if (state.getValue(SEALED) != sealed) level.setBlockAndUpdate(pos, state.setValue(SEALED, sealed))
    }

    /**
     * The sound of it, and the only thing that says a wound is near before it is seen.
     *
     * Air going *in* rather than anything coming out, because that is what a hole in a space that cannot
     * sustain itself would do — and it is the one cue that works through a wall while someone is deciding
     * where to build.
     */
    override fun animateTick(state: BlockState, level: Level, pos: BlockPos, random: RandomSource) {
        drawSpecksIn(level, pos, random)
        if (random.nextInt(BREATHES_IN) != 0) return
        level.playLocalSound(
            pos.x + HALF,
            pos.y + HALF,
            pos.z + HALF,
            SoundEvents.PORTAL_AMBIENT,
            SoundSource.BLOCKS,
            VOLUME,
            // Well below its natural pitch, so it reads as suction rather than as an end portal.
            LOW,
            false,
        )
    }

    /**
     * Specks of nothing spiralling in, the way matter falls into a hole (Jonah, 2026-08-07).
     *
     * **The stream traces the path; no single speck travels it.** A dust particle has a fixed life and
     * vanilla's own drag, and `addParticle` sets a velocity once and never again — so a speck launched from
     * the outside fades long before it arrives, which is exactly what the walk saw. Instead one is spawned
     * at a random point *along* the spiral each time, so at any instant the arm is drawn end to end by
     * specks each of which only has to cover its own short segment.
     *
     * **It winds and it accelerates.** The angle advances as the radius falls, so the path curves rather
     * than pointing straight in; and both the inward and the tangential speed rise as the centre nears, so
     * the arm is slow and wide at its edge and whips at its throat. Falling toward the middle in height as
     * well, or it would read as a flat disc drawn around the block rather than as something being consumed.
     */
    private fun drawSpecksIn(level: Level, pos: BlockPos, random: RandomSource) {
        repeat(SPECKS_PER_TICK) {
            // Along the arm rather than at its mouth: the whole spiral is drawn every tick.
            val alongTheArm = random.nextDouble()
            val radius = DRAWN_FROM * alongTheArm
            if (radius < TOO_CLOSE) return@repeat
            // The angle runs with the radius, which is what makes it a spiral rather than a spoke. The
            // random start is per speck, so the arm is a cloud of them and not one drawn line.
            val angle = random.nextDouble() * TURN + WINDING * (DRAWN_FROM - radius)
            val height = (random.nextDouble() - HALF) * DRAWN_FROM * alongTheArm

            val atX = kotlin.math.cos(angle) * radius
            val atZ = kotlin.math.sin(angle) * radius

            // Faster the nearer the throat, so the arm accelerates instead of drifting uniformly.
            val haste = FASTEST - (FASTEST - SLOWEST) * alongTheArm
            // Inward, plus the tangent that keeps it turning as it falls.
            val towardX = -atX / radius * haste
            val towardZ = -atZ / radius * haste
            val aroundX = -atZ / radius * haste * SWIRL
            val aroundZ = atX / radius * haste * SWIRL

            level.addParticle(
                SPECK,
                pos.x + HALF + atX,
                pos.y + HALF + height,
                pos.z + HALF + atZ,
                towardX + aroundX,
                -height * haste,
                towardZ + aroundZ,
            )
        }
    }

    companion object {

        /** A speck of the same absence the block is: black, and small enough to read as a mote. */
        private val SPECK = DustParticleOptions(0x000000, 0.4f)

        /**
         * How many are drawn per client tick.
         *
         * Higher than it would need to be if one speck flew the whole path, because each is now a short
         * segment of an arm that has to look continuous.
         */
        private const val SPECKS_PER_TICK = 8

        /** How far out the arm reaches, in blocks. */
        private const val DRAWN_FROM = 2.5

        /** A whole turn, for the random starting angle. */
        private const val TURN = Math.PI * 2

        /** How much the arm winds over its length, in radians per block — about a turn and a half. */
        private const val WINDING = 3.8

        /** How fast a speck moves at the throat and at the rim, in blocks per tick. */
        private const val FASTEST = 0.34
        private const val SLOWEST = 0.05

        /** How much of the motion is *around* rather than *in*. Enough to curve, not enough to orbit. */
        private const val SWIRL = 0.9

        /** Below this the point is too near the middle for its direction to mean anything. */
        private const val TOO_CLOSE = 0.05

        /**
         * Whether this wound is boxed in on all six sides by something equal to the job.
         *
         * In the block state rather than recomputed, so the *corruption* — which is read far from the
         * block, by anything asking what an area is like — costs one state lookup rather than six.
         */
        val SEALED: BooleanProperty = BooleanProperty.create("sealed")

        /**
         * What may seal a wound. Shipped with obsidian; **nara belongs here** and does not exist yet
         * (§7.1.2), so the tag is the seam that lets it arrive without this class changing.
         *
         * A tag rather than a list, so a pack can decide what counts as equal to the job.
         */
        val SEALS: TagKey<Block> = TagKey.create(Registries.BLOCK, "seals_wounds".location())

        /** Enough to be a mistake worth not repeating, and far short of a death sentence. */
        private const val TOUCH_DAMAGE = 4.0f

        /** One in this many client ticks draws the breath, so it is intermittent rather than a drone. */
        private const val BREATHES_IN = 40

        private const val VOLUME = 0.6f
        private const val LOW = 0.35f
        private const val HALF = 0.5

        /** Whether every side of [pos] is something that may seal a wound. */
        fun sealedAt(level: BlockGetter, pos: BlockPos): Boolean =
            Direction.entries.all { side -> level.getBlockState(pos.relative(side)).`is`(SEALS) }
    }
}

