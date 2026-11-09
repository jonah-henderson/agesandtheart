package co.voik.agesandtheart.book

import com.mojang.serialization.Codec
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.util.StringRepresentable

/** How a book wears its banner's pattern across front board, spine and back board. */
enum class CoverLayout(private val key: String) : StringRepresentable {
    /** The whole field on each board, the back mirrored so both meet the spine on the field's edge column. */
    MIRRORED("mirrored"),

    /** The field turned on its side and wrapped back, spine, front. */
    WRAP_AROUND("wrap_around"),

    /** The field's top half on the front, its bottom half on the back. */
    FRONT_AND_BACK("front_and_back"),
    ;

    override fun getSerializedName(): String = key

    companion object {
        val DEFAULT = MIRRORED

        val CODEC: Codec<CoverLayout> = StringRepresentable.fromEnum(CoverLayout::values)

        val STREAM_CODEC: StreamCodec<ByteBuf, CoverLayout> =
            ByteBufCodecs.VAR_INT.map({ entries[it] }, CoverLayout::ordinal)
    }
}
