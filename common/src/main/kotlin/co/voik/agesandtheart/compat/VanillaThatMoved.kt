package co.voik.agesandtheart.compat

import net.minecraft.core.BlockPos
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
