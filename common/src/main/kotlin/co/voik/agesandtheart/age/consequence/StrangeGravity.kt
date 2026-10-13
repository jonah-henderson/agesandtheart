package co.voik.agesandtheart.age.consequence

import co.voik.agesandtheart.age.AgeRecipe
import co.voik.agesandtheart.age.Manifestation
import co.voik.agesandtheart.age.Spending
import co.voik.agesandtheart.location
import co.voik.agesandtheart.platform.Services
import io.netty.buffer.ByteBuf
import net.minecraft.core.registries.Registries
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * How hard an Age pulls things down, as a share of vanilla's gravity — one number per Age that everything falls by,
 * players, mobs, items, sand and arrows alike, and that a fall hurts by.
 *
 * **Both sides keep it**, because the client moves its own player and predicts what it can see: the server sets
 * its own from the Age's spending each tick and tells the clients on a slow beat, and `EntityGravityMixin` reads
 * whichever side's it is on.
 */
object StrangeGravity {

    private val onServer = ConcurrentHashMap<ResourceKey<Level>, Double>()
    private val onClient = ConcurrentHashMap<ResourceKey<Level>, Double>()

    /**
     * How far from vanilla's an Age of [seed] that bought [spending] pulls: further for each step, and lighter
     * or heavier as the seed says, so the steps only say how far.
     */
    fun factorFor(spending: Spending, seed: Long): Double {
        val steps = spending.bought(Manifestation.STRANGE_GRAVITY, Manifestation.GRAVITY_STRENGTH)
        if (steps <= 0) return ORDINARY
        val step = steps.coerceAtMost(HEAVIER_BY_STEPS.size - 1)
        val lighter = Random(seed xor DIRECTION_SALT).nextBoolean()
        return if (lighter) ORDINARY / (ORDINARY + LIGHTER_BY_STEPS[step]) else ORDINARY + HEAVIER_BY_STEPS[step]
    }

    /** How hard the Age of [recipe] that bought [spending] pulls — its pull set by hand, where one is. */
    fun pullOf(recipe: AgeRecipe, spending: Spending): Double = recipe.forcedPull ?: factorFor(spending, recipe.seed)

    /** The share of vanilla's gravity [level] pulls with, on whichever side it is — vanilla's own outside an Age. */
    @JvmStatic
    fun of(level: Level): Double {
        val factors = if (level.isClientSide) onClient else onServer
        if (factors.isEmpty()) return ORDINARY
        return factors[level.dimension()] ?: ORDINARY
    }

    /** Settles [level]'s pull from what it bought, and tells the clients in it on the motes' beat. */
    fun keep(level: ServerLevel, recipe: AgeRecipe, spending: Spending) {
        val factor = pullOf(recipe, spending)
        if (factor == ORDINARY) onServer.remove(level.dimension()) else onServer[level.dimension()] = factor
        if (level.server.tickCount % TELLING_THE_CLIENT != 0) return
        val telling = GravityPayload(level.dimension().identifier(), factor)
        for (player in level.players()) Services.NETWORK.sendToPlayer(player, telling)
    }

    fun remember(payload: GravityPayload) {
        val dimension = ResourceKey.create(Registries.DIMENSION, payload.age)
        if (payload.factor == ORDINARY) onClient.remove(dimension) else onClient[dimension] = payload.factor
    }

    fun serverStopped() = onServer.clear()

    fun disconnected() = onClient.clear()

    const val ORDINARY = 1.0

    /** How much heavier than vanilla's each step pulls. */
    private val HEAVIER_BY_STEPS = listOf(0.0, 0.40, 0.75, 1.00)

    /** How much heavier vanilla's pull is than the Age's at each step — a third of it at the last. */
    private val LIGHTER_BY_STEPS = listOf(0.0, 0.40, 1.00, 2.00)

    private const val TELLING_THE_CLIENT = 20
    private const val DIRECTION_SALT = 0x2B6E_91C4_07FA_3D85L
}

/** How hard the Age named [age] pulls, as a share of vanilla's gravity. */
data class GravityPayload(val age: Identifier, val factor: Double) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<GravityPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<GravityPayload> = CustomPacketPayload.Type("gravity".location())

        val STREAM_CODEC: StreamCodec<ByteBuf, GravityPayload> = StreamCodec.composite(
            Identifier.STREAM_CODEC,
            GravityPayload::age,
            ByteBufCodecs.DOUBLE,
            GravityPayload::factor,
            ::GravityPayload,
        )
    }
}
