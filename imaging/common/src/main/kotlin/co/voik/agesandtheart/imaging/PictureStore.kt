package co.voik.agesandtheart.imaging

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.storage.LevelResource
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * The pictures a world holds: a folder each in the save, `agesandtheart_imaging/pictures/<id>/`, with the picture at
 * each [PictureSizes.Size] and what is known about its taking in `about.json`. Kept as maps are, for as long as the save.
 */
object PictureStore {

    /** What is known about a picture besides its pixels. */
    data class About(val photographer: UUID, val pose: Shots.Pose, val fieldOfView: Float, val takenAt: Long)

    private const val ABOUT_FILE = "about.json"
    private const val SKETCH_FILE = "sketch.jpg"
    private val GSON = GsonBuilder().setPrettyPrinting().create()

    fun folderOf(server: MinecraftServer, id: UUID): Path =
        server.getWorldPath(LevelResource.ROOT).resolve("agesandtheart_imaging").resolve("pictures").resolve(id.toString())

    /** Writes [upload] out at every size, or refuses it. Off the server thread: decoding and scaling take a moment. */
    fun develop(server: MinecraftServer, id: UUID, upload: ByteArray, about: About) {
        val picture = try {
            PictureSizes.read(upload)
        } catch (failure: Exception) {
            Imaging.LOG.warn("A picture for {} could not be read: {}", id, failure.message)
            null
        }
        if (picture == null) {
            Imaging.LOG.warn("A picture for {} was refused: not a 16:9 JPEG no larger than 1920 by 1080", id)
            return
        }
        val folder = folderOf(server, id)
        Files.createDirectories(folder)
        PictureSizes.Size.entries.forEach { size ->
            Files.write(folder.resolve(size.fileName), PictureSizes.written(PictureSizes.fitted(picture, size)))
        }
        Files.writeString(folder.resolve(ABOUT_FILE), GSON.toJson(aboutJson(about)))
        Imaging.LOG.info("Developed picture {}, {} by {}", id, picture.width, picture.height)
    }

    /** The server's own drawing of the picture, kept beside it, and the picture's particulars if they are not yet. */
    fun keepSketch(server: MinecraftServer, id: UUID, sketch: java.awt.image.BufferedImage, about: About) {
        val folder = folderOf(server, id)
        Files.createDirectories(folder)
        Files.write(folder.resolve(SKETCH_FILE), PictureSizes.written(sketch))
        val aboutFile = folder.resolve(ABOUT_FILE)
        if (!Files.exists(aboutFile)) Files.writeString(aboutFile, GSON.toJson(aboutJson(about)))
    }

    /** What [viewer] may see of a picture at [size]: the photograph, or the sketch where they may not or it never came. */
    class Seen(val bytes: ByteArray, val isSketch: Boolean)

    fun seenBy(server: MinecraftServer, viewer: UUID, id: UUID, size: PictureSizes.Size): Seen? {
        val folder = folderOf(server, id)
        val photograph = folder.resolve(size.fileName)
        val sketch = folder.resolve(SKETCH_FILE)
        val mayBeShownThePhotograph = ImagingConfig.sharesPictures.get() || photographerOf(folder) == viewer
        return when {
            mayBeShownThePhotograph && Files.isRegularFile(photograph) -> Seen(Files.readAllBytes(photograph), isSketch = false)
            Files.isRegularFile(sketch) -> Seen(Files.readAllBytes(sketch), isSketch = true)
            else -> null
        }
    }

    private fun photographerOf(folder: Path): UUID? = runCatching {
        val about = GSON.fromJson(Files.readString(folder.resolve(ABOUT_FILE)), JsonObject::class.java)
        UUID.fromString(about.get("photographer").asString)
    }.getOrNull()

    private fun aboutJson(about: About) = JsonObject().apply {
        addProperty("photographer", about.photographer.toString())
        addProperty("dimension", about.pose.dimension.identifier().toString())
        addProperty("x", about.pose.eyeX)
        addProperty("y", about.pose.eyeY)
        addProperty("z", about.pose.eyeZ)
        addProperty("yaw", about.pose.yaw)
        addProperty("pitch", about.pose.pitch)
        addProperty("field_of_view", about.fieldOfView)
        addProperty("taken_at", about.takenAt)
    }
}
