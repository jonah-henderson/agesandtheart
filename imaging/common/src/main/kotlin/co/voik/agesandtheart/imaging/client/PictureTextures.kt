package co.voik.agesandtheart.imaging.client

import co.voik.agesandtheart.client.sendToServer
import co.voik.agesandtheart.imaging.Imaging
import co.voik.agesandtheart.imaging.PictureSent
import co.voik.agesandtheart.imaging.PictureSizes
import co.voik.agesandtheart.imaging.PictureWanted
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.util.Util
import org.lwjgl.stb.STBImage
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Pictures as textures on this client: each size asked for the first time it is needed, kept on disk once it comes
 * (`agesandtheart_imaging/pictures/` in the game folder, by picture id, which no two servers share), and uploaded as a
 * texture on the render thread. Only a few full-size pictures are kept at once, since each is several megabytes on the
 * graphics card; the one seen least lately goes first.
 */
object PictureTextures {

    private sealed interface Held
    private data object Asked : Held
    private class Missing(val untilMillis: Long) : Held
    private class Ready(val texture: Identifier) : Held

    private val held = HashMap<Pair<UUID, PictureSizes.Size>, Held>()
    private val fullSizeSeenLately = LinkedHashSet<UUID>()

    /** How many full-size pictures stay uploaded at once. */
    private const val MOST_FULL_SIZE = 4

    /** How long to wait before asking again after the server had nothing, while a picture is still being developed. */
    private const val ASK_AGAIN_MILLIS = 5_000L

    private const val RGBA = 4

    /**
     * The texture for [id] at [size] if it is here; otherwise it is sent for, and the nearest size already here stands
     * in, or null where there is none.
     */
    fun textureFor(id: UUID, size: PictureSizes.Size): Identifier? {
        val wanted = held[id to size]
        if (wanted is Ready) {
            if (size == PictureSizes.Size.FULL) seenAtFullSize(id)
            return wanted.texture
        }
        fetch(id, size)
        return PictureSizes.Size.entries.sortedBy { kotlin.math.abs(it.ordinal - size.ordinal) }
            .firstNotNullOfOrNull { (held[id to it] as? Ready)?.texture }
    }

    fun received(sent: PictureSent) {
        val size = PictureSizes.Size.entries.getOrNull(sent.size) ?: return
        if (sent.bytes.isEmpty()) {
            held[sent.id to size] = Missing(System.currentTimeMillis() + ASK_AGAIN_MILLIS)
            return
        }
        Util.ioPool().execute {
            runCatching {
                Files.createDirectories(cacheFolder())
                Files.write(cacheFile(sent.id, size), sent.bytes)
            }
            decodeAndUpload(sent.id, size, sent.bytes)
        }
    }

    /** Everything let go, when this client leaves a world; what is on disk stays. */
    fun forget() {
        val textures = Minecraft.getInstance().textureManager
        held.values.filterIsInstance<Ready>().forEach { textures.release(it.texture) }
        held.clear()
        fullSizeSeenLately.clear()
    }

    private fun fetch(id: UUID, size: PictureSizes.Size) {
        val current = held[id to size]
        val isWaiting = current is Asked || (current is Missing && System.currentTimeMillis() < current.untilMillis)
        if (isWaiting) return
        held[id to size] = Asked
        Util.ioPool().execute {
            val cached = cacheFile(id, size)
            val bytes = if (Files.isRegularFile(cached)) runCatching { Files.readAllBytes(cached) }.getOrNull() else null
            if (bytes != null) {
                decodeAndUpload(id, size, bytes)
            } else {
                Minecraft.getInstance().execute { sendToServer(PictureWanted(id, size.ordinal)) }
            }
        }
    }

    private fun decodeAndUpload(id: UUID, size: PictureSizes.Size, bytes: ByteArray) {
        val image = decoded(bytes)
        if (image == null) {
            Imaging.LOG.warn("Picture {} at {} could not be decoded", id, size)
            Minecraft.getInstance().execute { held[id to size] = Missing(Long.MAX_VALUE) }
            return
        }
        Minecraft.getInstance().execute {
            val texture = Imaging.id("picture/$id/${size.name.lowercase()}")
            Minecraft.getInstance().textureManager.register(texture, DynamicTexture({ "Picture $id" }, image))
            held[id to size] = Ready(texture)
            if (size == PictureSizes.Size.FULL) seenAtFullSize(id)
        }
    }

    private fun seenAtFullSize(id: UUID) {
        fullSizeSeenLately.remove(id)
        fullSizeSeenLately.add(id)
        while (fullSizeSeenLately.size > MOST_FULL_SIZE) {
            val oldest = fullSizeSeenLately.first()
            fullSizeSeenLately.remove(oldest)
            (held.remove(oldest to PictureSizes.Size.FULL) as? Ready)?.let { Minecraft.getInstance().textureManager.release(it.texture) }
        }
    }

    /** A JPEG the server wrote, decoded by STB into an image the texture can take. */
    private fun decoded(bytes: ByteArray): NativeImage? {
        val input = MemoryUtil.memAlloc(bytes.size).put(bytes).flip()
        try {
            MemoryStack.stackPush().use { stack ->
                val width = stack.mallocInt(1)
                val height = stack.mallocInt(1)
                val channels = stack.mallocInt(1)
                val pixels = STBImage.stbi_load_from_memory(input, width, height, channels, RGBA) ?: return null
                try {
                    val image = NativeImage(width.get(0), height.get(0), false)
                    MemoryUtil.memCopy(MemoryUtil.memAddress(pixels), image.pointer, width.get(0).toLong() * height.get(0) * RGBA)
                    return image
                } finally {
                    STBImage.stbi_image_free(pixels)
                }
            }
        } finally {
            MemoryUtil.memFree(input)
        }
    }

    private fun cacheFolder(): Path = Minecraft.getInstance().gameDirectory.toPath().resolve("agesandtheart_imaging").resolve("pictures")

    private fun cacheFile(id: UUID, size: PictureSizes.Size): Path = cacheFolder().resolve("$id-${size.name.lowercase()}.jpg")
}
