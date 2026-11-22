package co.voik.agesandtheart.client.mirror

import co.voik.agesandtheart.client.AgeRenderTypes.MATRICES_AND_PROJECTION
import co.voik.agesandtheart.compat.ONLY_VERTEX_BINDING
import co.voik.agesandtheart.location
import co.voik.agesandtheart.optics.AstriteMirrorBlock
import co.voik.agesandtheart.optics.AstriteMirrorBlockEntity
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.renderpearl.api.pipeline.BindGroupLayout
import com.mojang.renderpearl.api.pipeline.BlendFunction
import com.mojang.renderpearl.api.pipeline.CompareOp
import com.mojang.renderpearl.api.pipeline.ColorTargetState
import com.mojang.renderpearl.api.pipeline.DepthStencilState
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.pipeline.UniformType
import com.mojang.renderpearl.api.textures.FilterMode
import com.mojang.renderpearl.api.textures.GpuTexture
import com.mojang.renderpearl.api.textures.GpuTextureView
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.oit.OitPipelineSet
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.texture.AbstractTexture
import net.minecraft.core.Direction
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.world.phys.Vec3

class AstriteMirrorRenderState : BlockEntityRenderState() {
    var plane: MirrorPlane? = null
    var facing: Direction = Direction.NORTH
}

/**
 * The faint reflection in a mirror's polished astrite, where its plane is the one [MirrorReflection] drew this frame:
 * added over the block's own face at [REFLECTION_STRENGTH], as light off a polished stone is, rather than replacing it.
 * The face is laid in a grid, each point looked up where it falls in the reflection's texture, since a texture
 * stretched across one quad would bend under perspective.
 */
class AstriteMirrorRenderer : BlockEntityRenderer<AstriteMirrorBlockEntity, AstriteMirrorRenderState> {

    override fun createRenderState(): AstriteMirrorRenderState = AstriteMirrorRenderState()

    override fun extractRenderState(
        mirror: AstriteMirrorBlockEntity,
        state: AstriteMirrorRenderState,
        partialTicks: Float,
        cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        super.extractRenderState(mirror, state, partialTicks, cameraPosition, breakProgress)
        val facing = mirror.blockState.getValue(AstriteMirrorBlock.FACING)
        val plane = MirrorPlane(facing, AstriteMirrorBlock.glassMiddle(mirror.blockPos, facing))
        state.facing = facing
        state.plane = plane
        val isFacingTheEye = plane.distanceInFront(cameraPosition) > 0.0
        if (isFacingTheEye) MirrorReflection.seen(plane, cameraPosition.distanceTo(plane.point))
    }

    override fun submit(state: AstriteMirrorRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        if (MirrorReflection.isDrawing) return
        val drawn = MirrorReflection.drawnPlane ?: return
        if (state.plane?.key != drawn.key || MirrorReflection.view() == null) return
        MirrorTexture.register()
        val blockCorner = Vec3.atLowerCornerOf(state.blockPos)
        collector.submitCustomGeometry(poseStack, REFLECTION) { pose, buffer -> layTheGlass(state.facing, blockCorner, pose, buffer) }
    }

    /** The glass's front face, a hair proud of the model's, as a grid of quads in the block's own frame. */
    private fun layTheGlass(facing: Direction, blockCorner: Vec3, pose: PoseStack.Pose, buffer: VertexConsumer) {
        val (across, up) = acrossAndUp(facing)
        val outward = Vec3(facing.stepX.toDouble(), facing.stepY.toDouble(), facing.stepZ.toDouble())
        val middle = Vec3(HALF, HALF, HALF).add(outward.scale(-(HALF - AstriteMirrorBlock.THICKNESS - PROUD)))
        val origin = middle.subtract(across.scale(HALF)).subtract(up.scale(HALF))
        fun corner(column: Int, row: Int) {
            val local = origin.add(across.scale(column.toDouble() / GRID)).add(up.scale(row.toDouble() / GRID))
            val (u, v) = MirrorReflection.textureCoordinatesOf(blockCorner.add(local))
            buffer.addVertex(pose, local.x.toFloat(), local.y.toFloat(), local.z.toFloat()).setUv(u, v).setColor(TINT)
        }
        for (column in 0..<GRID) {
            for (row in 0..<GRID) {
                corner(column, row)
                corner(column + 1, row)
                corner(column + 1, row + 1)
                corner(column, row + 1)
            }
        }
    }

    private fun acrossAndUp(facing: Direction): Pair<Vec3, Vec3> = when (facing.axis) {
        Direction.Axis.X -> Vec3(0.0, 0.0, 1.0) to Vec3(0.0, 1.0, 0.0)
        Direction.Axis.Z -> Vec3(1.0, 0.0, 0.0) to Vec3(0.0, 1.0, 0.0)
        Direction.Axis.Y -> Vec3(1.0, 0.0, 0.0) to Vec3(0.0, 0.0, 1.0)
    }

    private companion object {
        const val HALF = 0.5
        const val GRID = 8

        /** How far in front of the model's glass the reflection is laid, so the two never fight. */
        const val PROUD = 0.002

        /** How much of the reflection's light the polish gives back, of 255. */
        const val REFLECTION_STRENGTH = 44
        val TINT: Int = ARGB.color(255, REFLECTION_STRENGTH, REFLECTION_STRENGTH, REFLECTION_STRENGTH)

        private val SHEEN: RenderPipeline.Snippet = RenderPipeline.builder(MATRICES_AND_PROJECTION)
            .withVertexShader("core/position_tex_color")
            .withFragmentShader("core/position_tex_color")
            .withBindGroupLayout(BindGroupLayout.builder().withUniform("Sampler0", UniformType.COMBINED_IMAGE_SAMPLER).build())
            .withVertexBinding(ONLY_VERTEX_BINDING, DefaultVertexFormat.POSITION_TEX_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .buildSnippet()

        /** The sorted pass a client with improved transparency draws blended light in, as `lightThroughFog` has. */
        @Suppress("DEPRECATION")
        private val SORTED_SHEEN: OitPipelineSet =
            OitPipelineSet.builder("astrite_mirror_reflection", RenderPipeline.builder(SHEEN).withShaderDefine("OIT_ADDITIVE")).build()

        val REFLECTION: RenderType = RenderType.create(
            "astrite_mirror_reflection",
            RenderSetup.builder(
                RenderPipeline.builder(SHEEN)
                    .withLocation("pipeline/astrite_mirror_reflection".location())
                    .withColorTargetState(ColorTargetState(BlendFunction.LIGHTNING))
                    .withCull(false)
                    // Tested as everything is (26.3's depth runs backwards) but never written, being light laid over a face.
                    .withDepthStencilState(DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
                    .build(),
            ).withTexture("Sampler0", MirrorTexture.LOCATION) { RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR) }
                .setOitPipelines(SORTED_SHEEN)
                .createRenderSetup(),
        )
    }
}

/** The reflection's colour texture under a name, so a render type can sample it as any texture. */
private object MirrorTexture : AbstractTexture() {
    val LOCATION: Identifier = "astrite_mirror/reflection".location()
    override fun getTextureView(): GpuTextureView = MirrorReflection.view() ?: error("$LOCATION was sampled before anything was drawn into it")

    override fun getTexture(): GpuTexture = getTextureView().texture()

    override fun close() = Unit

    /** On every draw rather than once: registering again is a map write that changes nothing, and outlasts a reload. */
    fun register() {
        Minecraft.getInstance().textureManager.register(LOCATION, this)
    }
}
