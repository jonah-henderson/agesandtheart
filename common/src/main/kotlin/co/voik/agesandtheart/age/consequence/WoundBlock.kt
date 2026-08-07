package co.voik.agesandtheart.age.consequence

import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.location
import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
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
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
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
 * blight — nothing spreads, nothing is on a timer, and nothing threatens what a player built. It is an
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
class WoundBlock(properties: Properties) : BaseEntityBlock(properties) {

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = WoundBlockEntity(pos, state)

    /** Nothing is drawn from a model: the flicker is a scale that changes every frame. */
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.INVISIBLE

    init {
        registerDefaultState(stateDefinition.any().setValue(SEALED, false))
    }

    override fun codec(): MapCodec<WoundBlock> = CODEC

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

    companion object {
        val CODEC: MapCodec<WoundBlock> = simpleCodec(::WoundBlock)

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

/**
 * A wound's presence in the world, and nothing else.
 *
 * **No state and no ticking.** It exists so a block entity renderer has something to hang the flicker on;
 * everything a wound *is* lives on the block and its state.
 *
 * What is drawn over it is [co.voik.agesandtheart.client.WoundRenderer]'s business entirely.
 */
class WoundBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(AgeContent.WOUND_ENTITY, pos, state)
