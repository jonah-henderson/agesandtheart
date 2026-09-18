package co.voik.agesandtheart.worldgen.fissure

import co.voik.agesandtheart.Constants
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

/**
 * That the fall this player was in the middle of is still running — on its way to a client that has just
 * joined (design §7.8).
 *
 * **The one thing about a fall neither side can work out on the way back in.** Everything else is derived
 * from the blocks, identically on both sides, which is what lets the drop be smooth with nothing sent; but
 * `Entity.noPhysics` is a plain field with no synchronisation behind it, and a client cannot derive it
 * because entering a tear asks for one **in the way down** and by now the tear is fifty blocks overhead.
 * [StarFissureFall.SAVE_KEY] restores it on the server alone, so without this the two sides disagree for the
 * rest of the drop: the server lets them on through the rock while the client walls them up inside it, which
 * is the suffocation overlay, no field, and no way out.
 *
 * **Nothing in it**, because there is only one thing to say and only one moment it is ever said at. Ending
 * a fall needs no message: `Player.tick` writes the flag back from `isSpectator` every tick, so a client
 * told nothing stops falling on its own.
 */
data object FallResumedPayload : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<FallResumedPayload> = TYPE

    val TYPE: CustomPacketPayload.Type<FallResumedPayload> = CustomPacketPayload.Type(
        Identifier.fromNamespaceAndPath(Constants.MOD_ID, "fall_resumed"),
    )

    val STREAM_CODEC: StreamCodec<ByteBuf, FallResumedPayload> = StreamCodec.unit(FallResumedPayload)
}
