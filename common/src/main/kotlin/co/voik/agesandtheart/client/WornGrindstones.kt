package co.voik.agesandtheart.client

import co.voik.agesandtheart.ChunkBlockIndex
import co.voik.agesandtheart.station.GrindstoneWear
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.ChunkAccess

/**
 * Worn grindstones near the player, drawn with vanilla's mining cracks at the stage their wear has reached
 * ([GrindstoneWear.crackStageOf]), so the cracks follow the grindstone's own model.
 *
 * The cracks are `ClientLevel.destroyBlockProgress` entries, which the client drops after 400 ticks
 * without a refresh, so [tick] refreshes those in reach. Each grindstone's entry has a negative id of its
 * own, where a player's mining uses its entity id; a grindstone being mined shows whichever stage is
 * further on.
 */
object WornGrindstones {

    fun tick(minecraft: Minecraft) {
        val level = minecraft.level ?: return
        val player = minecraft.player ?: return
        if (level.gameTime % REFRESHED_EVERY != 0L) return
        worn.eachValueWithin(level, player.position(), REFRESHED_WITHIN) { at, stage, _ -> crack(level, at, stage) }
    }

    fun stocked(level: Level, chunk: ChunkAccess) = worn.stocked(level, chunk)

    fun emptied(level: Level, at: ChunkPos) = worn.emptied(level, at)

    /** A grindstone worn, broken or placed after its chunk arrived. */
    fun noticed(level: ClientLevel, at: BlockPos, was: BlockState, now: BlockState) {
        val stage = GrindstoneWear.crackStageOf(now)
        if (stage == GrindstoneWear.crackStageOf(was)) return
        if (stage == null) {
            worn.gone(level, at)
            level.destroyBlockProgress(crackIdFor(at), at, NO_CRACKS)
        } else {
            worn.arrived(level, at, stage)
            crack(level, at, stage)
        }
    }

    fun forget() = worn.forget()

    private fun crack(level: ClientLevel, at: BlockPos, stage: Int) = level.destroyBlockProgress(crackIdFor(at), at, stage)

    /**
     * Negative, so never an entity's id. Two grindstones share one only when a multiple of 256 blocks apart
     * across or 4096 up, far wider than [REFRESHED_WITHIN].
     */
    private fun crackIdFor(at: BlockPos): Int {
        val across = (at.x and LOW_EIGHT_BITS) or ((at.z and LOW_EIGHT_BITS) shl EIGHT_BITS)
        val packed = across or ((at.y and LOW_TWELVE_BITS) shl SIXTEEN_BITS)
        return -1 - packed
    }

    /** Read on the client thread only. */
    private val worn = ChunkBlockIndex(GrindstoneWear::crackStageOf)

    /** Well inside the 400 ticks an entry lasts, and often enough that a grindstone walked up to is cracked in time. */
    private const val REFRESHED_EVERY = 40L

    /** Past the 32 blocks vanilla draws cracks within, by more than a sprint covers between refreshes. */
    private const val REFRESHED_WITHIN = 64.0

    /** What `destroyBlockProgress` takes as removing an entry. */
    private const val NO_CRACKS = -1

    private const val EIGHT_BITS = 8
    private const val SIXTEEN_BITS = 16
    private const val LOW_EIGHT_BITS = 0xFF
    private const val LOW_TWELVE_BITS = 0xFFF
}
