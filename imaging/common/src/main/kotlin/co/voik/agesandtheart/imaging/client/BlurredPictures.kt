package co.voik.agesandtheart.imaging.client

import co.voik.agesandtheart.imaging.Imaging
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB

/**
 * A picture thrown with no lens to focus it: shrunk to a quarter and blurred, so the wall shows its colours and shapes
 * but none of its detail, a hint at what a lens would bring. Made once a picture and kept until the client leaves.
 */
object BlurredPictures {

    private val made = HashMap<Identifier, Identifier>()

    fun blurred(picture: Identifier): Identifier? {
        made[picture]?.let { return it }
        val minecraft = Minecraft.getInstance()
        val pixels = (minecraft.textureManager.getTexture(picture) as? DynamicTexture)?.pixels ?: return null
        val width = (pixels.width / SHRINK).coerceAtLeast(1)
        val height = (pixels.height / SHRINK).coerceAtLeast(1)
        val small = NativeImage(width, height, false)
        pixels.resizeSubRectTo(0, 0, pixels.width, pixels.height, small)
        val colours = IntArray(width * height) { small.getPixel(it % width, it / width) }
        repeat(PASSES) {
            boxBlur(colours, width, height, across = true)
            boxBlur(colours, width, height, across = false)
        }
        for (at in colours.indices) small.setPixel(at % width, at / width, colours[at])
        val texture = Imaging.id("blurred/${picture.path.replace('/', '_')}")
        minecraft.textureManager.register(texture, DynamicTexture({ "Blurred $picture" }, small))
        made[picture] = texture
        return texture
    }

    fun forget() {
        val textures = Minecraft.getInstance().textureManager
        made.values.forEach(textures::release)
        made.clear()
    }

    /** Each pixel the mean of [RADIUS] either side of it along one direction, the edges held. */
    private fun boxBlur(colours: IntArray, width: Int, height: Int, across: Boolean) {
        val lines = if (across) height else width
        val length = if (across) width else height
        val line = IntArray(length)
        for (which in 0..<lines) {
            fun indexOf(along: Int) = if (across) which * width + along else along * width + which
            for (along in 0..<length) line[along] = colours[indexOf(along)]
            for (along in 0..<length) {
                var red = 0
                var green = 0
                var blue = 0
                for (offset in -RADIUS..RADIUS) {
                    val colour = line[(along + offset).coerceIn(0, length - 1)]
                    red += ARGB.red(colour)
                    green += ARGB.green(colour)
                    blue += ARGB.blue(colour)
                }
                val count = RADIUS * 2 + 1
                colours[indexOf(along)] = ARGB.color(OPAQUE, red / count, green / count, blue / count)
            }
        }
    }

    private const val SHRINK = 4
    private const val RADIUS = 3
    private const val PASSES = 2
    private const val OPAQUE = 255
}
