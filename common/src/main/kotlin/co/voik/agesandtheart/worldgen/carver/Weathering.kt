package co.voik.agesandtheart.worldgen.carver

import co.voik.agesandtheart.worldgen.SpireField
import kotlin.math.abs
import kotlin.math.pow
import net.minecraft.world.level.levelgen.XoroshiroRandomSource
import net.minecraft.world.level.levelgen.synth.NormalNoise

/**
 * Which rock survives being worn away, and which does not. Read by
 * [co.voik.agesandtheart.worldgen.field.Weathered], which subtracts it from the shape.
 *
 * The rule is **pure** — whether a block goes depends only on where it is — so the terrain preview
 * evaluates this exact object offline and cannot drift from what generates.
 *
 * What is physics and what is invention: [windStretch] is genuinely wind, since yardangs really are
 * streamlined along it; the resistance threshold is generic to all weathering; the vertical profile is
 * frankly unphysical, since real abrasion is monotonic and yields slopes, where this spares a keel and
 * attacks both extremes to make crowns above and hanging needles below. [needleBonus] likewise.
 *
 * **If a cut ever runs through a carver rather than the shape, check the `replaceable` tag before touching
 * a number here** — a block outside it is never removed however hard the wind blows, which once made the
 * preview and the game disagree completely.
 */
