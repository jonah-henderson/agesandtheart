package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.age.aspect.Phenomenon
import co.voik.agesandtheart.math.mix64
import co.voik.agesandtheart.math.unitDouble
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.server.MinecraftServer
import kotlin.math.roundToInt

/**
 * How the ground gives way — **datapack content** (`art/phenomenon/tectonics.json`).
 *
 * Two dials and a weighting. [reach] is how far a swathe spreads and [pace] is how fast it goes once the
 * warning is over; each shape spends the first however it likes, and the warning itself is not on either
 * axis — three seconds of unmistakable fracture is the mechanism rather than a difficulty setting.
 */
data class TectonicsBehaviour(
    /**
     * How far one cave-in reaches from where it began, in blocks.
     *
     * **A radius rather than an area**, so doubling it is four times the ground. Each [CollapseShape]
     * spends it as its own shape wants: a fissure's length, a bowl's radii, a bolt's whole span, a shaft's
     * depth. A swathe's other dimensions are drawn from it, so this is the one number that says how big.
     */
    val reach: Int = DEFAULT_REACH,
    /**
     * How long the falling takes, against the walked one — under one is quicker, over one is slower.
     *
     * It moves three things together because moving any one alone is visible as the wrong thing: the
     * stagger between neighbouring blocks, how long a block spends on each stage of its crumbling, and how
     * often the swathe is rescanned for what the last course exposed. Dropping only the stages leaves the
     * pauses between courses untouched, which reads as a collapse hesitating rather than a quicker one.
     */
    val pace: Double = DEFAULT_PACE,
    /**
     * How often each shape comes up, as weights among themselves — a shape left out never happens.
     *
     * Read in the enum's own order rather than the map's, so the draw is a pure function of a cave-in's
     * seed and not of how a hash happened to lay the keys out this run.
     */
    val shapes: Map<CollapseShape, Int> = DEFAULT_SHAPES,
    /** What the greatest instability makes of the two dials — see [TectonicsFury]. */
    val fury: TectonicsFury = TectonicsFury.ORDINARY,
) {

    /** How far a swathe reaches in an Age this far into [TectonicsDials.size]. */
    fun reachAt(size: Double): Int =
        lerp(reach.toDouble(), this.fury.reach.toDouble(), size).roundToInt().coerceAtLeast(SMALLEST_REACH)

    /** And how fast it falls, this far into [TectonicsDials.speed]. */
    fun paceAt(speed: Double): Double =
        lerp(pace, this.fury.pace, speed).coerceAtLeast(QUICKEST_PACE)

    /**
     * Which shape a cave-in of this seed takes.
     *
     * Off the seed rather than off the level's random, so a cave-in reloaded mid-collapse goes on cutting
     * the shape it was cutting.
     */
    fun shapeDrawnFrom(seed: Long): CollapseShape {
        val weighted = CollapseShape.entries.mapNotNull { shape ->
            val weight = shapes[shape] ?: NEVER
            if (weight > NEVER) shape to weight else null
        }
        if (weighted.isEmpty()) return CollapseShape.FISSURE
        val total = weighted.sumOf { it.second }
        var drawn = (unitDouble(mix64(seed xor SHAPE_SALT)) * total).toInt().coerceIn(0, total - 1)
        for ((shape, weight) in weighted) {
            drawn -= weight
            if (drawn < 0) return shape
        }
        return weighted.last().first
    }

    companion object {
        /** Straight between the ordinary number and the furious one; [howFar] is clamped by its caller. */
        private fun lerp(ordinary: Double, furious: Double, howFar: Double): Double =
            ordinary + (furious - ordinary) * howFar

        private const val DEFAULT_REACH = 24
        private const val DEFAULT_PACE = 1.0

        /** Small enough to be silly, large enough that no shape divides by a zero size. */
        private const val SMALLEST_REACH = 4

        /** A stage is already a tick at the walked pace, so nothing is gained below this. */
        private const val QUICKEST_PACE = 0.25

        private const val NEVER = 0

        /** So the shape draw and the stagger cannot come off the same bits of one seed. */
        private const val SHAPE_SALT = 0x5CA1E5L

        /**
         * The fissure is the commonest because it is the one that reads best on the most ground, and the
         * square is one in a hundred because a thing that unnatural stops being unnatural if it is common.
         */
        private val DEFAULT_SHAPES = mapOf(
            CollapseShape.FISSURE to 30,
            CollapseShape.ELLIPSOID to 20,
            CollapseShape.BOLT to 18,
            CollapseShape.CRESCENT to 14,
            CollapseShape.RING to 9,
            CollapseShape.SHAFT to 8,
            CollapseShape.SQUARE to 1,
        )

        /** What a tectonic Age nobody tuned does, so an absent file is a default rather than a dead one. */
        val ORDINARY = TectonicsBehaviour()

        val CODEC: Codec<TectonicsBehaviour> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.INT.optionalFieldOf("reach", DEFAULT_REACH).forGetter(TectonicsBehaviour::reach),
                Codec.DOUBLE.optionalFieldOf("pace", DEFAULT_PACE).forGetter(TectonicsBehaviour::pace),
                Codec.unboundedMap(CollapseShape.CODEC, Codec.INT).optionalFieldOf("shapes", DEFAULT_SHAPES)
                    .forGetter(TectonicsBehaviour::shapes),
                TectonicsFury.CODEC.optionalFieldOf("fury", TectonicsFury.ORDINARY)
                    .forGetter(TectonicsBehaviour::fury),
            ).apply(instance, ::TectonicsBehaviour)
        }

        private val FILE = PhenomenonFile(Phenomenon.TECTONICS, CODEC, ORDINARY)

        /** What this server currently says a cave-in does. */
        fun of(server: MinecraftServer): TectonicsBehaviour = FILE.of(server)
    }
}

/** What the two dials become in an Age whose instability bought every step of the register. */
data class TectonicsFury(
    /** How far a swathe reaches at the worst — twice the walked one is four times the ground. */
    val reach: Int = DEFAULT_FURY_REACH,
    /** And how fast it falls at the worst. */
    val pace: Double = DEFAULT_FURY_PACE,
) {
    companion object {
        private const val DEFAULT_FURY_REACH = 48
        private const val DEFAULT_FURY_PACE = 0.45

        val ORDINARY = TectonicsFury()

        val CODEC: Codec<TectonicsFury> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.INT.optionalFieldOf("reach", DEFAULT_FURY_REACH).forGetter(TectonicsFury::reach),
                Codec.DOUBLE.optionalFieldOf("pace", DEFAULT_FURY_PACE).forGetter(TectonicsFury::pace),
            ).apply(instance, ::TectonicsFury)
        }
    }
}
