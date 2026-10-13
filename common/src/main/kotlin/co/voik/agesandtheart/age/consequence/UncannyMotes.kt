package co.voik.agesandtheart.age.consequence

import co.voik.agesandtheart.age.AgeRecipe
import co.voik.agesandtheart.age.Manifestation
import co.voik.agesandtheart.age.Spending
import co.voik.agesandtheart.location
import co.voik.agesandtheart.platform.Services
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel

/**
 * Motes no air should hold, hanging in an Age whose instability bought them — told to the clients in it, which
 * draw them, since what was bought is a fact about a recipe that never leaves the server.
 */
object UncannyMotes {

    /** Tells everyone in [level] what motes hang there, on a slow beat as the fog does — a dozen bytes a second. */
    fun tellTheClients(level: ServerLevel, recipe: AgeRecipe, spending: Spending) {
        if (level.server.tickCount % TELLING_THE_CLIENT != 0) return
        val age = level.dimension().identifier()
        val telling = if (spending.bought(Manifestation.UNCANNY_MOTES) == 0) {
            MotesPayload.noneIn(age)
        } else {
            MotesPayload(
                age,
                present = true,
                seed = recipe.seed,
                density = spending.reach(Manifestation.UNCANNY_MOTES, Manifestation.MOTES_DENSITY),
                size = spending.reach(Manifestation.UNCANNY_MOTES, Manifestation.MOTES_SIZE),
            )
        }
        for (player in level.players()) Services.NETWORK.sendToPlayer(player, telling)
    }

    private const val TELLING_THE_CLIENT = 20
}

/**
 * Whether the Age you stand in has uncanny motes, and how far each dial went. [seed] is the Age's, from which the
 * client draws how they look and move. [age] lets a client refuse a message meant for the world it just left.
 */
data class MotesPayload(
    val age: Identifier,
    val present: Boolean,
    val seed: Long,
    val density: Double,
    val size: Double,
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<MotesPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<MotesPayload> = CustomPacketPayload.Type("motes".location())

        val STREAM_CODEC: StreamCodec<ByteBuf, MotesPayload> = StreamCodec.composite(
            Identifier.STREAM_CODEC,
            MotesPayload::age,
            ByteBufCodecs.BOOL,
            MotesPayload::present,
            ByteBufCodecs.VAR_LONG,
            MotesPayload::seed,
            ByteBufCodecs.DOUBLE,
            MotesPayload::density,
            ByteBufCodecs.DOUBLE,
            MotesPayload::size,
            ::MotesPayload,
        )

        /** What an Age with no motes says, which is most of them. */
        fun noneIn(age: Identifier) = MotesPayload(age, present = false, seed = 0L, density = 0.0, size = 0.0)
    }
}