class Weathering(
    /** The band the wind reaches; rock outside it is untouched. */
    override val fromY: Int,
    override val toY: Int,
    /**
     * How much resistance the rock must beat to stand. The most important dial by some way: raise it and
     * a mass thins toward isolated towers, lower it and it fills back in solid.
     */
    val bite: Double,
    /**
     * The level rock is spared up to — the island's keel. At and below it the wind barely works, which is
     * what leaves a **shared landmass** for spires to rise out of instead of a field of loose columns.
     */
    val keelY: Int,
    /** How much the rock is favoured at the keel. Large: nearly everything there should stand. */
    val atTheKeel: Double,
    /** And how much it is punished at the very top. Negative, so a spire narrows to a point. */
    val atTheTip: Double,
    /**
     * How much it is punished at the very bottom of the band. Kept milder than [atTheTip]: an underside
     * should be sculpted into hanging spikes, not pared away as sharply as a crown.
     */
    val atTheRoot: Double,
    /**
     * How quickly the keel's advantage is given up. **Below 1** the change arrives early, so spires part
     * company just above the keel and have room to taper; above 1 it is held back so long that the whole
     * mass stays welded together nearly to the top.
     */
    val taper: Double,
    /**
     * Over how many blocks above and below the keel the advantage decays — **separately from the band**,
     * which is not a tidiness: the band must cover every block of rock or the remainder floats above the
     * gaps opened beneath it, where the taper must finish quickly or crowns weld into a lump.
     */
    val taperReachAbove: Int,
    val taperReachBelow: Int,
    /** Blocks per unit of noise. `8 × scale` is roughly the coarsest feature size at first octave -3. */
    val scale: Double,
    /**
     * Blocks per unit of noise *vertically*. Large keeps flanks near-sheer; smaller lets resistance wander
     * with height, so gaps can close over into overhangs and arches. Free to use here, unlike in a field —
     * the carver already judges every block separately, so the third dimension costs one argument.
     */
    val verticalScale: Double,
    /** How far the resistance pattern is drawn out along the wind axis, combing rock into fins. */
    val windStretch: Double,
    /**
     * Blocks per unit of noise for the *needle* pattern — the rare places the wind fails to touch. Fine, so
     * a needle is a few blocks across rather than a hill.
     */
    val needleScale: Double,
    /** How exceptional a place must be to become a needle. High: read it against the printed spread. */
    val needleThreshold: Double,
    /**
     * What a needle gets. Large enough to shrug off the taper entirely, so it keeps its full height while
     * everything around it is cut down — which is the only way one spire stands far above its neighbours.
     */
    val needleBonus: Double,
    val seed: Long,
    val firstOctave: Int,
    val amplitudes: DoubleArray,
) : CarvingRule {
    // Shared and immutable: resistance is a property of the rock in a place, not of a chunk.
    private val resistance = NormalNoise.create(XoroshiroRandomSource(seed), firstOctave, *amplitudes)

    // A separate, finer pattern picking out the few places that survive whatever the wind does.
    private val needles = NormalNoise.create(XoroshiroRandomSource(seed * 31 + 17), firstOctave, *amplitudes)

    override fun cuts(worldX: Int, worldY: Int, worldZ: Int): Boolean = erodes(worldX, worldY, worldZ)

    /** Whether the wind takes the block at this position. */
    fun erodes(worldX: Int, worldY: Int, worldZ: Int): Boolean =
        erodesGiven(worldX, worldY, worldZ, favour = 0.0)

    /**
     * The same question, with an extra [favour] the caller worked out for itself — because **this rule
     * cannot see where an island is, and something has to.** `Weathered` measures how thick a column's rock
     * stands and hands the answer back, so a column deep in an island survives where a thin one on the rim
     * does not. An argument rather than a field, since this rule is deliberately ignorant of shapes.
     */
    fun erodesGiven(worldX: Int, worldY: Int, worldZ: Int, favour: Double): Boolean {
        if (worldY !in fromY..toY) return false
        val standing = profile(worldY) + favour + if (isNeedle(worldX, worldZ)) needleBonus else 0.0
        return resistanceAt(worldX, worldY, worldZ) + standing <= bite
    }

    /**
     * The same wind, blowing [lift] blocks higher — the companion to
     * [co.voik.agesandtheart.worldgen.field.Raised]. Only the three absolute heights move; the scales, the
     * bite and the profile are relative, so raising them would change *how* the rock erodes.
     */
    fun raisedBy(lift: Int): Weathering {
        if (lift == 0) return this
        return Weathering(
            fromY = fromY + lift, toY = toY + lift, bite = bite, keelY = keelY + lift,
            atTheKeel = atTheKeel, atTheTip = atTheTip, atTheRoot = atTheRoot, taper = taper,
            taperReachAbove = taperReachAbove, taperReachBelow = taperReachBelow,
            scale = scale, verticalScale = verticalScale, windStretch = windStretch,
            needleScale = needleScale, needleThreshold = needleThreshold, needleBonus = needleBonus,
            seed = seed, firstOctave = firstOctave, amplitudes = amplitudes,
        )
    }

    /**
     * How much of a *column's* survival bonus reaches this height — all of it at the keel, none at the
     * taper reach. Without it a deep column survives root to crown and comes out a monolith: the point of
     * favouring deep rock is to hold the **deck** together, and a deck is a band, not a column.
     */
    fun keelShare(worldY: Int): Double {
        val reach = (if (worldY >= keelY) taperReachAbove else taperReachBelow).coerceAtLeast(1)
        val away = (abs(worldY - keelY).toDouble() / reach).coerceIn(0.0, 1.0)
        return 1.0 - away
    }

    /** Whether this column is one of the rare places that keeps its full height, top and bottom. */
    fun isNeedle(worldX: Int, worldZ: Int): Boolean =
        needles.getValue(worldX / needleScale, 0.0, worldZ / needleScale) > needleThreshold

    /**
     * How well the rock here holds out. Mostly a property of the *column*, which is what leaves sheer
     * flanks rather than slopes; [verticalScale] admits just enough drift with height to spoil the perfect
     * verticality and let hollows close over.
     */
    fun resistanceAt(worldX: Int, worldY: Int, worldZ: Int): Double =
        resistance.getValue(worldX / (scale * windStretch), worldY / verticalScale, worldZ / scale)

    /**
     * The rock's advantage at a given height — a ridge of leniency at the keel falling away in both
     * directions, which is the shape of a spire. A *rising* profile broadens tops into plateaus and cuts
     * their bases away; sparing everything below the keel welds the underside into one lump.
     */
    private fun profile(worldY: Int): Double {
        val rising = worldY >= keelY
        val reach = (if (rising) taperReachAbove else taperReachBelow).coerceAtLeast(1)
        // Clamped, so past the reach the rock simply stays at its harshest rather than easing off again.
        val away = (abs(worldY - keelY).toDouble() / reach).coerceIn(0.0, 1.0)
        val edge = if (rising) atTheTip else atTheRoot
        return atTheKeel + (edge - atTheKeel) * away.pow(taper)
    }

    companion object {
        /** Nothing erodes — for previewing a field's own shape with the wind switched off. */
        val NONE = Weathering(
            fromY = 0, toY = 1, bite = -Double.MAX_VALUE,
            keelY = 0, atTheKeel = 0.0, atTheTip = 0.0, atTheRoot = 0.0, taper = 1.0,
            taperReachAbove = 1, taperReachBelow = 1,
            scale = 1.0, windStretch = 1.0, verticalScale = 1.0,
            needleScale = 1.5, needleThreshold = Double.MAX_VALUE, needleBonus = 0.0,
            seed = 0L, firstOctave = -1, amplitudes = doubleArrayOf(1.0),
        )

        // A margin below the floor is harmless; a band stopping short of the ceiling is not.
        private const val BAND_MARGIN = 4

        /**
         * The wind over the Spire archipelago.
         *
         * These thresholds only mean anything against the measured spread of the resistance noise, which
         * runs about -1.0 to +0.8 with its tenth and ninetieth percentiles near ∓0.39.
         * `./gradlew :common:preview` prints the distribution — **read it before moving any of them.**
         */
        val SPIRE = Weathering(
            // Read from `SpireField` rather than written out, because an island's extent is the field's
            // business and hardcoding these left them silently describing the wrong world once already.
            fromY = SpireField.SPIKE_FLOOR - BAND_MARGIN,
            toY = SpireField.PEAK_CEILING + BAND_MARGIN,
            // Raise this together with [atTheKeel]: their *difference* is the threshold at the deck, so
            // moving one alone either dissolves the slab or stops the tips eroding.
            bite = 0.28,
            // At the deck, which is the point of a keel — a keel below it spares open sky and eats the slab.
            keelY = SpireField.DECK_Y,
            atTheKeel = 0.66,
            atTheTip = -0.05,
            // Mirrored on the tip, so the underside narrows into hanging needles the way the crowns rise.
            atTheRoot = -0.15,
            taper = 0.5,
            // How far the keel's protection extends before the wind takes over, so this *is* the width of
            // the stable deck.
            taperReachAbove = 32,
            taperReachBelow = 28,
            // Pillar-scale, not island-scale: ~24-block features with crags at 12 and 6. A scale of 34 gave
            // 270-block features, wider than an island, so each read as one smooth slope.
            scale = 0.9,
            windStretch = 2.0,
            // Erosion keeps or removes whole columns rather than tapering them, so this is what puts a
            // point on a spire: enough vertical drift for a column to be taken high and spared low. Much
            // larger gives a forest of flat-topped rectangular pillars; lower closes hollows into arches.
            verticalScale = 22.0,
            needleScale = 1.5,
            needleThreshold = 0.62,
            needleBonus = 0.75,
            seed = 0xE205_10AL,
            firstOctave = -3,
            amplitudes = doubleArrayOf(1.0, 0.5, 0.25),
        )
    }
}
