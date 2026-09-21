package co.voik.agesandtheart.worldgen.fissure

import co.voik.agesandtheart.compat.hasChunkAtColumn
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
     * [alreadyFalling] is what separates the two questions this answers. Entering asks whether a tear is in
     * the way down, so a tear in a wall beside a walkway is scenery; once falling, it asks only whether the
     * tear is still overhead, which it must keep answering or the ground would catch somebody halfway down
     * and set them inside it.
     */
    @JvmStatic
    fun takesHold(player: Player, alreadyFalling: Boolean): Boolean {
        if (player.isSpectator) return false
        if (player.level().dimension() == Level.OVERWORLD) return false
        if (alreadyFalling) return stillUnderTheTear(player)
        return tearInTheWayDown(player) != null
    }

    /**
     * Whether the tear a fall began at is still there — and yes wherever the answer cannot be had.
     *
     * A client that has just joined holds no chunks yet, and a column it cannot read answers air. Letting
     * that end a fall would drop somebody back into physics inside the rock they were passing through, so
     * an unloaded column keeps its tear rather than losing it. The server, whose chunks are held by the
     * player standing in them, never takes this branch.
     */
    private fun stillUnderTheTear(player: Player): Boolean {
        val cannotSeeTheColumn = !player.level().hasChunkAtColumn(player.blockX, player.blockZ)
        return cannotSeeTheColumn || tearOfTheFall(player) != null
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

    /** Whether the fall is far enough along to be let go of — nearly into the void under the Age. */
    fun hasFallenFarEnough(player: Player): Boolean {
        if (!isFalling(player)) return false
        if (tearOfTheFall(player) == null) return true
        return player.y < letsGoAt(player.level())
    }

    /**
     * The height the Age lets go at: as deep as a fall can go before vanilla's void damage, which
     * `Entity.checkBelowWorld` starts [VOID_BELOW_THE_FLOOR] under the world's floor.
     */
    fun letsGoAt(level: Level): Int = level.minY - VOID_BELOW_THE_FLOOR + CLEAR_OF_THE_VOID

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
     * The tear this fall is under — the lowest one in the player's own column.
     *
     * Searched from the feet rather than the eyes so the same call answers on the way in, where the tear is
     * level with the body, and all the way down, where it is a long way overhead. The search stops at the
     * first tear it meets, so it costs the distance fallen and no more.
     */
    fun tearOfTheFall(player: Player): BlockPos? =
        tearInTheColumn(player, upTo = player.level().maxY)

    /**
     * Which columns overhead are open, as the tears standing near [tear] — the hole to leave in the lid.
     *
     * **The tear's own columns, not the box around them.** A crack fills its bounding rectangle no better
     * than a spreading tear's band does, and what a lid with a rectangle cut out of it leaves overhead is
     * the ground the tear did not take — at the floor of an Age, a chunk of bedrock where the field should
     * be.
     *
     * **One layer is enough to look at**, because every tear is cut at one: a collapse tear stands at the
     * Age's own floor and a natural fissure is levelled across its whole crack ([StarFissurePiece]).
     */
    fun openingAround(player: Player, tear: BlockPos): Opening {
        val torn = BooleanArray(SIDE * SIDE)
        val cursor = BlockPos.MutableBlockPos()
        for (awayX in -SPREADS_OVER..SPREADS_OVER) {
            for (awayZ in -SPREADS_OVER..SPREADS_OVER) {
                cursor.set(tear.x + awayX, tear.y, tear.z + awayZ)
                val column = (awayX + SPREADS_OVER) * SIDE + (awayZ + SPREADS_OVER)
                torn[column] = player.level().getBlockState(cursor).block is StarFissureBlock
            }
        }
        return Opening(tear.x - SPREADS_OVER, tear.z - SPREADS_OVER, SIDE, torn)
    }

    /**
     * The hole in the veil's lid: which of the columns around a tear are themselves torn, in blocks.
     *
     * Everything outside the square this was searched over is lid, which is what [leastX], [leastZ] and
     * [side] are for — the veil frames that square once and only goes column by column inside it.
     */
    class Opening(val leastX: Int, val leastZ: Int, val side: Int, private val torn: BooleanArray) {

        val mostX: Int get() = leastX + side - 1
        val mostZ: Int get() = leastZ + side - 1

        fun isTorn(x: Int, z: Int): Boolean {
            val alongX = x - leastX
            val alongZ = z - leastZ
            if (alongX !in 0..<side || alongZ !in 0..<side) return false
            return torn[alongX * side + alongZ]
        }
    }

    /**
     * A tear anywhere the body will have passed through by the end of this tick — **how one is entered**.
     *
     * **Swept rather than a snapshot**, because a tear one block deep standing on solid ground is stepped
     * clean over by a fall at speed: the tick before, the tear is still under the feet; the tick after, what
     * is under the tear has already caught them, and a body standing on the ground was never asked. A tear
     * at an Age's floor (`Collapse`) is the bottom of a hundred blocks of cleared shaft, so it is *always*
     * arrived at that way.
     */
    private fun tearInTheWayDown(player: Player): BlockPos? =
        tearInTheColumn(player, upTo = Mth.floor(player.y + player.bbHeight))

    /**
     * The lowest tear in the player's own column, from where this tick's fall will put them up to [upTo].
     *
     * **It reaches under the feet, and every caller wants it to.** A tear caught on the way in is often
     * still below the body for the tick it is caught on, and a fall whose tear cannot be found is a fall
     * that has come out of the bottom — [TheFall] would put them back in the overworld having never moved,
     * and [carry] would let them drift off the tear's column on the way past it.
     */
    private fun tearInTheColumn(player: Player, upTo: Int): BlockPos? {
        val lowest = Mth.floor(player.y + thisTicksDrop(player))
        val cursor = BlockPos.MutableBlockPos()
        for (y in lowest..upTo) {
            cursor.set(Mth.floor(player.x), y, Mth.floor(player.z))
            if (player.level().getBlockState(cursor).block is StarFissureBlock) return cursor.immutable()
        }
        return null
    }

    /** How far this tick's fall carries them, read at the head of it, before gravity has been added. */
    private fun thisTicksDrop(player: Player): Double = minOf(0.0, player.deltaMovement.y) - GRAVITY

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

    /** How far under the world's floor `Entity.checkBelowWorld` starts hurting, in blocks. */
    private const val VOID_BELOW_THE_FLOOR = 64

    /**
     * How far above that the Age lets go — more than a tick's fall at terminal speed, about four blocks,
     * since the let-go runs after the entities have ticked.
     */
    private const val CLEAR_OF_THE_VOID = 8

    /** How far a tear is followed sideways when measuring the opening, and the square that makes. */
    private const val SPREADS_OVER = 8
    private const val SIDE = SPREADS_OVER * 2 + 1

    /** What a tick of falling adds, which the tick has not added yet when a fall is looked for. */
    private const val GRAVITY = 0.08

    private const val HALF_A_BLOCK = 0.5
    private const val A_BLOCK = 1.0
}
