package co.voik.agesandtheart.compat

import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3

/**
 * Vanilla API that moved between Minecraft versions, restated once so the callers do not have to move
 * with it.
 *
 * **Only what can be restated belongs here** — a rename, a method that changed shape, a constant that
 * found a new home. Where a version genuinely does something differently there is nothing to restate, and
 * that difference belongs behind an interface with an implementation per version rather than here.
 *
 * Keeping the two apart is the point: this file should stay small and boring, and anything that wants to
 * grow a branch inside it is telling you it is the other kind.
 */

/**
 * Where a block's middle is. 26.2 took `center` off `BlockPos`, leaving `Vec3.atCenterOf`, which says the
 * same thing at every one of the call sites that used to read `at.center`.
 */
val BlockPos.center: Vec3 get() = Vec3.atCenterOf(this)

/**
 * The one vertex buffer our pipelines bind. 26.2 numbers vertex bindings, where `withVertexFormat` took
 * the single format it assumed — so everything here binds slot zero and says so by name.
 */
const val ONLY_VERTEX_BINDING = 0

/**
 * Whether the chunk holding this column is loaded.
 *
 * 26.2 deprecated the whole `hasChunkAt` family on `LevelReader` — the `BlockPos` form, the `x, z` form
 * and the `hasChunk` beneath them — leaving `ChunkSource.hasChunk`, which is what they all called.
 *
 * **`Level.isLoaded` is the near miss and is deliberately not used.** It is not deprecated and looks like
 * the intended replacement, but it tests `isInValidBounds` first, so it answers *false* for a position
 * outside build height whether or not the chunk is there. Every caller here is asking a flat question
 * about a column and hands over whatever Y it happened to have — and `StarFissureFall` asks it of a
 * player falling through a tear, whose Y is precisely the thing that leaves the world.
 */
fun Level.hasChunkAtColumn(blockX: Int, blockZ: Int): Boolean =
    chunkSource.hasChunk(SectionPos.blockToSectionCoord(blockX), SectionPos.blockToSectionCoord(blockZ))
