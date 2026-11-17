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

    /** The picture at [size], or null where it was never developed. */
    fun read(server: MinecraftServer, id: UUID, size: PictureSizes.Size): ByteArray? {
        val file = folderOf(server, id).resolve(size.fileName)
        return if (Files.isRegularFile(file)) Files.readAllBytes(file) else null
    }

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
