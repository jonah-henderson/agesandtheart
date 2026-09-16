package co.voik.agesandtheart.worldgen.fissure

import co.voik.agesandtheart.content.AgeContent
import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.InsideBlockEffectApplier
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Portal
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.TheEndPortalBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.portal.TeleportTransition
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * The stuff a star fissure is filled with: a hole in an Age you fall out of (design §7.8, §9 item 15).
 *
 * **The fall is the point** (Jonah, 2026-08-05). Mystcraft's fissure took you the instant you touched it;
 * this one is entered by falling *through* — you jump in, the starfield goes past, and a beat later you are
 * somewhere else. That beat is vanilla's own: `Portal.getPortalTransitionTime` is how long an entity must be
 * continuously inside before it fires, which is exactly what a column of these gives a falling player and
 * needed no machinery of ours.
 *
 * **One way, and it drops you into open air.** No return trip and nothing built at the far end — you arrive
 * a little above the world's spawn already falling, so the last thing the fissure does is the same thing it
 * started with. That is the escape hatch's whole shape: found rather than carried, and never a route back.
 */
open class StarFissureBlock(properties: Properties) : BaseEntityBlock(properties), Portal {

    override fun codec(): MapCodec<out StarFissureBlock> = CODEC

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
        if (!entity.canUsePortal(false)) return
        // **Whatever fell to get here, it did not fall.** A tear that runs the whole height of the world
        // (§5.3) is a hundred-odd blocks of shaft, so anything arriving at the bottom arrives at terminal
        // velocity and is killed by the floor a tick before the portal's beat is up — dying *inside* the
        // way out, which is the one thing this block exists to prevent. Zeroing it every tick inside also
        // covers the ordinary structural fissure, where a long drop in was survivable but expensive.
        entity.resetFallDistance()
        entity.setAsInsidePortal(this, pos)
    }

    /**
     * A little above the world's spawn, already falling.
     *
     * The gentle push down is the illusion's other half: arriving at rest reads as a teleport, where
     * arriving in the air still moving reads as having come *out* of somewhere.
     */
    override fun getPortalDestination(level: ServerLevel, entity: Entity, pos: BlockPos): TeleportTransition? {
        val home = level.server.overworld()
        val spawn = home.respawnData.pos()
        return TeleportTransition(
            home,
            Vec3(spawn.x + HALF_A_BLOCK, spawn.y + FALL_OUT_ABOVE, spawn.z + HALF_A_BLOCK),
            Vec3(0.0, -GENTLY_DOWN, 0.0),
            entity.yRot,
            entity.xRot,
            TeleportTransition.PLAY_PORTAL_SOUND,
        )
    }

    /**
     * How long you fall before the Age lets go — about a second, which is fifteen blocks of falling.
     *
     * **Only a player falls.** The beat is the whole point for somebody who jumped in, and it is a way to
     * die for everything else: a tear where bedrock used to be is one block deep, so a mob or a dropped item
     * spends the beat falling past it and out of the world. Vanilla's own portals answer nought for anything
     * but a player for the same reason.
     */
    override fun getPortalTransitionTime(level: ServerLevel, entity: Entity): Int =
        if (entity is Player) FALLING_FOR else AT_ONCE

    /** No swirl: the nether's confusion is a doorway's, and this is a hole in the ground. */
    override fun getLocalTransition(): Portal.Transition = Portal.Transition.NONE

    companion object {
        val CODEC: MapCodec<StarFissureBlock> = simpleCodec(::StarFissureBlock)

        private const val FALLING_FOR = 20

        /** What everything but a player waits, so nothing falls through the one block it has to land on. */
        private const val AT_ONCE = 0
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
