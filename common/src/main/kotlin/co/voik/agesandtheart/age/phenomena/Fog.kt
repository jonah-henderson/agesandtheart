package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.age.aspect.Rung
import co.voik.agesandtheart.location
import co.voik.agesandtheart.platform.Services
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.level.levelgen.XoroshiroRandomSource

/**
 * Banks of heavy fog rolling over an Age and lifting again ([co.voik.agesandtheart.age.aspect.Phenomenon.FOG]).
 *
 * **Nothing here touches the world.** The server keeps the schedule — which spells of the Age's time lie under
 * a bank, drawn off its seed so every client agrees — and the client draws the fog ([FogPayload]).
 *
 * Time is cut into spells of [SPELL_TICKS], and each spell is foggy or clear by a draw at the share the Age's
 * fog reaches. A bank rolls in over the last [ROLLING_TICKS] of the spell before a foggy one and lifts over the
 * last of a foggy one, so it is never switched on and off.
 */
object Fog {

    /** How much of a bank lies over [level] now, from none of one to all of it, for a fog of [density] and [dials]. */
    fun lyingAt(level: ServerLevel, density: Double, dials: FogDials): Double {
        val spell = level.gameTime / SPELL_TICKS
        val intoIt = level.gameTime % SPELL_TICKS
        val share = shareOfTheTime(density, dials.often)
        val now = if (isFoggy(level.seed, spell, share)) WHOLLY else NONE
        val next = if (isFoggy(level.seed, spell + 1, share)) WHOLLY else NONE
        val turning = ((intoIt - (SPELL_TICKS - ROLLING_TICKS)).toDouble() / ROLLING_TICKS).coerceIn(NONE, WHOLLY)
        return Mth.lerp(Mth.smoothstep(turning.toFloat()).toDouble(), now, next)
    }

    /**
     * How much of the time a bank lies over an Age this foggy: an ordinary fog about a quarter of it, and one
     * bought in full more often than not. A written rung would scale it too, as a rung does the weather.
     */
    fun shareOfTheTime(density: Double, often: Double): Double =
        ((SOMETIMES + often * (MOSTLY - SOMETIMES)) * density / Rung.ORDINARY).coerceIn(NONE, MOSTLY)

    /** How close a bank closes in: one for an ordinary fog, and twice as close bought in full. */
    fun thicknessAt(reach: Double): Double = Rung.ORDINARY + reach * THICKER_BOUGHT_IN_FULL

    private fun isFoggy(seed: Long, spell: Long, share: Double): Boolean =
        XoroshiroRandomSource(seed xor FOG_SALT xor spell).nextDouble() < share

    /**
     * Tell everyone in [level] how much fog lies over it, and how thick. Sent where there is none too, so a
     * client leaving a foggy Age for a clear one does not carry the bank with it.
     */
    fun tellTheClients(level: ServerLevel, density: Double?, dials: FogDials) {
        val age = level.dimension().identifier()
        val telling = if (density == null) {
            FogPayload.noneIn(age)
        } else {
            FogPayload(age, lyingAt(level, density, dials), thicknessAt(dials.thickness))
        }
        for (player in level.players()) Services.NETWORK.sendToPlayer(player, telling)
    }

    /** Two minutes: long enough to be caught out in, short enough that a bank lifts while you wait. */
    private const val SPELL_TICKS = 2400L

    /** Half a minute to roll in or lift. */
    private const val ROLLING_TICKS = 600L

    private const val SOMETIMES = 0.25
    private const val MOSTLY = 0.6
    private const val THICKER_BOUGHT_IN_FULL = 1.0
    private const val NONE = 0.0
    private const val WHOLLY = 1.0
    private const val FOG_SALT = 0x6F0C_A1E5_D3B2_9A17L
}

/**
 * How much of a bank of fog lies over the Age you are standing in, and how thick it is.
 *
 * **Sent rather than derived**, as [BlizzardPayload] is and for its reasons: whether an Age has a fog is a fact
 * about a recipe that never leaves the server, and the schedule is drawn off a seed a `ClientLevel` lacks.
 * [age] lets a client refuse a message meant for the world it just left.
 */
data class FogPayload(val age: Identifier, val lying: Double, val thickness: Double) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<FogPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<FogPayload> = CustomPacketPayload.Type("fog".location())

        val STREAM_CODEC: StreamCodec<ByteBuf, FogPayload> = StreamCodec.composite(
            Identifier.STREAM_CODEC,
            FogPayload::age,
            ByteBufCodecs.DOUBLE,
            FogPayload::lying,
            ByteBufCodecs.DOUBLE,
            FogPayload::thickness,
            ::FogPayload,
        )

        /** What an Age with no fog says, which is most of them. */
        fun noneIn(age: Identifier) = FogPayload(age, lying = 0.0, thickness = 0.0)
    }
}
