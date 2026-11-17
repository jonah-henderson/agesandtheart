package co.voik.agesandtheart.imaging.client

import co.voik.agesandtheart.client.sendToServer
import co.voik.agesandtheart.imaging.Imaging
import co.voik.agesandtheart.imaging.PicturePiece
import co.voik.agesandtheart.imaging.PictureSizes
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.util.Util
import org.lwjgl.stb.STBIWriteCallback
import org.lwjgl.stb.STBImageWrite
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * Taking the picture on the photographer's client: the next frame, read back after the post effects and before the GUI
 * is drawn (`GameRendererMixin`), with the viewfinder's own effect taken off for it, cropped to 16:9, made no larger than
 * 1920 by 1080, and sent up as a JPEG in [PicturePiece]s. Minecraft's own STB does the scaling and the encoding.
 */
object Capture {

    private const val QUALITY = 90
    private const val CHANNELS = 4

    private var isWanted = false

    /** The vertical field of view the picture is taken at, the zoom included. */
    private var fieldOfView = 0.0f

    /** Whether a picture is waiting for its frame, during which the view stays as the viewfinder shows it. */
    @JvmStatic
    fun isPending(): Boolean = isWanted

    fun request(minecraft: Minecraft) {
        isWanted = true
        fieldOfView = minecraft.options.fov().get() * ViewfinderView.fieldOfViewModifier()
        ViewfinderView.dropTheEffect(minecraft)
    }

    /** Called once a frame, after its post effects and before its GUI. */
    @JvmStatic
    fun afterThePostEffects() {
        if (!isWanted) return
        isWanted = false
        val minecraft = Minecraft.getInstance()
        val takenAt = fieldOfView
        Screenshot.takeScreenshot(minecraft.gameRenderer.mainRenderTarget()) { screen ->
            Util.backgroundExecutor().execute { sendUp(minecraft, screen, takenAt) }
        }
    }

    private fun sendUp(minecraft: Minecraft, screen: NativeImage, takenAt: Float) {
        val jpeg = screen.use { cropped(it).use(::encoded) }
        val pieces = jpeg.asList().chunked(PicturePiece.MOST_BYTES).map { it.toByteArray() }
        minecraft.execute {
            pieces.forEachIndexed { index, bytes -> sendToServer(PicturePiece(index, pieces.size, takenAt, bytes)) }
        }
        Imaging.LOG.info("A picture went up: {} bytes in {} pieces", jpeg.size, pieces.size)
    }

    /** The middle 16:9 of [screen], made no larger than the largest picture kept. */
    private fun cropped(screen: NativeImage): NativeImage {
        val aspect = PictureSizes.Size.FULL.width.toDouble() / PictureSizes.Size.FULL.height
        val isWiderThanAPicture = screen.width.toDouble() / screen.height > aspect
        val cropWidth = if (isWiderThanAPicture) (screen.height * aspect).roundToInt() else screen.width
        val cropHeight = if (isWiderThanAPicture) screen.height else (screen.width / aspect).roundToInt()
        val width = minOf(cropWidth, PictureSizes.Size.FULL.width)
        val height = (width / aspect).roundToInt()
        val picture = NativeImage(width, height, false)
        screen.resizeSubRectTo((screen.width - cropWidth) / 2, (screen.height - cropHeight) / 2, cropWidth, cropHeight, picture)
        return picture
    }

    private fun encoded(picture: NativeImage): ByteArray {
        val bytes = ByteArrayOutputStream()
        val callback = STBIWriteCallback.create { _, data, size -> bytes.write(STBIWriteCallback.getData(data, size).let { buffer -> ByteArray(size).also(buffer::get) }) }
        try {
            STBImageWrite.stbi_write_jpg_to_func(callback, 0L, picture.width, picture.height, CHANNELS, picture.pixelBytes, QUALITY)
        } finally {
            callback.free()
        }
        return bytes.toByteArray()
    }
}
