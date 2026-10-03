package co.voik.agesandtheart.worldgen.fissure

import co.voik.agesandtheart.advancement.AgeTriggers
import co.voik.agesandtheart.content.AgeContent
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.InsideBlockEffectApplier
import net.minecraft.world.entity.item.FallingBlockEntity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.TheEndPortalBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * The stuff a star fissure is filled with: a hole in an Age you fall out of (design §7.8, §9 item 15).
 *
 * **The fall is the point** (Jonah, 2026-08-05). Mystcraft's fissure took you the instant you touched it;
 * this one is entered by falling *through* — you jump in, the starfield goes past, and a moment later you
 * are somewhere else.
 *
 * **Not a `Portal`, and that is deliberate.** It was one, and vanilla's portal machinery cost more than it
 * gave: `handlePortal` has three separate ways to decline a destination, and a declined one is
 * indistinguishable from a fissure that does nothing — which is exactly how a tear placed by hand came to
 * swallow a player and then set them down in the overworld having never moved. The two hops are ours now,
 * and each is a single call on the tick an entity touches the block.
 *
 * **A player falls through it and is held inside it** ([StarFissureFall]), which takes no teleport at all:
 * the ground under the tear stops holding them, they drop until their eyes are inside the field, and there
 * they stay while it fills the view. [TheFall] sends them on when the eyes come out of the bottom.
 *
 * **Everything else goes straight home.** The fall is worth having for somebody who jumped in; for a mob or
 * a dropped item it is a way to fall past a one-block tear and out of the world.
 *
 * **One way, and it drops you into open air.** No return trip and nothing built at the far end — you arrive
 * a little above the world's spawn already falling, so the last thing the fissure does is the same thing it
 * started with. That is the escape hatch's whole shape: found rather than carried, and never a route back.
 */
open class StarFissureBlock(properties: Properties) : BaseEntityBlock(properties) {


    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = StarFissureBlockEntity(pos, state)

    /** Drawn by its block entity — the starfield is a renderer, not a texture. */
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.INVISIBLE

    /** Nothing to stand on: a fissure you could walk over would not be one. */
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        net.minecraft.world.phys.shapes.Shapes.empty()

    /**
     * The whole cube counts as inside, so a player falling fast still registers on every block they pass.
     *
     * Vanilla's end portal uses a flat slab because you walk onto it; this is fallen through at speed, and a
     * thin shape would let a tick's movement skip between two of them.
     */
    override fun getEntityInsideCollisionShape(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        entity: Entity,
    ): VoxelShape = net.minecraft.world.phys.shapes.Shapes.block()

    override fun entityInside(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        entity: Entity,
        effects: InsideBlockEffectApplier,
        overlapping: Boolean,
    ) {
        if (level !is ServerLevel) return
        // **Whatever fell to get here, it did not fall.** A tear that runs the whole height of the world
        // (§5.3) is a hundred-odd blocks of shaft, so anything arriving at the bottom arrives at terminal
        // velocity and would be killed by the floor — dying *inside* the way out, which is the one thing
        // this block exists to prevent.
        entity.resetFallDistance()
        // A player is [StarFissureFall]'s from here: it carries them through the ground under the tear and
        // holds them in the field, and nothing of it is a teleport. Only the overworld has no fall to give.
        if (entity is ServerPlayer && level.dimension() != Level.OVERWORLD) return
        if (entity is FallingBlockEntity) return RubbleArrivals.deliver(level.server.overworld(), entity)
        if (entity is ItemEntity) answerTheThrower(entity)
        // A mob, an item, or a player where there is no fall to be had: straight home, at once.
        sendHome(level, entity)
    }

    /** A linking or descriptive book somebody threw in tells the advancements whose it was. */
    private fun answerTheThrower(item: ItemEntity) {
        val isABook = item.item.`is`(AgeContent.LINKING_BOOK) || item.item.`is`(AgeContent.DESCRIPTIVE_BOOK)
        val thrower = item.owner as? ServerPlayer
        if (isABook && thrower != null) AgeTriggers.GAVE_A_BOOK_TO_A_FISSURE.trigger(thrower)
    }

    /**
     * A little above the world's spawn, already falling — **the way out**.
     *
     * The push down is the illusion's other half: arriving at rest reads as a teleport, where arriving in
     * the air still moving reads as having come *out* of somewhere.
     */
    private fun sendHome(level: ServerLevel, entity: Entity) {
        val home = level.server.overworld()
        val spawn = home.levelData.respawnData.pos()
        entity.teleportTo(
            home,
            spawn.x + HALF_A_BLOCK,
            spawn.y + FALL_OUT_ABOVE,
            spawn.z + HALF_A_BLOCK,
            emptySet(),
            entity.yRot,
            entity.xRot,
            false,
        )
        entity.deltaMovement = Vec3(0.0, -GENTLY_DOWN, 0.0)
    }

    /**
     * How long you fall before the Age lets go — about a second, which is fifteen blocks of falling.
     *
     * **Only a player falls.** The beat is the whole point for somebody who jumped in, and it is a way to
     * die for everything else: a tear where bedrock used to be is one block deep, so a mob or a dropped item
     * spends the beat falling past it and out of the world. Vanilla's own portals answer nought for anything
     * but a player for the same reason.
     */

    /** No swirl: the nether's confusion is a doorway's, and this is a hole in the ground. */

    companion object {

        private const val FALL_OUT_ABOVE = 8.0
        private const val GENTLY_DOWN = 0.2
        private const val HALF_A_BLOCK = 0.5
    }
}

/**
 * Nothing of its own — it exists so vanilla's end-portal renderer has something to draw.
 *
 * Extending `TheEndPortalBlockEntity` rather than copying it is what buys the **actual** starfield: the
 * renderer is typed to that class and takes ours without knowing the difference, so the shader, the
 * transformation and the depth layers are vanilla's rather than an imitation of them.
 */
class StarFissureBlockEntity(pos: BlockPos, state: BlockState) :
    TheEndPortalBlockEntity(AgeContent.STAR_FISSURE_ENTITY, pos, state) {

    /**
     * **Every face, including the buried ones.**
     *
     * Vanilla answers `axis == Y` here, because an end portal is one layer on a floor and only ever seen
     * from above. Stack that and the sides are missing.
     *
     * Drawing only the shell was the obvious economy and it was wrong (Jonah, 2026-08-05, walked): a face
     * between two fissure blocks faces *away* from someone falling between them, so from inside the shaft
     * you looked straight out through the rock at the daylit world. Being inside a star fissure has to look
     * like being inside one, and the overdraw is a handful of quads in a hole nobody stands in for long.
     */
    override fun shouldRenderFace(direction: Direction): Boolean = true
}
