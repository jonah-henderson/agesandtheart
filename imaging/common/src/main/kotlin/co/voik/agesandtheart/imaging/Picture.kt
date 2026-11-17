package co.voik.agesandtheart.imaging

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.netty.buffer.ByteBuf
import net.minecraft.core.BlockPos
import net.minecraft.core.UUIDUtil
import net.minecraft.core.component.DataComponentType
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.resources.Identifier
import java.util.UUID

/**
 * What a recorded image is of: the picture's id, under which the server keeps its files ([PictureStore]), and the
 * place's name and the block it was taken from, kept as they were, as a linking book keeps its place's name.
 */
data class Picture(val id: UUID, val place: String, val position: BlockPos) {

    companion object {
        val CODEC: Codec<Picture> = RecordCodecBuilder.create { instance ->
            instance.group(
                UUIDUtil.CODEC.fieldOf("id").forGetter(Picture::id),
                Codec.STRING.fieldOf("place").forGetter(Picture::place),
                BlockPos.CODEC.fieldOf("position").forGetter(Picture::position),
            ).apply(instance, ::Picture)
        }

        val STREAM_CODEC: StreamCodec<ByteBuf, Picture> = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, Picture::id,
            ByteBufCodecs.STRING_UTF8, Picture::place,
            BlockPos.STREAM_CODEC, Picture::position,
            ::Picture,
        )
    }
}

/** The companion's data components, registered before its items. */
object ImagingComponents {

    private val PICTURE_ID: Identifier = Imaging.id("picture")

    val PICTURE: DataComponentType<Picture> = DataComponentType.builder<Picture>()
        .persistent(Picture.CODEC)
        .networkSynchronized(Picture.STREAM_CODEC)
        .build()

    val components: List<Pair<Identifier, DataComponentType<*>>> = listOf(PICTURE_ID to PICTURE)
}
