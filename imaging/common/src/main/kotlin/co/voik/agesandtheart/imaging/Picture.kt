package co.voik.agesandtheart.imaging

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.netty.buffer.ByteBuf
import net.minecraft.core.BlockPos
import net.minecraft.core.UUIDUtil
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.core.component.DataComponentType
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.resources.Identifier
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import java.util.Optional
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

/** What a recorded image is framed in: its [planks], or nothing where it is the picture alone. */
data class PictureFrame(val planks: Block?) {

    companion object {
        val UNFRAMED = PictureFrame(null)

        val CODEC: Codec<PictureFrame> = BuiltInRegistries.BLOCK.byNameCodec().optionalFieldOf("planks")
            .xmap({ PictureFrame(it.orElse(null)) }, { Optional.ofNullable(it.planks) })
            .codec()

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, PictureFrame> =
            ByteBufCodecs.optional(ByteBufCodecs.registry(Registries.BLOCK))
                .map({ PictureFrame(it.orElse(null)) }, { Optional.ofNullable(it.planks) })
    }
}

/**
 * The planks this recorded image is framed in, or null where it is the picture alone. One taken before frames could be
 * chosen says nothing, and keeps the dark oak every picture was framed in then.
 */
val ItemStack.framedIn: Block?
    get() {
        val frame = get(ImagingComponents.FRAME) ?: return Blocks.DARK_OAK_PLANKS
        return frame.planks
    }

/** The companion's data components, registered before its items. */
object ImagingComponents {

    private val PICTURE_ID: Identifier = Imaging.id("picture")

    val PICTURE: DataComponentType<Picture> = DataComponentType.builder<Picture>()
        .persistent(Picture.CODEC)
        .networkSynchronized(Picture.STREAM_CODEC)
        .build()

    private val FRAME_ID: Identifier = Imaging.id("frame")

    /** What a recorded image is framed in ([FramingRecipe]); one without it was taken before frames ([framedIn]). */
    val FRAME: DataComponentType<PictureFrame> = DataComponentType.builder<PictureFrame>()
        .persistent(PictureFrame.CODEC)
        .networkSynchronized(PictureFrame.STREAM_CODEC)
        .build()

    val components: List<Pair<Identifier, DataComponentType<*>>> = listOf(PICTURE_ID to PICTURE, FRAME_ID to FRAME)
}
