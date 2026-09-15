package co.voik.agesandtheart.content

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3

/**
 * A block that can be **set down where there is nothing to set it on** — sneak and use while looking at
 * open air, and it appears a few blocks in front of you (design §7.1.2).
 *
 * **Arc crystal, because arc crystal is what you took out of the sky.** The whole acquisition is a fight
 * with a thing that will not stay near the ground, so a block of it hanging unsupported is the material
 * saying what it is; and it hands a player the one capability the reward otherwise makes them earn the
 * hard way, which is somewhere to stand up there. A midair build stops needing a kilometre of scaffolding
 * from the ground, and that is a real capability rather than a convenience.
 *
 * **It costs the same block, and nothing else.** No charge, no fuel and no cooldown: the price is that
 * this is the material you had to fly, shoot and break for, so a bridge of it is expensive in the only
 * currency the material has.
 */
class MidairBlockItem(block: Block, properties: Properties) : BlockItem(block, properties) {

    /**
     * **Vanilla only calls this when nothing was targeted**, which is what makes the gesture unambiguous:
     * a block within reach goes to `useOn` and places the ordinary way, sneaking or not, so nothing about
     * building against a wall changes. Reaching this at all means the player was looking at open sky.
     *
     * Sneak is asked for anyway. Without it, every swing at the air with a stack in hand would drop a
     * block into it.
     */
    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        if (!player.isShiftKeyDown) return super.use(level, player, hand)
        val holding = player.getItemInHand(hand)
        val at = BlockPos.containing(player.eyePosition.add(player.lookAngle.scale(SET_DOWN_AT)))
        if (level.isOutsideBuildHeight(at) || !level.getBlockState(at).canBeReplaced()) {
            return InteractionResult.PASS
        }
        // **Asked here because nothing else will ask it.** The permission checks a normal placement gets
        // hang off the *position clicked*, and this gesture has none — `ServerPlayerGameMode.useItem` runs
        // with nothing targeted, so spawn protection, the world border and adventure mode would all be
        // walked straight past by a block that decides its own position.
        if (!player.mayBuild() || !player.mayUseItemAt(at, Direction.UP, holding)) {
            return InteractionResult.PASS
        }
        // **Placed through vanilla's own path rather than by writing the block in.** A synthesised hit
        // gives `BlockItem.place` everything it checks and everything it does afterwards — that the space
        // is unobstructed, the sound, the statistic, the stack shrinking, and both loaders' place events —
        // none of which is worth reimplementing to save building a context.
        val looking = BlockHitResult(Vec3.atCenterOf(at), Direction.UP, at, false)
        val placed = place(BlockPlaceContext(player, hand, holding, looking))
        if (placed.consumesAction() && level is ServerLevel) settled(level, at)
        return placed
    }

    /** A green flare where it appeared, since nothing else about a block arriving out of nowhere says so. */
    private fun settled(level: ServerLevel, at: BlockPos) {
        level.sendParticles(
            ChargedMetal.ARC_GREEN,
            at.x + HALF,
            at.y + HALF,
            at.z + HALF,
            SPARKS,
            SPREAD,
            SPREAD,
            SPREAD,
            DRIFT,
        )
    }

    private companion object {
        /**
         * How far in front of the eyes it lands.
         *
         * Inside a player's reach, so what was just placed can be broken again without having to get to
         * it — and near enough that looking a little down puts it at your feet, which is what bridging
         * out from a ledge wants.
         */
        private const val SET_DOWN_AT = 3.0

        private const val SPARKS = 12
        private const val SPREAD = 0.3
        private const val DRIFT = 0.01
        private const val HALF = 0.5
    }
}
