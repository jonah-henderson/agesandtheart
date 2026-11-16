package co.voik.agesandtheart.age.cataclysm

import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.codecs.RecordCodecBuilder

/** Which cataclysm ends an Age (design §5.3 "The cataclysm"). Only the asteroid is built. */
enum class CataclysmKind(val key: String) {
    ASTEROID("asteroid"),
    ;

    companion object {
        val CODEC: Codec<CataclysmKind> = Codec.STRING.comapFlatMap(
            { key -> entries.firstOrNull { it.key == key }?.let { DataResult.success(it) } ?: DataResult.error { "No cataclysm called $key" } },
            CataclysmKind::key,
        )
    }
}

/** The stages of an asteroid's end, in order, each running until the next begins. */
enum class ImpactPhase(val key: String) {
    /** A speck in the sky, growing. */
    APPROACH("approach"),

    /** Burning, and falling past the horizon. */
    ENTRY("entry"),

    /** The flash, the glow on the horizon, and the debris coming back over the sky toward you. */
    TRAVEL("travel"),

    /** The sky glows, and whatever it can see burns. */
    HEAT("heat"),

    /** The view closes in while the ground is scorched. */
    DUST("dust"),

    /** The impact winter: a dead grey world. */
    AFTER("after"),
}

/** How long each stage of an asteroid's end lasts, in ticks. */
data class ImpactTimeline(
    val approach: Int,
    val entry: Int,
    val travel: Int,
    val heat: Int,
    val dust: Int,
) {
    /** How long before [phase] begins, counted from the start of the approach. */
    fun startOf(phase: ImpactPhase): Int = when (phase) {
        ImpactPhase.APPROACH -> 0
        ImpactPhase.ENTRY -> approach
        ImpactPhase.TRAVEL -> approach + entry
        ImpactPhase.HEAT -> approach + entry + travel
        ImpactPhase.DUST -> approach + entry + travel + heat
        ImpactPhase.AFTER -> approach + entry + travel + heat + dust
    }

    companion object {
        /** Short enough to watch whole while the look is being worked out: two minutes from speck to strike. */
        val PROTOTYPE = ImpactTimeline(
            approach = 2 * TICKS_PER_MINUTE,
            entry = 15 * TICKS_PER_SECOND,
            travel = 75 * TICKS_PER_SECOND,
            heat = 45 * TICKS_PER_SECOND,
            dust = 90 * TICKS_PER_SECOND,
        )

        val CODEC: Codec<ImpactTimeline> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.INT.fieldOf("approach").forGetter(ImpactTimeline::approach),
                Codec.INT.fieldOf("entry").forGetter(ImpactTimeline::entry),
                Codec.INT.fieldOf("travel").forGetter(ImpactTimeline::travel),
                Codec.INT.fieldOf("heat").forGetter(ImpactTimeline::heat),
                Codec.INT.fieldOf("dust").forGetter(ImpactTimeline::dust),
            ).apply(instance, ::ImpactTimeline)
        }

        const val TICKS_PER_SECOND = 20
        private const val TICKS_PER_MINUTE = 60 * TICKS_PER_SECOND
    }
}

/**
 * One Age's end, as a schedule against the overworld's clock (Ages share it): [kind], the tick its approach
 * began, how long each stage lasts, and the bearing, clockwise from north, it comes down at.
 *
 * Everything else is arithmetic over these, on the server and on every client alike, so nothing about an
 * end in progress is stored or sent more than once.
 */
