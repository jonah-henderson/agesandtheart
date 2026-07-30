package co.voik.agesandtheart.worldgen.field

import co.voik.agesandtheart.worldgen.carver.Weathering
import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder

/**
 * [base], worn away by a [Weathering] — the erosion pass as part of the *shape* rather than as a carver.
 *
 * **Why this is not a carver** (Jonah, 2026-07-29: *"it seems like perhaps we are shoehorning carvers into
 * something they are not really designed to do"*). He was right, four times over:
 *
 * - A vanilla carver is a **stateful walk** — `CaveWorldCarver` has rooms, tunnels and a `CarveSkipChecker`.
 *   Weathering is a *pure positional predicate*, which is why the offline preview can evaluate it.
 * - Carving runs at `ChunkStatus.CARVERS`, **after `SURFACE`**, so a cut exposes raw rock and is never
 *   re-skinned. Right for a cave wall, wrong for a spire's crown.
 * - A carver shares a `CarvingMask` so a cave crosses chunk boundaries. Weathering decides each block alone.
 * - `CarverConfiguration.replaceable` is a **fence**, because a wandering process must not eat what it should
 *   not. Weathering wants no fence — and that cost a day: `terrain.stone` accepts any block in the pack, so no
 *   tag can keep up, and when the Spire's rock became basalt the carver silently cut *nothing* while the preview
 *   looked perfect.
 *
 * Being a field instead means `getBaseHeight` answers from the *eroded* rock — so structures and arrival footing
 * stop being placed against stone erosion later removes — the surface system paints what the wind left, and the
 * previewer has nothing left to be blind to.
 *
 * ## Why it wraps its input instead of describing a cut
 *
 * The first version was the *removed* rock, meant to sit inside a [Subtract]. Jonah killed it with one question:
 * *"that won't help entirely though because erosion removes entire columns, right?"* — asked of a plan to spare
 * the central landmass by thickening the deck there.
 *
 * He was right. [Weathering.resistanceAt] is *mostly* a property of the column; `verticalScale` admits only
 * enough drift to spoil perfect verticality, so across a deck a few blocks thick a column's fate is essentially
 * decided once. Thickening the deck just gives the wind a taller thing to punch through. What actually spares
 * rock is its resistance beating the threshold, and the threshold is uniform horizontally — so *no* change to the
 * field alone can protect a centre.
 *
 * A survival bonus can, but erosion is deliberately blind to where islands are. It need not be blind to the
 * **rock**: by taking [base] as a child, this can measure how thick the column it is standing in happens to be,
 * and a thick column *is* an island's middle while a thin one is its rim. That needs no knowledge of placement,
 * survives jitter and variation for free, and is what `SpireField` already claimed — "a thick middle is what lets
 * spires stand and a thinning rim is what keeps the edges low."
 */
data class Weathered(
    val base: TerrainField,
    val weathering: Weathering,
    /**
     * How much a column standing in deep rock is favoured, over and above the vertical profile.
     *
     * Read against the printed resistance spread (`./gradlew :common:preview`), like every other threshold here:
     * the noise runs about -0.9 to +0.8 with its deciles near ∓0.36, so a bonus of a few tenths is the difference
     * between a rim that wears through and a middle that holds.
     */
    val coreBonus: Double,
    /** The column thickness earning the whole of [coreBonus]. Thinner columns earn a proportional share. */
    val coreThickness: Int,
    /**
     * How much a column reaching *high* is punished, away from the keel — Jonah's *"maybe apply a survival
     * penalty to higher columns"*, asked of the central cone still reading too much like a cone.
     *
     * I had written that off as impossible: a per-column height is not something a pure `(x, y, z)` rule can
     * know. It became possible the moment this node took [base] as a child — the same measurement that yields
     * depth yields the crown, for free and in the same pass.
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
        // **Squared, so the favour is concentrated rather than spread.** Linearly, a column carrying half an
        // island's depth took half the bonus, and near the axis almost everything clears the threshold — so
        // erosion fell by half across the whole island, which is the opposite of "only towards the centre".
        // Squaring makes the mid-radius share small (a quarter at half depth) while the deepest rock keeps it all.
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
     * Resizing carries the child and leaves the weathering alone.
     *
     * A profile is tied to absolute heights — a keel, a band, reaches either side of it — and those describe one
     * island at one altitude. Scaling them about a pivot might be right; it is not *obviously* right, and since
     * erosion is applied once over a whole Age rather than per instance, no caller needs it. Better plainly
     * unscaled than quietly wrong.
     */
    override fun resized(factor: Double, pivotY: Int): TerrainField =
        copy(base = base.resized(factor, pivotY))

    companion object {
        private const val EXPECTED_RUNS = 8

        /**
         * The Spire's weathering, configured — **the one place these numbers live.**
         *
         * They were briefly repeated in `AgeGeneration` and in the terrain preview, with a comment excusing it on
         * the grounds that a disagreement would show up as the preview differing from the game. That is exactly
         * the failure this whole node exists to have made impossible, so excusing a fresh instance of it was
         * indefensible. One factory, both callers.
         */
        fun spire(base: TerrainField, lift: Int = 0) = Weathered(
            base, Weathering.SPIRE.raisedBy(lift), CORE_BONUS, CORE_THICKNESS, CROWN_PENALTY, CROWN_REACH,
        )

        /**
         * How much a column standing in deep rock is favoured, and the depth earning all of it.
         *
         * Jonah's ask: *"keep a bit more of the central landmass together, but only towards the centre. the edges
         * can and should wear away."* Read against the resistance spread the previewer prints — the noise runs
         * about -0.9 to +0.8 with its deciles near ∓0.36 — so a few tenths is the difference between a rim that
         * wears through and a middle that holds.
         *
         * [CORE_THICKNESS] is what makes it *only* the centre. Set near an island's full depth rather than at some
         * modest slab height: at 60 almost every column on an island cleared it, so everything got the whole bonus
         * and erosion fell by half everywhere — the opposite of the ask.
         */
        private const val CORE_BONUS = 0.40

        /**
         * Deliberately near the depth found *on an island's axis*, not its average.
         *
         * Tuned twice and both misses are instructive. At 60 almost every column on an island cleared it, so the
         * whole island took the full bonus and erosion halved everywhere. At 170 it was still too generous —
         * because `CENTRAL_SHARE` heaps the cone over 85% of the radius, so depth stopped being a proxy for
         * *centre* and mid-radius columns came out monolithic. Set against the axis, the gradient is steep enough
         * that a rim column earns nothing worth having.
         */
        private const val CORE_THICKNESS = 300

        /**
         * The cone's handicap, and the height over the keel earning all of it.
         *
         * Sized against the same resistance spread as everything else here, and smaller than [CORE_BONUS] on
         * purpose: the cone is meant to become hard to recognise, not to be shaved flat. [CROWN_REACH] is set
         * near the tallest a column gets — `SpireField.CENTRAL_RISE` over the deck — so the handicap is a
         * gradient across the cone rather than a cliff partway up it.
         */
        private const val CROWN_PENALTY = 0.22
        private const val CROWN_REACH = 150

        /**
         * The weathering itself is **not** in the codec, and that is deliberate.
         *
         * `Weathering` carries eighteen tuned numbers whose values are argued for in its own KDoc; writing them
         * out as codec fields would invite a datapack to set them without any of that context and would have to
         * be kept in step by hand. There is exactly one curated profile, so the codec names it implicitly. A
         * second profile is the moment this becomes a dispatch on a name — the shape [FieldKind] already uses.
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
