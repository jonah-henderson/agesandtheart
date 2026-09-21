package co.voik.agesandtheart.client.panel

import com.mojang.blaze3d.ProjectionType
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.TextureFilteringMethod
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.GlobalSettingsUniform
import net.minecraft.client.renderer.ProjectionMatrixBuffer
import net.minecraft.world.phys.HitResult
import org.joml.Matrix4f

/**
 * The client state there is only one of, lent to a panel for one render and given back on [close].
 *
 * What is borrowed here — the projection, the camera in the "Globals" UBO, the shader fog, the crosshair
 * and smart culling — is what has a public setter and a getter to read the old value back.
 * `link-panel-research.md` lists the rest of the register and how each is dealt with instead.
 *
 * Restoring is not optional: a panel draws inside a screen's frame, so every one of these is read again
 * later in that same frame by the GUI, by the world behind it, or by the next tick.
 */
class BorrowedFrame private constructor(
    private val minecraft: Minecraft,
    private val outerProjection: GpuBufferSlice?,
    private val outerProjectionType: ProjectionType,
    private val outerGlobals: GpuBuffer?,
    private val outerShaderFog: GpuBufferSlice?,
    private val outerHitResult: HitResult?,
    private val outerSmartCull: Boolean,
) : AutoCloseable {

    /** A null saved value is one nothing had set yet, which cannot be put back. */
    override fun close() {
        outerProjection?.let { RenderSystem.setProjectionMatrix(it, outerProjectionType) }
        outerGlobals?.let { RenderSystem.setGlobalSettingsUniform(it) }
        outerShaderFog?.let { RenderSystem.setShaderFog(it) }
        minecraft.hitResult = outerHitResult
        minecraft.smartCull = outerSmartCull
    }

    companion object {

        private val projections by lazy { ProjectionMatrixBuffer("ages linking panel") }
        private val globals by lazy { GlobalSettingsUniform() }

        /**
         * Replaces each with the panel's own.
         *
         * The globals uniform holds the camera position that chunk terrain is drawn *relative to*, so a
         * panel that inherits the player's draws its sections that far from where it is looking. Only the
         * size, the camera and the Age's clock come from the panel; glint, blur and filtering are the
         * viewer's settings and mean the same thing in either world.
         *
         * `hitResult` is nulled rather than replaced: `extractLevel` reads it to decide whether to outline
         * a block, and `CollisionContext.of(camera.entity())` throws on an orbit camera's null entity.
         * Nothing in a panel is being pointed at, so returning early is the right answer.
         */
        fun lentTo(
            target: RenderTarget,
            camera: PanelCamera,
            level: ClientLevel,
            projection: Matrix4f,
            delta: DeltaTracker,
        ): BorrowedFrame {
            val minecraft = Minecraft.getInstance()
            // The shader fog is not set here but by `renderLevel`'s own main pass, which leaves it pointing
            // at the panel's fog buffer for the rest of the frame.
            val loan = BorrowedFrame(
                minecraft,
                RenderSystem.getProjectionMatrixBuffer(),
                RenderSystem.getProjectionType(),
                RenderSystem.getGlobalSettingsUniform(),
                RenderSystem.getShaderFog(),
                minecraft.hitResult,
                minecraft.smartCull,
            )

            // Every swap inside the guard: the loan is the only way any of them gets put back, and a throw
            // part-way through would otherwise leave the caller's `use` unentered with the projection
            // already replaced.
            try {
                RenderSystem.setProjectionMatrix(projections.getBuffer(projection), ProjectionType.PERSPECTIVE)

                val options = minecraft.gameRenderer.gameRenderState().optionsRenderState
                globals.update(
                    target.width,
                    target.height,
                    options.glintStrength,
                    level.gameTime,
                    delta,
                    options.menuBackgroundBlurriness,
                    camera.position(),
                    options.textureFiltering == TextureFilteringMethod.RGSS,
                )
                minecraft.hitResult = null

                // **Occlusion culling starves a panel rather than helping it.** The graph will not expand
                // through a section whose mesh is not compiled yet, and it is rebuilt from the camera's
                // section whenever the camera moves eight blocks — which an orbit does every second or so.
                // The frontier is reset faster than sections can compile and propagate, so it never leaves
                // the camera and the rest of the ring is held loaded and never drawn. Off, the walk expands
                // through everything in the frustum at once, which for a ring this small is what we wanted
                // anyway: the set is bounded by design, so there is nothing here worth occluding.
                minecraft.smartCull = false
            } catch (failure: Throwable) {
                loan.close()
                throw failure
            }

            return loan
        }
    }
}
