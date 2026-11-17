package co.voik.agesandtheart.imaging

import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.abs

/**
 * Reading an uploaded picture and writing it out again at each [Size], with Java's own image library on the server.
 *
 * Every picture another player sees is one this wrote, never the bytes a client sent: whatever a client puts in a file,
 * only decoded pixels get through, so a viewer's decoder never meets a file somebody crafted.
 */
object PictureSizes {

    enum class Size(val fileName: String, val width: Int, val height: Int) {
        FULL("full.jpg", 1920, 1080),
        MIDDLE("middle.jpg", 480, 270),
        SMALL("small.jpg", 120, 68),
    }

    private const val ASPECT = 16.0 / 9.0
    private const val ASPECT_TOLERANCE = 0.02
    private const val SMALLEST_WIDTH = 160
    private const val QUALITY = 0.9f

    /** The upload's picture, or null where it is not a JPEG of a sensible size and shape; the size is read first. */
    fun read(upload: ByteArray): BufferedImage? {
        ImageIO.createImageInputStream(ByteArrayInputStream(upload)).use { stream ->
            val reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull { it.formatName.equals("jpeg", ignoreCase = true) }
                ?: return null
            try {
                reader.input = stream
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                val isASensibleSize = width in SMALLEST_WIDTH..Size.FULL.width && height <= Size.FULL.height
                val isSixteenByNine = height > 0 && abs(width.toDouble() / height - ASPECT) < ASPECT_TOLERANCE
                if (!isASensibleSize || !isSixteenByNine) return null
                return reader.read(0)
            } finally {
                reader.dispose()
            }
        }
    }

    /** [picture] at [size] or smaller, never enlarged, averaged down so a small one stays smooth. */
    fun fitted(picture: BufferedImage, size: Size): BufferedImage {
        val width = minOf(picture.width, size.width)
        val height = minOf(picture.height, size.height)
        val fitted = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val scaled = if (width == picture.width && height == picture.height) picture else picture.getScaledInstance(width, height, Image.SCALE_AREA_AVERAGING)
        fitted.createGraphics().apply {
            drawImage(scaled, 0, 0, null)
            dispose()
        }
        return fitted
    }

    fun written(picture: BufferedImage): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val bytes = ByteArrayOutputStream()
        try {
            ImageIO.createImageOutputStream(bytes).use { stream ->
                writer.output = stream
                val settings = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = QUALITY
                }
                writer.write(null, IIOImage(picture, null, null), settings)
            }
        } finally {
            writer.dispose()
        }
        return bytes.toByteArray()
    }
}
