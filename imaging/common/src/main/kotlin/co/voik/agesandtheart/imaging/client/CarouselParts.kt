package co.voik.agesandtheart.imaging.client

import co.voik.agesandtheart.imaging.Imaging
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.ResourceManagerReloadListener
import org.joml.Vector3f

/**
 * The slide carousel's moving parts, as `scripts/imaging/slide_carousel_models.py` wrote them from Jonah's Blockbench
 * project (`carousel/parts.json`): each part's quads in the model's own pixels, and the pivots they turn about. Read on
 * every resource reload, so a resource pack may redraw them.
 */
object CarouselParts : ResourceManagerReloadListener {

    val ID: Identifier = Imaging.id("carousel_parts")
    private val FILE: Identifier = Imaging.id("carousel/parts.json")

    /** One face: its texture, the way it faces, and its four corners as x, y, z, u, v. */
    class Quad(val texture: Identifier, val normal: Vector3f, val corners: FloatArray) {
        /** Facing the front or the back, which a slide's picture faces are. */
        val looksAlongTheBeam: Boolean get() = kotlin.math.abs(normal.z) > ALONG_THE_BEAM
    }

    /** What the file says: each part's quads by name, the pivots, and where light passes through. */
    class Parts(
        val quads: Map<String, List<Quad>>,
        val axle: Vector3f,
        val hookPivot: Vector3f,
        val slidePivot: Vector3f,
        val lensLow: Vector3f,
        val lensHigh: Vector3f,
        val heldSlideLow: Vector3f,
        val heldSlideHigh: Vector3f,
    )

    var parts: Parts? = null
        private set

    override fun onResourceManagerReload(resourceManager: ResourceManager) {
        parts = runCatching {
            resourceManager.openAsReader(FILE).use { reader -> read(JsonParser.parseReader(reader).asJsonObject) }
        }.onFailure { Imaging.LOG.warn("The slide carousel's moving parts could not be read: {}", it.message) }.getOrNull()
    }

    /**
     * Draws [part] under [poseStack] in the model's pixels, lit by [light], with [picture] across its two faces that look
     * along the projector's beam where it is a slide that has one. A part that is a [decal] lying on another is drawn
     * nudged toward the eye, so it neither fights the face under it nor has to hover visibly above it.
     */
    fun draw(part: String, poseStack: PoseStack, collector: SubmitNodeCollector, light: Int, picture: Identifier? = null, decal: Boolean = false) {
        val quads = parts?.quads?.get(part) ?: return
        val (faces, rest) = if (picture == null) emptyList<Quad>() to quads else quads.partition { it.looksAlongTheBeam }
        if (picture != null && faces.isNotEmpty()) drawPicture(faces, picture, poseStack, collector, light)
        for ((texture, sameTexture) in rest.groupBy { it.texture }) {
            // Culled, so a flat plate's two faces in one plane are never both drawn; a decal has only the face that shows.
            val drawnAs = if (decal) RenderTypes.entityCutoutZOffset(texture, true) else RenderTypes.entityCutoutCull(texture)
            collector.submitCustomGeometry(poseStack, drawnAs) { pose, buffer ->
                for (quad in sameTexture) {
                    for (corner in 0..<CORNERS) {
                        val at = corner * FLOATS_A_CORNER
                        buffer.addVertex(pose, quad.corners[at], quad.corners[at + 1], quad.corners[at + 2])
                            .setColor(WHITE)
                            .setUv(quad.corners[at + 3], quad.corners[at + 4])
                            .setOverlay(OverlayTexture.NO_OVERLAY)
                            .setLight(light)
                            .setNormal(pose, quad.normal.x, quad.normal.y, quad.normal.z)
                    }
                }
            }
        }
    }

    /**
     * [picture] laid across [faces], its top at their top. The slide is held up turned half round, so the picture is laid
     * upside down in its slot, as a slide goes in a real projector, and reads true before the lens.
     */
    private fun drawPicture(faces: List<Quad>, picture: Identifier, poseStack: PoseStack, collector: SubmitNodeCollector, light: Int) {
        val xs = faces.flatMap { quad -> (0..<CORNERS).map { quad.corners[it * FLOATS_A_CORNER] } }
        val ys = faces.flatMap { quad -> (0..<CORNERS).map { quad.corners[it * FLOATS_A_CORNER + 1] } }
        val (left, right) = xs.min() to xs.max()
        val (bottom, top) = ys.min() to ys.max()
        collector.submitCustomGeometry(poseStack, RenderTypes.entitySolid(picture)) { pose, buffer ->
            for (quad in faces) {
                for (corner in 0..<CORNERS) {
                    val at = corner * FLOATS_A_CORNER
                    val x = quad.corners[at]
                    val y = quad.corners[at + 1]
                    buffer.addVertex(pose, x, y, quad.corners[at + 2])
                        .setColor(WHITE)
                        .setUv((x - left) / (right - left), (y - bottom) / (top - bottom))
                        .setOverlay(OverlayTexture.NO_OVERLAY)
                        .setLight(light)
                        .setNormal(pose, quad.normal.x, quad.normal.y, quad.normal.z)
                }
            }
        }
    }

    private fun read(file: JsonObject): Parts {
        val pivots = file.getAsJsonObject("pivots")
        val light = file.getAsJsonObject("light")
        val quads = file.getAsJsonObject("parts").entrySet().associate { (name, list) ->
            name to list.asJsonArray.map { quadOf(it.asJsonObject) }
        }
        return Parts(
            quads,
            vectorOf(pivots.getAsJsonArray("axle")),
            vectorOf(pivots.getAsJsonArray("hook")),
            vectorOf(pivots.getAsJsonArray("slide")),
            vectorOf(light.getAsJsonArray("lens_low")),
            vectorOf(light.getAsJsonArray("lens_high")),
            vectorOf(light.getAsJsonArray("held_slide_low")),
            vectorOf(light.getAsJsonArray("held_slide_high")),
        )
    }

    private fun quadOf(quad: JsonObject): Quad {
        val texture = Identifier.parse(quad.get("texture").asString)
        val corners = quad.getAsJsonArray("corners").flatMap { corner -> corner.asJsonArray.map { it.asFloat } }.toFloatArray()
        return Quad(texture.withPath { "textures/$it.png" }, vectorOf(quad.getAsJsonArray("normal")), corners)
    }

    private fun vectorOf(values: JsonArray) = Vector3f(values[0].asFloat, values[1].asFloat, values[2].asFloat)

    private const val ALONG_THE_BEAM = 0.5f
    private const val CORNERS = 4
    private const val FLOATS_A_CORNER = 5
    private const val WHITE = -1
}
