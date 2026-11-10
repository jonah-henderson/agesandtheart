package co.voik.agesandtheart.age.phenomena

import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.util.Mth
import net.minecraft.world.level.levelgen.XoroshiroRandomSource
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One gust as it stands at a moment: [warned] is how much of its warning has gone by, reaching one as it hits, and
 * [gusting] how hard it blows from then, nought to one and back. [presence] is how much of it there is to see and
 * hear, swelling through the warning to most of the way as it hits and going with it. It drives [veerDegrees] off the
 * gale's bearing, and [slot] tells one gust from the next.
 */
data class Gust(val slot: Long, val warned: Double, val gusting: Double, val presence: Double, val veerDegrees: Double)

/**
 * When a gale gusts, how hard, and with how much warning (`notes/the-art-design.md` §5.2.2, "Gale"): the gale's three gust dials and
 * a seed of the Age's own.
 *
 * **One schedule both sides read**, since the client pushes its own player and the server everything else: time is
 * cut into slots of [SLOT_TICKS], each with at most one gust in it, drawn off [seed] alone. The seed is drawn from the
 * Age's, so sending it tells a client nothing about the Age's ground.
 */
data class Gusts(val seed: Long, val often: Double, val force: Double, val sudden: Double) {

    val blowsAny: Boolean get() = often > NEVER

    /** How many ticks a gust is heard and seen coming before it hits: three seconds, or half of one at the most sudden. */
    val warningTicks: Int get() = Mth.lerp(sudden.coerceIn(NEVER, ALWAYS).toFloat(), LONGEST_WARNING, SHORTEST_WARNING).roundToInt()

    val warningSeconds: Double get() = warningTicks / TICKS_PER_SECOND

    /** How hard a gust drives, as a multiple of the server's gale dial: well above an ordinary gale's lean at the least. */
    val strength: Double get() = GENTLEST + force.coerceIn(NEVER, ALWAYS) * (FIERCEST - GENTLEST)

    /** The fewest ticks a gust blows for, from when it hits until it is gone: five seconds, more the fiercer. */
    private val shortestBlowTicks: Int get() = SHORTEST_GUST + (force.coerceIn(NEVER, ALWAYS) * LONGER_AT_FIERCEST).roundToInt()

    /** How many ticks a gust takes to build to its height once it hits: two seconds, or one at the most sudden. */
    private val riseTicks: Double get() = Mth.lerp(sudden.coerceIn(NEVER, ALWAYS), SLOWEST_RISE, QUICKEST_RISE)

    /** The share of slots with a gust in them. */
    private val shareOfSlots: Double get() = often.coerceIn(NEVER, ALWAYS) * MOST_SLOTS_GUSTING

    /** About how long between one gust and the next, in seconds. */
    val secondsBetween: Double get() = SLOT_TICKS / TICKS_PER_SECOND / shareOfSlots

    /** The gust at [gameTime], warning or blowing, or null between them. */
    fun at(gameTime: Long): Gust? {
        if (!blowsAny) return null
        val slot = Math.floorDiv(gameTime, SLOT_TICKS)
        val draw = XoroshiroRandomSource(seed xor slot * SLOT_SPREAD)
        if (draw.nextDouble() >= shareOfSlots) return null
        val warning = warningTicks
        val length = shortestBlowTicks + draw.nextInt(LENGTH_SPREAD + 1)
        val startsAt = draw.nextInt((SLOT_TICKS - warning - length).toInt() + 1)
        val veer = (draw.nextDouble() * 2.0 - 1.0) * MOST_VEER
        val since = gameTime - slot * SLOT_TICKS - startsAt
        return when {
            since < 0 -> null
            since < warning -> warningOf(slot, since.toDouble() / warning, veer)
            since < warning + length -> blowingOf(slot, since - warning, length, veer, riseTicks)
            else -> null
        }
    }

    private fun warningOf(slot: Long, warned: Double, veer: Double) =
        Gust(slot, warned, gusting = NEVER, presence = warned * warned * PRESENT_AS_IT_HITS, veer)

    /** A gust [since] ticks after it hits: building over [riseTicks], held, and let go more slowly still. */
    private fun blowingOf(slot: Long, since: Long, length: Int, veer: Double, riseTicks: Double): Gust {
        val rising = Mth.smoothstep(min(ALWAYS, (since + 1).toDouble() / riseTicks).toFloat()).toDouble()
        val falling = Mth.smoothstep(min(ALWAYS, (length - since).toDouble() / FALL_TICKS).toFloat()).toDouble()
        val presence = (PRESENT_AS_IT_HITS + (ALWAYS - PRESENT_AS_IT_HITS) * rising) * falling
        return Gust(slot, warned = ALWAYS, gusting = rising * falling, presence, veer)
    }

    companion object {
        val NONE = Gusts(seed = 0L, often = NEVER, force = NEVER, sudden = NEVER)

        /** The gusts of a gale over an Age of [ageSeed] with these dials. */
        fun of(ageSeed: Long, dials: GaleDials) = Gusts(
            seed = XoroshiroRandomSource(ageSeed xor SEED_SALT).nextLong(),
            often = dials.gusts,
            force = dials.force,
            sudden = dials.sudden,
        )

        val STREAM_CODEC: StreamCodec<ByteBuf, Gusts> = StreamCodec.composite(
            ByteBufCodecs.LONG,
            Gusts::seed,
            ByteBufCodecs.DOUBLE,
            Gusts::often,
            ByteBufCodecs.DOUBLE,
            Gusts::force,
            ByteBufCodecs.DOUBLE,
            Gusts::sudden,
            ::Gusts,
        )

        /** Sixteen seconds, long enough to hold the longest warning and the longest gust. */
        const val SLOT_TICKS = 320L

        /** At the most, a gust in four slots of five: one about every twenty seconds. */
        private const val MOST_SLOTS_GUSTING = 0.8

        private const val LONGEST_WARNING = 60.0f
        private const val SHORTEST_WARNING = 10.0f

        /** A gust lasts five seconds to six and a half, and up to three seconds more at the fiercest. */
        private const val SHORTEST_GUST = 100
        private const val LENGTH_SPREAD = 30
        private const val LONGER_AT_FIERCEST = 60.0
        private const val SLOWEST_RISE = 40.0
        private const val QUICKEST_RISE = 20.0
        private const val FALL_TICKS = 50.0

        /** How much of a gust there is to see and hear by the moment it hits. */
        private const val PRESENT_AS_IT_HITS = 0.6

        /** How far a gust may swing off the gale's bearing, either way. */
        private const val MOST_VEER = 20.0

        /**
         * A gust's strength as a multiple of the server's gale dial: at the default dial the gentlest is four times an
         * ordinary gale's lean, and the fiercest the most a push may be.
         */
        private const val GENTLEST = 1.0
        private const val FIERCEST = 2.5

        private const val NEVER = 0.0
        private const val ALWAYS = 1.0
        private const val TICKS_PER_SECOND = 20.0
        private const val SLOT_SPREAD = -0x61C8_8646_80B5_83EBL
        private const val SEED_SALT = 0x3D9A_51C4_7E02_B86FL
    }
}
