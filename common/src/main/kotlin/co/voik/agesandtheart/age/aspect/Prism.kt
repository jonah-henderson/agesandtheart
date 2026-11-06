package co.voik.agesandtheart.age.aspect

import co.voik.ephemeris.sky.ColourCycles
import co.voik.ephemeris.sky.HueCycle
import co.voik.ephemeris.sky.SkySpec
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Colours turning round the colour wheel over time — `prismatic`, drawn by Ephemeris ([HueCycle]). Each part that holds
 * a colour turns on a cycle of its own, which is the same cycle for all of them unless a word says otherwise: the same
 * Age draws the same pace for every part, so they turn in step, and `swift prismatic sun` puts the sun on its own.
 */
object Prism {

    /** Whether this part's colours turn round the wheel over time. */
    val CYCLING = Parameter.flag("cycling", help = "Whether its colours turn round the colour wheel over time.")

    /**
     * Whether the light itself turns, which recolours every block at once — so only where a word names the air, never
     * from a `prismatic` laid bare (Jonah).
     */
    val LIGHT_CYCLING = Parameter.flag("light_cycling", help = "Whether the light itself turns round the colour wheel.")
        .onlyWhenAimed()

    /**
     * How long a full turn takes: from a day at -0.6 to two and a half minutes at 0.6, which an unsaid one is drawn
     * between, and further each way for `slow` and `swift` (Jonah).
     */
    val CYCLE_PACE = Parameter.ranged(
        "cycle_pace",
        help = "How fast its colours turn round the wheel.",
        landmarks = listOf(
            Parameter.Landmark(-1.0, "three days a turn"),
            Parameter.Landmark(UNSAID_SLOWEST, "a day a turn"),
            Parameter.Landmark(UNSAID_FASTEST, "two and a half minutes a turn"),
            Parameter.Landmark(1.0, "a minute a turn"),
        ),
    )

    /** What turns in an Age of [parts] whose sky is [sky], each part on its own cycle. */
    fun cyclesIn(parts: AgeParts, sky: SkySpec, seed: Long): ColourCycles {
        fun cycleOf(aspect: Aspect, tinge: Float, member: Int = 0): HueCycle? {
            val options = parts.optionsFor(aspect, member)
            return if (options.isTrue(CYCLING)) HueCycle(periodOf(options, seed), tinge = tinge) else null
        }
        val air = parts.optionsFor(Aspect.AIR)
        val bodies = sky.bodies.indices.mapNotNull { index ->
            val isASun = sky.bodies[index].phase == null
            val among = sky.bodies.take(index).count { (it.phase == null) == isASun }
            val cycle = cycleOf(if (isASun) Aspect.SUN else Aspect.MOON, BODY_TINGE, among) ?: return@mapNotNull null
            index to cycle
        }.toMap()
        return ColourCycles(
            sky = cycleOf(Aspect.SKY, SKY_TINGE),
            fog = cycleOf(Aspect.AIR, SKY_TINGE),
            cloud = cycleOf(Aspect.CLOUD, CLOUD_TINGE),
            light = if (air.isTrue(LIGHT_CYCLING)) HueCycle(periodOf(air, seed), tinge = LIGHT_TINGE) else null,
            waterFog = cycleOf(Aspect.WATERS, WATER_TINGE),
            stars = cycleOf(Aspect.STARS, BODY_TINGE),
            aurora = cycleOf(Aspect.AURORA, NO_TINGE),
            bodies = bodies,
            // The mesh's colours only turn, since every untinted block is a grey a tinge would colour. Grass and leaves
            // turn as one in it, since a shader can tell them apart only by layer, the same as the ground's.
            ground = cycleOf(Aspect.GRASS, NO_TINGE) ?: cycleOf(Aspect.LEAVES, NO_TINGE),
            water = cycleOf(Aspect.WATERS, NO_TINGE),
        )
    }

    /** How many ticks a full turn takes where [options] say how fast, or where this Age's [seed] draws it. */
    fun periodOf(options: Options, seed: Long): Int = periodAt(options.steer(CYCLE_PACE, seed) ?: unsaidPaceOf(seed))

    /** One draw for the whole Age, so every part left unsaid turns in step. */
    private fun unsaidPaceOf(seed: Long): Double = Random(seed xor PACE_SALT).nextDouble(UNSAID_SLOWEST, UNSAID_FASTEST)

    /** The period at [pace], evenly in its logarithm between the landmarks, so each step is the same multiple. */
    fun periodAt(pace: Double): Int {
        val held = pace.coerceIn(-1.0, 1.0)
        val (from, to) = PERIODS.zipWithNext().first { (_, upper) -> held <= upper.first }
        val along = (held - from.first) / (to.first - from.first)
        return exp(ln(from.second) + along * (ln(to.second) - ln(from.second))).roundToInt()
    }

    private const val UNSAID_SLOWEST = -0.6
    private const val UNSAID_FASTEST = 0.6

    private const val TICKS_A_MINUTE = 1_200.0
    private const val TICKS_A_DAY = 24_000.0

    /** The pace axis's landmarks and the ticks a turn takes at each. */
    private val PERIODS = listOf(
        -1.0 to TICKS_A_DAY * 3,
        UNSAID_SLOWEST to TICKS_A_DAY,
        UNSAID_FASTEST to TICKS_A_MINUTE * 2.5,
        1.0 to TICKS_A_MINUTE,
    )

    // How far a pale colour of each part is tinged toward the turn's hue (HueCycle.tinge): the sky and fog are
    // coloured already, clouds and the bodies mostly white, and the light gently, since it lights every block.
    private const val SKY_TINGE = 0.3f
    private const val CLOUD_TINGE = 0.5f
    private const val BODY_TINGE = 0.6f
    private const val WATER_TINGE = 0.4f
    private const val LIGHT_TINGE = 0.35f
    private const val NO_TINGE = 0.0f

    private const val PACE_SALT = 0x7A15_4C3E_9B0D_21F7L
}
