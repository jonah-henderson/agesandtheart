package co.voik.agesandtheart.worldgen.biome

import co.voik.agesandtheart.worldgen.field.TerrainField
import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import java.util.Optional
import kotlin.math.abs

/**
 * What the land says about where the ocean, the coast and the rivers are — **the option that lets an Age's
 * biomes agree with its shape**.
 *
 * By default they do not, and that is a decision rather than an oversight: an ocean biome on a hilltop and
 * water pooling in a desert are things this mod's worlds are allowed to do, and `notes/terrain-architecture.md`
 * records the call and the oasis it was read as. What that costs is the other kind of Age — one that means
 * to look like somewhere — because vanilla generates its terrain *from* continentalness and so agrees with
 * itself by construction, where we generate terrain first and then ask what grows.
 *
 * So this is per Age, and it is a lever both ways rather than a fix: an Age can be grounded or free, and
 * being free is not a defect to be corrected. (An Age whose *words* asked for one and whose shape gives the
 * other is the sort of thing §5's instability index could eventually price.)
 *
 * Three of vanilla's six climate parameters are enough:
 *
 * - **continentalness** decides ocean against coast against inland, and is read straight off how far the
 *   ground stands over the waterline;
 * - **erosion** means *how worn flat the ground is*, which is why it decides a sandy beach from a stony
 *   shore — `OverworldBiomeBuilder` files `stony_shore` at erosion −1.0..−0.2225 and `beach` above it, at
 *   the same continentalness. Passing vanilla's own erosion through leaves that to a noise that knows
 *   nothing about our shape, so a flat beach comes out stony about as often as not. Measured off the
 *   local fall instead, it says what it means.
 * - **weirdness** decides, among much else, where rivers run — vanilla files them in the *valley* band of
 *   its peaks-and-valleys curve, which is `|weirdness| < 0.05`. Rather than working backwards from that,
 *   a shape that carries its own rivers already knows where they are, so this simply asks it.
 */
