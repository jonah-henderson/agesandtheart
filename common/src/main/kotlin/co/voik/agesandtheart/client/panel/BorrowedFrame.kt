package co.voik.agesandtheart.client.panel

import com.mojang.blaze3d.ProjectionType
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.GpuBufferSlice
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
 * The three borrowed here — the projection, the camera in the "Globals" UBO, and the crosshair — are the
 * ones with a public setter and a getter to read the old value back. `link-panel-research.md` lists the
 * others and how each is dealt with instead.
 *
 * Restoring is not optional: a panel draws inside a screen's frame, so all three are read again later in
 * that same frame by the GUI, by the world behind it, and by the next tick.
 */
class BorrowedFrame private constructor(
    private val minecraft: Minecraft,
    private val outerProjection: GpuBufferSlice?,
    private val outerProjectionType: ProjectionType,
    private val outerGlobals: GpuBuffer?,
    private val outerShaderFog: GpuBufferSlice?,
    private val outerHitResult: HitResult?,
) : AutoCloseable {

    /** A null saved value is one nothing had set yet, which cannot be put back. */
    override fun close() {
        outerProjection?.let { RenderSystem.setProjectionMatrix(it, outerProjectionType) }
        outerGlobals?.let { RenderSystem.setGlobalSettingsUniform(it) }
        outerShaderFog?.let { RenderSystem.setShaderFog(it) }
        minecraft.hitResult = outerHitResult
    }

    companion object {

        private val projections by lazy { ProjectionMatrixBuffer("ages linking panel") }
        private val globals by lazy { GlobalSettingsUniform() }

        /**
         * Replaces all three with the panel's own.
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
            )

            // Every swap inside the guard: the loan is the only way any of them gets put back, and a throw
            // part-way through would otherwise leave the caller's `use` unentered with the projection
            // already replaced.
            try {
                RenderSystem.setProjectionMatrix(projections.getBuffer(projection), ProjectionType.PERSPECTIVE)

                val options = minecraft.gameRenderer.gameRenderState.optionsRenderState
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
            } catch (failure: Throwable) {
                loan.close()
                throw failure
            }

            return loan
        }
    }
}
