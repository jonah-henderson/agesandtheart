package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.Constants
import io.netty.buffer.ByteBuf
import net.minecraft.core.Direction
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

/**
 * That a blizzard blows in the Age you are standing in, how hard, and which way — on its way to the client.
 *
 * **Sent rather than derived, because the client cannot know any of it.** Whether an Age has a blizzard is
 * a fact about its recipe, which never leaves the server; how fierce it is folds in an instability the
 * client is deliberately never told; and the bearing is drawn from the Age's seed, which a `ClientLevel`
 * does not have. Only *whether it is raining* crosses on its own, and that alone cannot tell snow from a
 * shower.
 *
 * **One payload for the whole state**, so a client can never hold half of it — a severity from one Age and
 * a bearing from the next would be a storm blowing the wrong way, which is exactly the kind of fault a
 * split message makes possible and a single one cannot.
 *
 * [NONE] is how an Age says it has no blizzard, which is most of them: the client is told on every change
 * of world rather than only when there is something to say, or leaving a frozen Age for an ordinary one
 * would leave the wind blowing.
 */
data class BlizzardPayload(
    /**
     * Which Age this is about.
     *
     * Carried so the client can refuse a message meant for the world it just left — a payload in flight
     * while somebody links is otherwise a storm blowing in the wrong Age, and there is no lifecycle hook
     * that closes that window as cheaply as one field does.
     */
    val age: Identifier,
    /** How hard it blows, or [NOTHING] where this Age has no blizzard at all. */
    val severity: Double,
    /** Which way it drives, as [Direction.get2DDataValue]. Meaningless where [severity] is nothing. */
    val bearing: Int,
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<BlizzardPayload> = TYPE

    val blowing: Boolean get() = severity > NOTHING

    /** The bearing as a direction, always horizontal. */
    fun driving(): Direction = Direction.from2DDataValue(bearing)

    companion object {
        val TYPE: CustomPacketPayload.Type<BlizzardPayload> = CustomPacketPayload.Type(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "blizzard"),
        )

        val STREAM_CODEC: StreamCodec<ByteBuf, BlizzardPayload> = StreamCodec.composite(
            Identifier.STREAM_CODEC,
            BlizzardPayload::age,
            ByteBufCodecs.DOUBLE,
            BlizzardPayload::severity,
            ByteBufCodecs.VAR_INT,
            BlizzardPayload::bearing,
            ::BlizzardPayload,
        )

        private const val NOTHING = 0.0

        /** What an Age with no blizzard says, which is most of them. */
        fun noneIn(age: Identifier) = BlizzardPayload(age, NOTHING, 0)
    }
}
