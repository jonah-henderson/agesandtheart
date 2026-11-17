package co.voik.agesandtheart.imaging.client

import co.voik.agesandtheart.imaging.Imaging
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB

/**
 * A picture with an overlay held over it, as a jack o'lantern's face holds back all but what shines through its
 * carving: made once a pair, the picture's light kept where the overlay is clear and taken away where it is not.
 */
object MaskedPictures {

    private val made = HashMap<Pair<Identifier, Identifier>, Identifier>()
    private val masks = HashMap<Identifier, NativeImage>()

    fun masked(picture: Identifier, overlay: Identifier): Identifier? {
        made[picture to overlay]?.let { return it }
        val minecraft = Minecraft.getInstance()
        val pixels = (minecraft.textureManager.getTexture(picture) as? DynamicTexture)?.pixels ?: return null
        val mask = maskOf(overlay) ?: return null
        val masked = NativeImage(pixels.width, pixels.height, false)
        for (y in 0..<pixels.height) {
            for (x in 0..<pixels.width) {
                val held = mask.getPixel(x * mask.width / pixels.width, y * mask.height / pixels.height) ushr ALPHA_SHIFT and 0xFF
                val passes = ((0xFF - held) * PASSING).coerceIn(0, 0xFF)
                // Darkened as well as made clear, since light added to a wall ignores how clear it is.
                masked.setPixel(x, y, ARGB.color(passes, ARGB.scaleRGB(pixels.getPixel(x, y), passes / 255.0f)))
            }
        }
        val texture = Imaging.id("masked/${picture.path.replace('/', '_')}/${overlay.path.replace('/', '_')}")
        minecraft.textureManager.register(texture, DynamicTexture({ "Masked $picture" }, masked))
        made[picture to overlay] = texture
        return texture
    }

    fun forget() {
        val textures = Minecraft.getInstance().textureManager
        made.values.forEach(textures::release)
        made.clear()
        masks.values.forEach(NativeImage::close)
        masks.clear()
    }

    private fun maskOf(overlay: Identifier): NativeImage? = masks[overlay] ?: runCatching {
        Minecraft.getInstance().resourceManager.open(overlay).use(NativeImage::read)
    }.getOrNull()?.also { masks[overlay] = it }

    private const val ALPHA_SHIFT = 24

    /** How sharply the carving shows: an overlay half clear lets all the light through. */
    private const val PASSING = 2
}