data class Doom(
    val kind: CataclysmKind,
    val startsAt: Long,
    val timeline: ImpactTimeline,
    val bearingDegrees: Float,
) {
    fun startOf(phase: ImpactPhase): Long = startsAt + timeline.startOf(phase)

    /** The moment it strikes: the flash, and from then on every link into the Age fails. */
    val impactAt: Long get() = startOf(ImpactPhase.TRAVEL)

    /** The moment the debris reaches overhead and the heat begins. */
    val arrivalAt: Long get() = startOf(ImpactPhase.HEAT)

    /** Which stage the end is in at [time], or null before it has begun. */
    fun phaseAt(time: Long): ImpactPhase? {
        if (time < startsAt) return null
        return ImpactPhase.entries.last { time >= startOf(it) }
    }

    /** How far through [phase] [time] is, from nought to one; nought before it and one after. */
    fun progressThrough(phase: ImpactPhase, time: Double): Double {
        val begins = startOf(phase).toDouble()
        val length = lengthOf(phase).toDouble()
        if (length <= 0.0) return if (time >= begins) 1.0 else 0.0
        return ((time - begins) / length).coerceIn(0.0, 1.0)
    }

    /** How long [phase] lasts, in ticks; the impact winter never ends. */
    fun lengthOf(phase: ImpactPhase): Long = when (phase) {
        ImpactPhase.APPROACH -> timeline.approach.toLong()
        ImpactPhase.ENTRY -> timeline.entry.toLong()
        ImpactPhase.TRAVEL -> timeline.travel.toLong()
        ImpactPhase.HEAT -> timeline.heat.toLong()
        ImpactPhase.DUST -> timeline.dust.toLong()
        ImpactPhase.AFTER -> Long.MAX_VALUE
    }

    /** Whether it has struck by [time]: whether the Age has ended and its links fail. */
    fun hasStruckBy(time: Long): Boolean = time >= impactAt

    /**
     * How thick the smoke and dust hang at [time], from nought to one: smoke rising from what burns through the heat,
     * the dust closing in soon after it settles, and some kept for ever in the impact winter.
     */
    fun dustAt(time: Double): Float = when (phaseAt(time.toLong())) {
        ImpactPhase.HEAT -> SMOKE_BY_THE_DUST * progressThrough(ImpactPhase.HEAT, time).toFloat()
        ImpactPhase.DUST -> {
            val closing = (progressThrough(ImpactPhase.DUST, time) * DUST_CLOSES_FASTER).coerceAtMost(1.0).toFloat()
            SMOKE_BY_THE_DUST + (WHOLLY - SMOKE_BY_THE_DUST) * closing
        }
        ImpactPhase.AFTER -> WINTER_DUST
        ImpactPhase.APPROACH, ImpactPhase.ENTRY, ImpactPhase.TRAVEL, null -> NO_DUST
    }

    /** Whether the dust is thick enough at [time] that the land can be scorched behind it unseen. */
    fun hidesTheScorchAt(time: Long): Boolean = phaseAt(time) == ImpactPhase.AFTER || dustAt(time.toDouble()) >= WHOLLY

    companion object {
        val CODEC: Codec<Doom> = RecordCodecBuilder.create { instance ->
            instance.group(
                CataclysmKind.CODEC.fieldOf("kind").forGetter(Doom::kind),
                Codec.LONG.fieldOf("starts_at").forGetter(Doom::startsAt),
                ImpactTimeline.CODEC.fieldOf("timeline").forGetter(Doom::timeline),
                Codec.FLOAT.fieldOf("bearing").forGetter(Doom::bearingDegrees),
            ).apply(instance, ::Doom)
        }

        /**
         * An asteroid whose approach begins at [startsAt], on the bearing an Age of [seed] draws: the same Age
         * is always struck from the same quarter.
         */
        fun asteroid(seed: Long, startsAt: Long, timeline: ImpactTimeline = ImpactTimeline.PROTOTYPE): Doom =
            Doom(CataclysmKind.ASTEROID, startsAt, timeline, bearingFor(seed))

        fun bearingFor(seed: Long): Float = (seed xor BEARING_SALT).mod(WHOLE_TURN).toFloat()

        private const val NO_DUST = 0.0f
        private const val WHOLLY = 1.0f
        private const val SMOKE_BY_THE_DUST = 0.5f
        private const val DUST_CLOSES_FASTER = 4.0
        private const val WINTER_DUST = 0.55f

        private const val WHOLE_TURN = 360L
        private const val BEARING_SALT = 0x3A57_E401_D5C1_7B2FL
    }
}
