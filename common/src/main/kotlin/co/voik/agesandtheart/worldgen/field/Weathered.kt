package co.voik.agesandtheart.worldgen.field

import co.voik.agesandtheart.worldgen.SizeScale
import co.voik.agesandtheart.worldgen.Weathering
import com.mojang.datafixers.util.Either
import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
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
    /**
     * How far under the rock above it the wind reaches at all, in blocks. **Zero lets it reach any
     * depth**, which is what an archipelago wants — an island is thin enough to be worked right through.
     *
     * A solid world is not. Without this, erosion in one riddles the whole mass with pockets, because
     * resistance is positional and cannot tell an exposed face from bedrock a hundred blocks in. With it,
     * the wind only ever sculpts what is already open to the air — and the walk skips everything deeper,
     * which is most of the world.
     */
    val shelterReach: Int = NO_SHELTER,
    /** How much a block just short of [shelterReach] is favoured, tapering to nothing at the surface. */
    val shelterBonus: Double = 0.0,
    /**
     * The level at which rock is roofed by the world rather than open to the air. A run reaching it has no
     * surface for the weather to work back from, so it stands whole and the walk skips it entirely.
     *
     * [Spans.HIGHEST_Y] by default, which nothing reaches — an archipelago has open sky over every island
     * and wants no such rule. A world filled to its own ceiling is the case this exists for: without it the
     * weather pits a roof nothing can ever stand on, and does it for every column in the world.
     */
    val roofY: Int = Spans.HIGHEST_Y,
) : TerrainField {
    override val kind = FieldKind.WEATHERED

    override val horizontalReach = base.horizontalReach

    // The base's own cost plus a walk of the band. Reported so [Intersect] and friends still order sensibly.
    override val samplesPerColumn =
        base.samplesPerColumn + (weathering.toY - weathering.fromY + 1).coerceAtLeast(0)

    /**
     * The columns of a chunk and the ring around it, remembered — the same shape and the same reason as
     * [MountainRange]'s, one node further out.
     *
     * **A cache on the child is not enough**, which is the thing worth knowing: a chunk asks for the same
     * column from four directions — the fill, `Grounding`'s probes, `ClimateDepth` and the carvers — and
     * every one of those asks the *outermost* field. Memoising underneath saves the child's work and leaves
     * this walk of the whole band to be repeated in full each time.
     *
     * Cheap for the presets whose base is a single noise sample, and the difference between a playable Age
     * and an unplayable one where it is not.
     */
    private val memo = ColumnMemo(::weather)

    override fun columnSpans(worldX: Int, worldZ: Int): Spans = memo.spansAt(worldX, worldZ)

    private fun weather(worldX: Int, worldZ: Int): Spans {
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
            if (range.last >= roofY) {
                kept += range
                continue
            }
            for (y in range) {
                // How far this block lies under the rock above it in its own run — its own surface rather
                // than the column's, so a block under an overhang is judged by what actually covers it.
                val buried = range.last - y
                val outOfReach = shelterReach > NO_SHELTER && buried >= shelterReach
                // Bonus and penalty are complementary about the keel, which is what keeps them from arguing:
                // at the deck a deep column is fully protected, and past the taper reach — where the profile
                // has stopped helping anyway — a lofty one is fully punished. See [Weathering.keelShare].
                val share = weathering.keelShare(y)
                val punishable = y >= weathering.keelY
                val sheltered = if (shelterReach <= NO_SHELTER) 0.0 else
                    shelterBonus * (buried.toDouble() / shelterReach).coerceIn(0.0, 1.0)
                val here = favour * share - (if (punishable) penalty * (1.0 - share) else 0.0) + sheltered
                val survives = outOfReach || y !in weathering.fromY..weathering.toY ||
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

    /**
     * The whole weathered landform at another size: the rock, and the weather with it — its band, its grain,
     * and every depth it measures shelter and crowns by. For a landform whose size *is* a resize of itself.
     */
    fun sized(factor: Double, pivotY: Int): Weathered = copy(
        base = base.resized(factor, pivotY),
        weathering = weathering.resized(factor, pivotY),
        coreThickness = scaled(coreThickness, factor).coerceAtLeast(1),
        crownReach = scaled(crownReach, factor).coerceAtLeast(1),
        shelterReach = if (shelterReach <= NO_SHELTER) shelterReach else scaled(shelterReach, factor).coerceAtLeast(1),
        roofY = if (roofY >= Spans.HIGHEST_Y) roofY else scaledAbout(roofY, factor, pivotY),
    )

    companion object {
        private const val EXPECTED_RUNS = 8

        /** The wind reaches all the way down, however thick the rock. */
        const val NO_SHELTER = 0

        /** The Spire's weathering, configured — **the one place these numbers live.** */
        fun spire(base: TerrainField) = Weathered(
            base, Weathering.SPIRE, CORE_BONUS, CORE_THICKNESS, CROWN_PENALTY, CROWN_REACH,
        )

        /**
         * Weather that works an **exposed face** — the shape every landform but the archipelago wants.
         *
         * [coreBonus] and [crownPenalty] are zero throughout: both measure a column against an island,
         * and in a world of solid rock every column is as thick as the world and answers the same. The
         * whole of the discrimination is [shelterReach], and the profile in [weathering] decides which
         * heights the weather is fiercest at.
         */
        fun sculpting(
            base: TerrainField,
            weathering: Weathering,
            shelterReach: Int,
            roofY: Int = Spans.HIGHEST_Y,
        ) = Weathered(
            base,
            weathering,
            coreBonus = 0.0,
            coreThickness = 1,
            crownPenalty = 0.0,
            crownReach = 1,
            shelterReach = shelterReach,
            shelterBonus = SHELTER_BONUS,
            roofY = roofY,
        )

        /**
         * What a block at the full reach is favoured by — comfortably past the resistance noise's own
         * floor, so the taper reaches nothing rather than merely thinning towards it. One number for
         * every sculpting profile: what varies between them is *how deep* the weather reaches, not how
         * completely it gives up at the bottom.
         */
        private const val SHELTER_BONUS = 1.5

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
         * The profile is named rather than written out: `Weathering` carries nineteen tuned numbers, and
         * a recipe naming them all would be a copy of ours that could never be retuned. The dispatch is
         * what its being implicit was always waiting on — a second curated profile.
         */
        private val NAMED_CODEC: Codec<Weathering> = Codec.STRING.comapFlatMap(
            { key ->
                val profile = Weathering.named(key)
                if (profile == null) DataResult.error { "no weathering called '$key'" } else DataResult.success(profile)
            },
            Weathering::key,
        )

        /** A named profile [resized][Weathering.resized] with its landform, said as the name and the resize. */
        private val SIZED_CODEC: Codec<Weathering> = RecordCodecBuilder.create<Pair<Weathering, Pair<Double, Int>>> { instance ->
            instance.group(
                NAMED_CODEC.fieldOf("key").forGetter { it.first },
                Codec.DOUBLE.fieldOf("sized_by").forGetter { it.second.first },
                Codec.INT.fieldOf("sized_about").forGetter { it.second.second },
            ).apply(instance) { named, sizedBy, sizedAbout -> named to (sizedBy to sizedAbout) }
        }.xmap(
            { (named, resize) -> named.resized(resize.first, resize.second) },
            { sized -> sized to (sized.sizedBy to sized.sizedAbout) },
        )

        private val WEATHERING_CODEC: Codec<Weathering> = Codec.either(NAMED_CODEC, SIZED_CODEC).xmap(
            { either -> either.map({ it }, { it }) },
            { profile -> if (profile.sizedBy == SizeScale.ORDINARY) Either.left(profile) else Either.right(profile) },
        )

        fun codec(self: Codec<TerrainField>): MapCodec<Weathered> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                self.fieldOf("base").forGetter(Weathered::base),
                WEATHERING_CODEC.fieldOf("weathering").forGetter(Weathered::weathering),
                Codec.DOUBLE.fieldOf("core_bonus").forGetter(Weathered::coreBonus),
                Codec.INT.fieldOf("core_thickness").forGetter(Weathered::coreThickness),
                Codec.DOUBLE.fieldOf("crown_penalty").forGetter(Weathered::crownPenalty),
                Codec.INT.fieldOf("crown_reach").forGetter(Weathered::crownReach),
                Codec.INT.optionalFieldOf("shelter_reach", NO_SHELTER).forGetter(Weathered::shelterReach),
                Codec.DOUBLE.optionalFieldOf("shelter_bonus", 0.0).forGetter(Weathered::shelterBonus),
                Codec.INT.optionalFieldOf("roof_y", Spans.HIGHEST_Y).forGetter(Weathered::roofY),
            ).apply(instance, ::Weathered)
        }
    }
}