data class Grounding(
    /** The shape whose surface decides ocean from inland. */
    val terrain: TerrainField,
    /** The level the sea stands at, which is what "over the water" is measured against. */
    val waterline: Int,
    /**
     * Water the shape carries itself — see `SeaFill.wet`. Where it stands over the ground, this is a river
     * rather than a sea, however low the channel bed happens to be.
     */
    val rivers: TerrainField? = null,
    /** Everything the landform said about itself — see [Declared]. */
    val declared: Declared = Declared(),
) {

    /**
     * **What a landform tells the biome layer about its own shape** — the one channel between the two, and
     * the only thing `Terrain` gets to say to `Grounding`.
     *
     * Each of these was a method of its own on `Terrain`, a `when` over every landform answering false for
     * all but one, threaded here as a separate argument. They are one subject: what this shape is like, in
     * the terms biomes need. A new fact is a field here rather than a fourth of everything.
     */
    data class Declared(
        /**
         * Whether this Age's coast is sand the whole way round, rather than whatever vanilla's noise
         * happened to file there.
         *
         * Vanilla mixes its shorelines on purpose: `addInlandBiomes` carries a beach at a coast only in its
         * *low* weirdness slice and the negative half of its *mid* ones, so about two coastal columns in
         * three grow a forest down to the water, and a coast worn less flat than `erosions[2]` is a stony
         * shore. That is coherent for vanilla because the same numbers made the ground. For a shape that
         * already has a flat sandy shelf it is a coin toss, and it reads as sand broken up by grass.
         */
        val hasSandyShores: Boolean = false,
        /**
         * Whether the level in [waterline] is a **river** rather than a sea — an Age whose only standing
         * water runs in a channel it cut for itself, and which therefore has no ocean anywhere.
         *
         * Without this such an Age reads as drowned: continentalness is measured against the waterline, so
         * every column the water covers stands *below* it and files as deep ocean, and a gorge with a river
         * along the bottom grows kelp. It cannot be inferred from [rivers], because water poured by a flat
         * level is carried by no field at all — which is the whole reason it needs saying.
         */
        val waterlineIsRiver: Boolean = false,
        /**
         * What this Age's *height* says about what grows — a treeline and a snowline. Null for a landform
         * with no relief worth speaking of, which is most of them; see [Elevation] for why a range cannot
         * do without it and a coast has no use for it.
         */
        val elevation: Elevation? = null,
    ) {
        companion object {
            /**
             * What an Age divided between several landforms declares, as one.
             *
             * **Age-wide because each of these is**: there is one waterline, one set of heights, and one
             * sea for a shore to meet — so a single island territory is enough to make the coast sand, and
             * two ranges disagreeing about their own snowline is not something a climate could express.
             * The first elevation wins for that reason rather than by accident.
             */
            fun of(all: List<Declared>) = Declared(
                hasSandyShores = all.any { it.hasSandyShores },
                waterlineIsRiver = all.any { it.waterlineIsRiver },
                elevation = all.firstNotNullOfOrNull { it.elevation },
            )

            val CODEC: Codec<Declared> = RecordCodecBuilder.create { instance ->
                instance.group(
                    Codec.BOOL.optionalFieldOf("sandy_shores", false).forGetter(Declared::hasSandyShores),
                    Codec.BOOL.optionalFieldOf("waterline_is_river", false)
                        .forGetter(Declared::waterlineIsRiver),
                    Elevation.CODEC.optionalFieldOf("elevation")
                        .forGetter { Optional.ofNullable(it.elevation) },
                ).apply(instance) { sandyShores, waterlineIsRiver, elevation ->
                    Declared(sandyShores, waterlineIsRiver, elevation.orElse(null))
                }
            }
        }
    }

    /**
     * One cache per chunk worker, for the same reason [BelowTerrain] keeps one: a biome is asked per quart
     * cell, so a chunk asks hundreds of times about sixteen distinct columns — and on a
     * [co.voik.agesandtheart.worldgen.field.Drainage] a column is a fifty-sample neighbourhood scan.
     */
    private val columnCache = ThreadLocal.withInitial { ColumnCache() }

    /** How far inland this column reads as — vanilla's own axis, answered by our shape. */
    fun continentalnessAt(blockX: Int, blockZ: Int): Float {
        val cache = columnCache.get()
        val slot = cache.slotFor(blockX, blockZ, this)
        // A river runs through the country it drains, whatever the height of its own bed would say. Without
        // this a valley deep enough to hold water reads as ocean, and grows kelp.
        if (cache.isRiver(slot)) return NEAR_INLAND
        // An Age with relief and no sea reads this off the relief — see [Elevation.continentalnessFor].
        declared.elevation?.let { return it.continentalnessFor(cache.surface(slot)) }
        return cache.continentalness(slot)
    }

    /**
     * How worn flat this column is — vanilla's erosion axis, answered by how steeply our own ground falls.
     *
     * The one climate reading here that needs more than the column itself: a slope is a difference between
     * two places. It costs two extra surface reads, taken a quart cell apart, which is the resolution a
     * biome is chosen at anyway.
     */
    fun erosionAt(blockX: Int, blockZ: Int): Float {
        val cache = columnCache.get()
        val slot = cache.slotFor(blockX, blockZ, this)
        val worn = cache.erosion(slot)
        // Sand rather than shingle: `erosions[2]` ends at −0.2225 and everything under it is a stony shore.
        return if (isSandyShore(cache, slot)) maxOf(worn, SANDY_ENOUGH) else worn
    }

    /**
     * Whether this column is a shore this Age means to be sand. A river mouth is not, however coastal it
     * reads — what grows at one is the sea's business, and the river branch has already said so.
     */
    private fun isSandyShore(cache: ColumnCache, slot: Int): Boolean =
        declared.hasSandyShores && !cache.isRiver(slot) && cache.continentalness(slot) in A_SHORE

    /** Where a fall of this steepness falls on vanilla's erosion axis. A curve through its bands, as above. */
    internal fun erosionOf(fall: Double): Float {
        if (fall <= FALLS.first()) return WORN.first()
        for (anchor in 1..<FALLS.size) {
            if (fall > FALLS[anchor]) continue
            val along = ((fall - FALLS[anchor - 1]) / (FALLS[anchor] - FALLS[anchor - 1])).toFloat()
            return WORN[anchor - 1] + (WORN[anchor] - WORN[anchor - 1]) * along
        }
        return WORN.last()
    }

    /**
     * Vanilla's weirdness, pushed **into** the valley band where a river runs and **out of it** where one
     * does not.
     *
     * The second half is the one that is easy to miss. `OverworldBiomeBuilder` files its whole valley
     * slice at `span(-0.05F, 0.05F)`, and at a coast that slice holds rivers and stony shores rather than
     * beaches — so a weirdness that lands in it *by chance* puts a river through dry sand. Left to
     * vanilla's noise that is a twentieth of every column, which along a shoreline reads as the beach
     * being broken up into patches.
     *
     * A grounded Age knows exactly where its rivers are, so anywhere else being in the valley band is
     * simply false, and nudging it clear costs nothing.
     */
    fun weirdnessAt(blockX: Int, blockZ: Int, otherwise: Float): Float {
        val cache = columnCache.get()
        val slot = cache.slotFor(blockX, blockZ, this)
        if (cache.isRiver(slot)) return IN_A_VALLEY
        // An Age with real relief answers this axis outright rather than nudging vanilla's noise off the
        // valley band: where a column sits between floor and crest **is** what the axis asks. See [Elevation].
        declared.elevation?.let { return it.weirdnessFor(cache.surface(slot), otherwise) }
        val outOfTheValley = clearOfTheValley(otherwise)
        return if (isSandyShore(cache, slot)) intoTheBeachSlice(outOfTheValley) else outOfTheValley
    }

    /**
     * How cold it is here once the climb is paid for — vanilla's temperature axis, answered by our own
     * height. An Age with no [Declared.elevation] leaves it exactly as the climate gave it.
     */
    fun temperatureAt(blockX: Int, blockZ: Int, otherwise: Float): Float {
        val lapse = declared.elevation ?: return otherwise
        val cache = columnCache.get()
        return lapse.chilled(cache.surface(cache.slotFor(blockX, blockZ, this)), otherwise)
    }

    /** [weirdness], moved off vanilla's valley band the way it was already leaning, and no further. */
    private fun clearOfTheValley(weirdness: Float): Float {
        if (abs(weirdness) >= CLEAR_OF_THE_VALLEY) return weirdness
        return if (weirdness < 0.0f) -CLEAR_OF_THE_VALLEY else CLEAR_OF_THE_VALLEY
    }

    /**
     * [weirdness] squeezed into the slice vanilla files coastal beaches in — sign and ordering kept, so the
     * pattern of biomes along the shore is the same one, drawn from a band that has sand in it.
     */
    private fun intoTheBeachSlice(weirdness: Float): Float {
        val along = (abs(weirdness) - CLEAR_OF_THE_VALLEY) / (1.0f - CLEAR_OF_THE_VALLEY)
        val squeezed = CLEAR_OF_THE_VALLEY + along * (BEACH_SLICE_TOP - CLEAR_OF_THE_VALLEY)
        return if (weirdness < 0.0f) -squeezed else squeezed
    }

    /**
     * Where this surface falls on vanilla's continentalness axis. A curve through its bands rather than a
     * set of thresholds, so a coast is a gradient and the surface rules either side of it blend.
     */
    /** This column's surface, or the waterline where there is no ground at all. */
    private fun surfaceAt(blockX: Int, blockZ: Int): Int =
        terrain.columnSpans(blockX, blockZ).highestSolidY ?: waterline

    internal fun continentalnessOf(surfaceY: Int): Float {
        val overTheWater = (surfaceY - waterline).toFloat()
        if (overTheWater <= HEIGHTS.first()) return BANDS.first()
        for (anchor in 1..<HEIGHTS.size) {
            if (overTheWater > HEIGHTS[anchor]) continue
            val along = (overTheWater - HEIGHTS[anchor - 1]) / (HEIGHTS[anchor] - HEIGHTS[anchor - 1])
            return BANDS[anchor - 1] + (BANDS[anchor] - BANDS[anchor - 1]) * along
        }
        return BANDS.last()
    }

    /**
     * The columns of one chunk, remembered while its biomes are laid out — the shape [BelowTerrain] uses,
     * down to why the index has to shift to the quart first. What is cached is the *answer* rather than the
     * spans, both readings being wanted together and neither being wanted afterwards.
     */
    private class ColumnCache {
        private val keys = LongArray(SLOTS) { EMPTY_KEY }
        private val continentalness = FloatArray(SLOTS)
        private val erosion = FloatArray(SLOTS)
        private val river = BooleanArray(SLOTS)
        private val surface = IntArray(SLOTS)

        fun slotFor(blockX: Int, blockZ: Int, grounding: Grounding): Int {
            val key = (blockX.toLong() shl Int.SIZE_BITS) or (blockZ.toLong() and UNSIGNED_INT)
            val slot = (((blockX shr QUART_BITS) and 3) shl 2) or ((blockZ shr QUART_BITS) and 3)
            if (keys[slot] == key) return slot
            val surface = grounding.terrain.columnSpans(blockX, blockZ).highestSolidY ?: grounding.waterline
            val standing = grounding.rivers?.columnSpans(blockX, blockZ)?.highestSolidY
            continentalness[slot] = grounding.continentalnessOf(surface)
            // The steeper of the two axes, so a ridge running one way is not read as flat ground.
            val eastward = grounding.surfaceAt(blockX + A_QUART, blockZ) - surface
            val northward = grounding.surfaceAt(blockX, blockZ + A_QUART) - surface
            erosion[slot] = grounding.erosionOf(maxOf(abs(eastward), abs(northward)).toDouble() / A_QUART)
            // **A river stops being one when it reaches the sea.** Carrying water over its bed is not
            // enough: where that water stands at or under the waterline, this is the sea the river runs
            // into, and what grows there is an ocean's business — vanilla picks *which* ocean off the
            // temperature axis, which passes through untouched, so a cold one gets a frozen one.
            //
            // Unless there is no sea to reach, in which case the level itself is the river and any ground
            // it covers is that river's bed. See [waterlineIsRiver].
            val runsOverItsOwnBed = standing != null && standing > surface && standing > grounding.waterline
            val liesUnderTheOnlyWater = grounding.declared.waterlineIsRiver && surface < grounding.waterline
            river[slot] = runsOverItsOwnBed || liesUnderTheOnlyWater
            this.surface[slot] = surface
            keys[slot] = key
            return slot
        }

        fun continentalness(slot: Int): Float = continentalness[slot]

        fun erosion(slot: Int): Float = erosion[slot]

        fun isRiver(slot: Int): Boolean = river[slot]

        fun surface(slot: Int): Int = surface[slot]

        private companion object {
            const val SLOTS = 16
            const val UNSIGNED_INT = 0xFFFF_FFFFL
            const val QUART_BITS = 2
            const val EMPTY_KEY = Long.MIN_VALUE

            /** How far apart the two probes are — one biome cell, the grain a biome is chosen at. */
            const val A_QUART = 4
        }
    }

    companion object {
        /**
         * How far the ground stands over the waterline, against where that puts it on vanilla's
         * continentalness axis.
         *
         * `OverworldBiomeBuilder` files the axis as deep ocean −1.05..−0.455, ocean ..−0.19, coast ..−0.11,
         * near inland ..0.03, mid inland ..0.3 and far inland ..1.0; these are points inside those bands.
         *
         * **The coast is a plateau, not a slope.** The two middle anchors hold the same value, so every
         * height from two under the water to eight over it reads as the dead centre of vanilla's coast
         * band. Sloping through it instead put the band's edge inside the beach's own few blocks of rise,
         * and a column a block lower than its neighbour flipped to ocean — which along a shoreline is sand
         * broken up by patches of something else.
         */
        private val HEIGHTS = floatArrayOf(-30.0f, -10.0f, -2.0f, 8.0f, 20.0f, 60.0f)
        private val BANDS = floatArrayOf(-0.7f, -0.3f, -0.15f, -0.15f, -0.04f, 0.5f)

        /**
         * How steeply the ground falls, against where that puts it on vanilla's erosion axis.
         *
         * `OverworldBiomeBuilder` bands it −1.0, −0.78, −0.375, −0.2225, 0.05, 0.45, 0.55, 1.0. **The
         * number that matters is −0.2225**: a coast above it is sand and below it is stone. A beach rising
         * four blocks in a hundred lands near the middle of the sandy side, and anything you would have to
         * climb lands well under it.
         */
        private val FALLS = doubleArrayOf(0.0, 0.15, 0.40, 0.80)
        private val WORN = floatArrayOf(0.45f, 0.10f, -0.35f, -0.85f)

        /** Inland enough for a river rather than an ocean, and no further. */
        private const val NEAR_INLAND = -0.04f

        /**
         * The middle of vanilla's *valley* band. Its peaks-and-valleys curve is
         * `-(||w| - 2/3| - 1/3) * 3`, and a valley is that below −0.85 — which solves to `|w| < 0.05`, so
         * zero is as valley as it gets.
         */
        private const val IN_A_VALLEY = 0.0f

        /**
         * And just outside it. Vanilla's valley slice is exactly `-0.05..0.05`, so this is the smallest
         * nudge that leaves it — anything larger would move columns that were never going to be rivers.
         */
        private const val CLEAR_OF_THE_VALLEY = 0.0501f

        /** Vanilla's coast band, which is the continentalness a shore reads at. */
        private val A_SHORE = -0.19f..-0.11f

        /**
         * The top of vanilla's *low* weirdness slice, `addInlandBiomes` filing it at ±0.05..0.26666668.
         * It is the only slice carrying a coastal beach on both sides of zero — the mid ones carry one
         * where weirdness is negative, and the high ones and the peaks carry none.
         */
        private const val BEACH_SLICE_TOP = 0.26f

        /** Clear of `erosions[2]`, which ends at −0.2225 and below which a coast is a stony shore. */
        internal const val SANDY_ENOUGH = -0.2f

        val CODEC: Codec<Grounding> = RecordCodecBuilder.create { instance ->
            instance.group(
                TerrainField.CODEC.fieldOf("terrain").forGetter(Grounding::terrain),
                Codec.INT.fieldOf("waterline").forGetter(Grounding::waterline),
                TerrainField.CODEC.optionalFieldOf("rivers")
                    .forGetter { Optional.ofNullable(it.rivers) },
                Declared.CODEC.optionalFieldOf("declared", Declared()).forGetter(Grounding::declared),
            ).apply(instance) { terrain, waterline, rivers, declared ->
                Grounding(terrain, waterline, rivers.orElse(null), declared)
            }
        }

        val MAP_CODEC: MapCodec<Grounding> = CODEC.fieldOf("grounding")
    }
}
