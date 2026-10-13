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
import kotlin.random.Random

/** How an Age's tears look: every tear in one Age alike, drawn from its seed. */
enum class TearStyle {
    /** A white core in a violet glow, as light coming through. */
    GLOWING,

    /** A black seam laid over the sky, by day and night. */
    DARK,

    /** The End portal's drifting stars seen through it, in a faint violet rim. */
    STARFIELD,
}

/**
 * Tears in an Age's sky, told to the clients in it, which lay them out and draw them — what was bought is a fact
 * about a recipe that never leaves the server, as the motes' is.
 */
object TornSky {

    /** Tells everyone in [level] how torn its sky is, on the motes' slow beat. */
    fun tellTheClients(level: ServerLevel, recipe: AgeRecipe, spending: Spending) {
        if (level.server.tickCount % TELLING_THE_CLIENT != 0) return
        val age = level.dimension().identifier()
        val count = countFor(spending)
        val telling = if (count == 0) TearsPayload.noneIn(age) else TearsPayload(age, count, styleFor(recipe.seed), recipe.seed)
        for (player in level.players()) Services.NETWORK.sendToPlayer(player, telling)
    }

    /** How many tears the steps bought hang: ten for the first, then twenty-five, then fifty. */
    fun countFor(spending: Spending): Int {
        val steps = spending.bought(Manifestation.SKY_TEARS, Manifestation.TEARS_MANY)
        return TEARS_BY_STEPS[steps.coerceIn(0, TEARS_BY_STEPS.size - 1)]
    }

    /** Which of the styles an Age of [seed] tears its sky in, each as likely as the others. */
    fun styleFor(seed: Long): TearStyle {
        val styles = TearStyle.entries
        return styles[Random(seed xor STYLE_SALT).nextInt(styles.size)]
    }

    private val TEARS_BY_STEPS = listOf(0, 10, 25, 50)
    private const val TELLING_THE_CLIENT = 20
    private const val STYLE_SALT = 0x61D2_0B7E_93A4_C55FL
}

/**
 * How many tears the Age you stand in has, in which style, and the seed they are laid out from. [age] lets a
 * client refuse a message meant for the world it just left.
 */
data class TearsPayload(val age: Identifier, val count: Int, val style: TearStyle, val seed: Long) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<TearsPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<TearsPayload> = CustomPacketPayload.Type("tears".location())

        val STREAM_CODEC: StreamCodec<ByteBuf, TearsPayload> = StreamCodec.composite(
            Identifier.STREAM_CODEC,
            TearsPayload::age,
            ByteBufCodecs.VAR_INT,
            TearsPayload::count,
            ByteBufCodecs.idMapper({ TearStyle.entries[it] }, TearStyle::ordinal),
            TearsPayload::style,
            ByteBufCodecs.VAR_LONG,
            TearsPayload::seed,
            ::TearsPayload,
        )

        /** What an untorn Age says, which is most of them. */
        fun noneIn(age: Identifier) = TearsPayload(age, count = 0, style = TearStyle.GLOWING, seed = 0L)
    }
}
