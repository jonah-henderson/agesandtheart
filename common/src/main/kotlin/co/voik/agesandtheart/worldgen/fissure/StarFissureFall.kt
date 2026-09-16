package co.voik.agesandtheart.worldgen.fissure

import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3

/**
 * Falling out of an Age through a tear one block deep (design §7.8).
 *
 * **The ground stops holding you, and nothing else changes.** `Entity.noPhysics` is the whole mechanism:
 * `Entity.move` stops colliding, `Entity.isInWall` stops suffocating, and `Entity.isAffectedByBlocks` stops
 * every inside-block effect — so lava under a tear cannot burn somebody falling past it.
 * `ServerGamePacketListenerImpl.handleMovePlayer` reads the same flag and stops correcting the movement.
 *
 * **Gravity is left alone**, which is what makes it read as falling rather than as being moved: the drop
 * accelerates as any other does, and the tear overhead shrinks with it. Only the sideways part is taken
 * away, so the tear stays overhead and the way out stays in sight.
 *
 * **What is looked at is [co.voik.agesandtheart.client.StarFissureVeil]'s**, drawn client-side from the
 * same rules read here, so the two agree without anything being sent.
 */
object StarFissureFall {

    /**
     * Whether a tear has this player, which is what lets them through the ground under it.
     *
     * [alreadyFalling] is what separates the two questions this answers. Entering asks whether a tear is
     * *against the body*, so a tear in a wall beside a walkway is scenery; once falling, it asks only
     * whether the tear is still overhead, which it must keep answering or the ground would catch somebody
     * halfway down and set them inside it.
     */
    @JvmStatic
    fun takesHold(player: Player, alreadyFalling: Boolean): Boolean {
        if (player.isSpectator) return false
        if (player.level().dimension() == Level.OVERWORLD) return false
        if (alreadyFalling) return tearOfTheFall(player) != null
        if (player.onGround()) return false
        return tearAgainstTheBody(player) != null
    }

    /**
     * Takes the sideways part of a fall away, so the tear stays overhead.
     *
     * The drop itself is untouched: gravity is the whole of it, and pinning the fall to the tear's own
     * column is what keeps the opening in sight to fall away from.
     */
    @JvmStatic
    fun carry(player: Player) {
        if (!isFalling(player)) return
        val tear = tearOfTheFall(player) ?: return
        player.deltaMovement = Vec3(0.0, player.deltaMovement.y, 0.0)
        player.setPos(tear.x + HALF_A_BLOCK, player.y, tear.z + HALF_A_BLOCK)
    }

    /** Whether the fall is far enough along to be let go of — [FALL_DEPTH] under the tear it began at. */
    fun hasFallenFarEnough(player: Player): Boolean {
        if (!isFalling(player)) return false
        val tear = tearOfTheFall(player) ?: return true
        return player.y < tear.y - FALL_DEPTH
    }

    /**
     * Whether a fall is running at all — the cheap half of every question asked about one.
     *
     * [Player.noPhysics] is ours or nobody's: a spectator is excluded here, and nothing else in the game
     * sets it. So this needs no search, which is what lets the interaction gates ask it on every click.
     */
    @JvmStatic
    fun isFalling(player: Player): Boolean = player.noPhysics && !player.isSpectator

    /**
     * Whether the eyes have crossed into [tear], which is **the moment the view becomes the field's**.
     *
     * Until then the fall is watched from the Age, with the ground rising past; after it there is nothing
     * to see but the field and the opening overhead.
     */
    fun eyesInside(player: Player, tear: BlockPos): Boolean = player.eyeY < tear.y + A_BLOCK

    /**
     * The tear this fall is under — the first one at or above the feet, in the player's own column.
     *
     * Searched from the feet rather than the eyes so the same call answers on the way in, where the tear is
     * level with the body, and all the way down, where it is a long way overhead. The reach is a little
     * past [FALL_DEPTH], so the fall is always let go of before the tear is lost.
     */
    fun tearOfTheFall(player: Player): BlockPos? {
        val lowest = Mth.floor(player.y)
        for (y in lowest..lowest + REACHES_BACK) {
            val at = BlockPos(Mth.floor(player.x), y, Mth.floor(player.z))
            if (player.level().getBlockState(at).block is StarFissureBlock) return at
        }
        return null
    }

    /**
     * How wide the opening overhead is, as the block span of the tears joined to [tear] on its own layer.
     *
     * A tear is often one block and sometimes a spreading group of them, and what the veil needs is the
     * hole to leave in its lid. Taken as the span rather than the exact shape: an opening a block too
     * generous at a corner is not visible from under it, and a flood fill per frame would be.
     */
    fun openingAround(player: Player, tear: BlockPos): Opening {
        var leastX = tear.x
        var mostX = tear.x
        var leastZ = tear.z
        var mostZ = tear.z
        for (awayX in -SPREADS_OVER..SPREADS_OVER) {
            for (awayZ in -SPREADS_OVER..SPREADS_OVER) {
                val at = BlockPos(tear.x + awayX, tear.y, tear.z + awayZ)
                if (player.level().getBlockState(at).block !is StarFissureBlock) continue
                leastX = minOf(leastX, at.x)
                mostX = maxOf(mostX, at.x)
                leastZ = minOf(leastZ, at.z)
                mostZ = maxOf(mostZ, at.z)
            }
        }
        return Opening(leastX.toDouble(), mostX + A_BLOCK, leastZ.toDouble(), mostZ + A_BLOCK)
    }

    /** The hole in the veil's lid, in world coordinates. */
    data class Opening(val leastX: Double, val mostX: Double, val leastZ: Double, val mostZ: Double)

    /** A tear anywhere up the body, which is how one is entered. */
    private fun tearAgainstTheBody(player: Player): BlockPos? {
        val lowest = Mth.floor(player.y)
        val highest = Mth.floor(player.y + player.bbHeight)
        for (y in lowest..highest) {
            val at = BlockPos(Mth.floor(player.x), y, Mth.floor(player.z))
            if (player.level().getBlockState(at).block is StarFissureBlock) return at
        }
        return null
    }

    /**
     * Where a fall in progress is written on the player, so a disconnection does not strand them.
     *
     * **`noPhysics` is the only part of a fall that vanilla does not already save.** Where they are and how
     * fast they are going are both persisted with any entity, and everything else here is derived from the
     * blocks around them — so one flag is the whole of it, and a fall taken up again resumes at the speed
     * it left off at. Without it, logging back in put somebody inside the rock they were passing through
     * with physics switched back on.
     */
    const val SAVE_KEY = "agesandtheart:falling"

    /** How far under the tear the Age lets go, in blocks. */
    const val FALL_DEPTH = 50

    /** How far back up the fall looks for the tear it came through — past [FALL_DEPTH], never short of it. */
    private const val REACHES_BACK = FALL_DEPTH + 8

    /** How far a tear is followed sideways when measuring the opening. */
    private const val SPREADS_OVER = 8

    private const val HALF_A_BLOCK = 0.5
    private const val A_BLOCK = 1.0
}
