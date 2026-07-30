package co.voik.agesandtheart.worldgen.field

import co.voik.agesandtheart.worldgen.carver.Weathering
import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder

/**
 * [base], worn away by a [Weathering] — erosion as part of the *shape* rather than as a carver.
 *
 * **Not a carver**, four ways over: a vanilla carver is a stateful walk where weathering is a pure
 * positional predicate; carving runs after `SURFACE`, so a cut is never re-skinned; a carver shares a
 * `CarvingMask` for cross-chunk caves where this decides each block alone; and `replaceable` is a fence a
 * wandering process needs and this does not — no tag can keep up with `terrain.stone` accepting any block
 * in the pack, and when the Spire's rock became basalt the carver silently cut nothing.
 *
 * As a field, `getBaseHeight` answers from the *eroded* rock and the surface system paints what the wind
 * left.
 *
 * **It wraps [base] rather than describing a cut to [Subtract]**, because erosion removes whole columns:
 * resistance is mostly a property of the column, so no change to the field alone can protect a centre.
 * Taking [base] as a child lets it measure how thick the column it stands in is — and a thick column *is*
 * an island's middle where a thin one is its rim — which needs no knowledge of placement and survives
 * jitter and variation for free.
 */
data class Weathered(
    val base: TerrainField,
    val weathering: Weathering,
    /**
     * How much a column standing in deep rock is favoured, over and above the vertical profile. Read
     * against the resistance spread `./gradlew :common:preview` prints, like every threshold here.
     */
    val coreBonus: Double,
    /** The column thickness earning the whole of [coreBonus]. Thinner columns earn a proportional share. */
    val coreThickness: Int,
    /**
     * How much a column reaching *high* is punished, away from the keel — what stops the central cone
     * reading as a cone. Possible only because this node takes [base] as a child: the same measurement
     * that yields depth yields the crown, in the same pass.
     */
    val crownPenalty: Double,
    /** How far above the keel a column must reach to earn the whole of [crownPenalty]. */
    val crownReach: Int,
) : TerrainField {
    override val kind = FieldKind.WEATHERED

    override val horizontalReach = base.horizontalReach

    // The base's own cost plus a walk of the band. Reported so [Intersect] and friends still order sensibly.
    override val samplesPerColumn =
        base.samplesPerColumn + (weathering.toY - weathering.fromY + 1).coerceAtLeast(0)

    override fun columnSpans(worldX: Int, worldZ: Int): Spans {
        val rock = base.columnSpans(worldX, worldZ)
        if (rock.ranges.isEmpty()) return Spans.EMPTY
        if (weathering.toY < weathering.fromY) return rock

        // How deep the rock stands here, which stands in for "how near the middle of an island this is". Summed
        // over every run rather than measured tip to tail: a column with two thin slabs and a gap is a rim, not a
        // core, and taking the outer extent would call it one.
        val standing = rock.ranges.sumOf { it.last - it.first + 1 }
        // Squared, so the favour is concentrated rather than spread: linearly, a column at half an island's
        // depth took half the bonus and erosion fell by half across the whole island.
        val depth = (standing.toDouble() / coreThickness).coerceAtMost(1.0)
        val favour = coreBonus * depth * depth

        // How far over the keel this column's crown reaches, which is what makes it part of the central cone
        // rather than of the deck around it. Read off the topmost run, the ranges being ascending throughout.
        val reachesOverKeel = (rock.ranges.last().last - weathering.keelY).coerceAtLeast(0)
        val loftiness = (reachesOverKeel.toDouble() / crownReach).coerceAtMost(1.0)
        val penalty = crownPenalty * loftiness

        // Walked as ascending runs so [Spans] needs no normalising pass, as [Noise3D] does it.
        val kept = ArrayList<IntRange>(EXPECTED_RUNS)
        var runStart: Int? = null
        for (range in rock.ranges) {
            for (y in range) {
                // Bonus and penalty are complementary about the keel, which is what keeps them from arguing:
                // at the deck a deep column is fully protected, and past the taper reach — where the profile
                // has stopped helping anyway — a lofty one is fully punished. See [Weathering.keelShare].
                val share = weathering.keelShare(y)
                val punishable = y >= weathering.keelY
                val here = favour * share - if (punishable) penalty * (1.0 - share) else 0.0
                val survives = y !in weathering.fromY..weathering.toY ||
                    !weathering.erodesGiven(worldX, y, worldZ, here)
                if (survives) {
                    if (runStart == null) runStart = y
                } else if (runStart != null) {
                    kept += runStart..y - 1
                    runStart = null
                }
            }
            // A gap in the base ends a run whatever the wind did, or two slabs would be welded into one.
            runStart?.let { kept += it..range.last }
            runStart = null
        }
        return Spans.ofAscending(kept)
    }

    /**
     * Resizing carries the child and leaves the weathering alone: a profile is tied to absolute heights,
     * and scaling them about a pivot is not *obviously* right. Erosion is applied once over a whole Age
     * rather than per instance, so no caller needs it — better plainly unscaled than quietly wrong.
     */
    override fun resized(factor: Double, pivotY: Int): TerrainField =
        copy(base = base.resized(factor, pivotY))

    companion object {
        private const val EXPECTED_RUNS = 8

        /** The Spire's weathering, configured — **the one place these numbers live.** */
        fun spire(base: TerrainField, lift: Int = 0) = Weathered(
            base, Weathering.SPIRE.raisedBy(lift), CORE_BONUS, CORE_THICKNESS, CROWN_PENALTY, CROWN_REACH,
        )

        /** How much a column standing in deep rock is favoured. Read against the printed resistance spread. */
        private const val CORE_BONUS = 0.40

        /**
         * The depth earning all of [CORE_BONUS] — near what is found *on an island's axis*, not its
         * average, which is what makes the favour reach only the centre. Much lower and every column on an
         * island clears it, so erosion halves everywhere.
         */
        private const val CORE_THICKNESS = 300

        /**
         * The cone's handicap, and the height over the keel earning all of it. Smaller than [CORE_BONUS]
         * deliberately: the cone should become hard to recognise, not be shaved flat. [CROWN_REACH] sits
         * near the tallest a column gets, so the handicap is a gradient rather than a cliff partway up.
         */
        private const val CROWN_PENALTY = 0.22
        private const val CROWN_REACH = 150

        /**
         * The weathering itself is **not** a codec field: `Weathering` carries eighteen tuned numbers, and
         * there is exactly one curated profile, so the codec names it implicitly. A second profile is the
         * moment this becomes a dispatch on a name, like [FieldKind].
         */
        fun codec(self: Codec<TerrainField>): MapCodec<Weathered> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                self.fieldOf("base").forGetter(Weathered::base),
                Codec.DOUBLE.fieldOf("core_bonus").forGetter(Weathered::coreBonus),
                Codec.INT.fieldOf("core_thickness").forGetter(Weathered::coreThickness),
                Codec.DOUBLE.fieldOf("crown_penalty").forGetter(Weathered::crownPenalty),
                Codec.INT.fieldOf("crown_reach").forGetter(Weathered::crownReach),
            ).apply(instance) { base, bonus, thickness, penalty, reach ->
                Weathered(base, Weathering.SPIRE, bonus, thickness, penalty, reach)
            }
        }
    }
}
