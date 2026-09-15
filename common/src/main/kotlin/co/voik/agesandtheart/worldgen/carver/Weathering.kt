package co.voik.agesandtheart.worldgen.carver

import co.voik.agesandtheart.worldgen.AlpsField
import co.voik.agesandtheart.worldgen.CanyonField
import co.voik.agesandtheart.worldgen.CanyonlandsField
import co.voik.agesandtheart.worldgen.CliffField
import co.voik.agesandtheart.worldgen.CraterlandsField
import co.voik.agesandtheart.worldgen.RiverlandsField
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
 */
class Weathering(
    /** What a recipe names this profile by — see [named]. */
    val key: String,
    /** The band the wind reaches; rock outside it is untouched. */
    val fromY: Int,
    val toY: Int,
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
) {
    // Shared and immutable: resistance is a property of the rock in a place, not of a chunk.
    private val resistance = NormalNoise.create(XoroshiroRandomSource(seed), firstOctave, *amplitudes)

    // A separate, finer pattern picking out the few places that survive whatever the wind does.
    private val needles = NormalNoise.create(XoroshiroRandomSource(seed * 31 + 17), firstOctave, *amplitudes)

    /**
     * Whether the wind takes the block at this position, given an extra [favour] the caller worked out for
     * itself — because **this rule cannot see where an island is, and something has to.** `Weathered`
     * measures how thick a column's rock stands and hands the answer back, so a column deep in an island
     * survives where a thin one on the rim does not. An argument rather than a field, since this rule is
     * deliberately ignorant of shapes.
     */
    fun erodesGiven(worldX: Int, worldY: Int, worldZ: Int, favour: Double): Boolean {
        if (worldY !in fromY..toY) return false
        val standing = profile(worldY) + favour + if (isNeedle(worldX, worldZ)) needleBonus else 0.0
        return resistanceAt(worldX, worldY, worldZ) + standing <= bite
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
            key = "spire",
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

        /**
         * The weather in a canyon, which is water and frost rather than wind — and so a **different
         * mechanism from [SPIRE]'s**, not a retuning of it.
         *
         * The Spire's numbers all serve one question: how thick is this column, and how far is it above
         * the deck? In a world that is solid rock every column is as thick as the world and every plateau
         * column reaches the ceiling, so both measures answer the same thing everywhere and discriminate
         * nothing. What discriminates in a canyon is **how deeply buried a block is under its own
         * surface** — which is why this profile leans on `Weathered`'s shelter and leaves the core bonus
         * and crown penalty at zero.
         *
         * The vertical profile spares the gorge and attacks the rim, so the benches break up into buttes
         * and spurs near the top while the river's valley keeps its walls.
         */
        val CANYON = Weathering(
            key = "canyon",
            // Read from `CanyonField` for the same reason the Spire reads from `SpireField`. Below the
            // floor is the eighty blocks of bedrock the canyon deliberately leaves whole.
            fromY = CanyonField.FLOOR_Y,
            toY = CanyonField.WORLD_CEILING,
            // Against a resistance spread running about ∓0.39 at the tenth and ninetieth percentiles, this
            // takes well over half of an exposed face and almost nothing a dozen blocks behind it — the
            // steep falloff being `Weathered`'s shelter rather than anything here.
            bite = 0.05,
            // At the floor, so the profile's protection is strongest in the gorge and weakest at the rim —
            // the reverse of a spire, where the keel is the middle of the band.
            keelY = CanyonField.FLOOR_Y,
            atTheKeel = 0.35,
            atTheTip = -0.12,
            // Nothing lies below the keel: the band starts there.
            atTheRoot = 0.0,
            taper = 1.0,
            taperReachAbove = CanyonField.WORLD_CEILING - CanyonField.FLOOR_Y,
            taperReachBelow = 1,
            // Alcove-scale rather than pillar-scale: features some tens of blocks across, so a bench is
            // broken into spurs rather than sanded or bitten in half.
            scale = 1.6,
            windStretch = 1.0,
            // Large, so the rock is taken or spared in whole vertical faces. Small values would drill
            // horizontal pockets into a cliff, which reads as damage rather than as erosion.
            verticalScale = 48.0,
            needleScale = 1.5,
            needleThreshold = 0.7,
            needleBonus = 0.6,
            seed = 0xCA_1907_D15EL,
            firstOctave = -3,
            amplitudes = doubleArrayOf(1.0, 0.5, 0.25),
        )

        /**
         * The weather on a sea cliff, and **the shape every later table-and-face landform reuses**: a
         * protected tabletop, a fiercely worked face under it, and a protected floor under that.
         *
         * [taper] is what makes it a table rather than a ridge. A linear profile eases off gently either
         * way from the keel, which would leave the plateau's own surface half-eroded; cubed, the middle of
         * the band stays at [atTheKeel] nearly all the way out and only swings to the edges at the very
         * top and bottom. So the tableland stays walkable and the face still gets bitten hard.
         */
        val CLIFFS = Weathering(
            key = "cliffs",
            // The band starts at the waterline: below it the face is under thirty blocks of ocean, and
            // weather nobody can see is only a cost.
            fromY = CliffField.SEA_LEVEL,
            toY = CliffField.PLATEAU_Y + BAND_MARGIN,
            bite = 0.05,
            keelY = (CliffField.SEA_LEVEL + CliffField.PLATEAU_Y) / 2,
            // Negative at the keel and strongly positive at the edges: the reverse of a spire, whose keel
            // is the thing being spared.
            atTheKeel = -0.25,
            // High enough that the tableland comes out rocky and pitted rather than cratered — read it
            // against the shelter reach, which is what bounds how deep any one pit goes.
            atTheTip = 0.45,
            // Milder than the tip, so the foot of the cliff keeps a little working — sea caves and notches
            // where the waves would be.
            atTheRoot = 0.3,
            taper = 2.5,
            taperReachAbove = (CliffField.PLATEAU_Y - CliffField.SEA_LEVEL) / 2,
            taperReachBelow = (CliffField.PLATEAU_Y - CliffField.SEA_LEVEL) / 2,
            // Buttress-scale: gullies and ribs some tens of blocks across, rather than a sanded face.
            scale = 1.2,
            windStretch = 1.0,
            // Small against the other profiles', because here the reach is shallow: over ten blocks a
            // large one takes all or nothing, and what a rocky surface wants is part of a column.
            verticalScale = 20.0,
            needleScale = 1.5,
            // Rare and strongly favoured, which is what leaves a stack standing off a headland.
            needleThreshold = 0.72,
            needleBonus = 0.7,
            seed = 0xC11FF_A1EL,
            firstOctave = -3,
            amplitudes = doubleArrayOf(1.0, 0.5, 0.25),
        )

        /**
         * And the same shape over a mesa country — [CLIFFS]' profile against a shorter face, since a
         * canyonlands wall is a hundred and forty blocks rather than a hundred and seventy.
         *
         * Not [CLIFFS] shifted up or down: the two spans differ, so a shift would put the keel and the
         * reaches in the wrong places and tie two presets' heights together for good.
         */
        val CANYONLANDS = Weathering(
            key = "canyonlands",
            fromY = CanyonlandsField.FLOOR_Y,
            toY = CanyonlandsField.PLATEAU_Y + BAND_MARGIN,
            bite = 0.05,
            keelY = (CanyonlandsField.FLOOR_Y + CanyonlandsField.PLATEAU_Y) / 2,
            atTheKeel = -0.15,
            atTheTip = 0.9,
            // Gentler than a sea cliff's foot: a canyon floor is where the rubble goes, not where it is cut.
            atTheRoot = 0.45,
            taper = 2.5,
            taperReachAbove = (CanyonlandsField.PLATEAU_Y - CanyonlandsField.FLOOR_Y) / 2,
            taperReachBelow = (CanyonlandsField.PLATEAU_Y - CanyonlandsField.FLOOR_Y) / 2,
            scale = 1.2,
            windStretch = 1.0,
            verticalScale = 56.0,
            needleScale = 1.5,
            // More common and better rewarded than a sea stack's: a hoodoo standing in a canyon is the
            // one thing this landform is named for.
            needleThreshold = 0.68,
            needleBonus = 0.8,
            seed = 0xE5A_B0DEL,
            firstOctave = -3,
            amplitudes = doubleArrayOf(1.0, 0.5, 0.25),
        )

        /**
         * A river country's weather, and **the mildest of these by a long way**. The others are working a
         * face; this is only roughening a surface, so what it wants is a small bite over a shallow reach —
         * a hillside gone lumpy and a valley side gone ragged, not alcoves and buttes.
         *
         * The keel sits at the waterline and spares it, so the beds the rivers run down are left alone:
         * erosion in a channel only deepens a pool nobody can see the bottom of.
         */
        val RIVERLANDS = Weathering(
            key = "riverlands",
            fromY = RiverlandsField.WATERLINE - BAND_MARGIN,
            toY = RiverlandsField.LAND_Y + RiverlandsField.RELIEF.toInt() + BAND_MARGIN,
            bite = -0.18,
            keelY = RiverlandsField.WATERLINE,
            atTheKeel = 0.4,
            atTheTip = 0.0,
            atTheRoot = 0.4,
            taper = 1.5,
            taperReachAbove = RiverlandsField.LAND_Y + RiverlandsField.RELIEF.toInt() - RiverlandsField.WATERLINE,
            taperReachBelow = 1,
            // Boulder-scale rather than landform-scale: this is texture on a hillside.
            scale = 0.7,
            windStretch = 1.0,
            // Small, so a column loses part of itself rather than all of it — which over a shallow reach
            // is the difference between a rough surface and a field of postholes.
            verticalScale = 12.0,
            needleScale = 1.5,
            needleThreshold = 0.75,
            needleBonus = 0.4,
            seed = 0x21_5EA5_D1EL,
            firstOctave = -3,
            amplitudes = doubleArrayOf(1.0, 0.5, 0.25),
        )

        /**
         * A mountain range's weather — **frost, and the one profile here whose subject is the high ground.**
         *
         * The others work a face that a landform put there. This one works the *tops*: rock above the
         * snowline is shattered by freeze and thaw and shed as scree, while a valley floor two hundred
         * blocks below is under soil and does nothing of the sort. So the keel sits at the valley floors and
         * spares them, the punishment lands at the tip, and the reach above the keel is long enough to cover
         * the whole climb.
         *
         * [needleThreshold] and [needleBonus] earn more here than anywhere but the Spire: a rock that shrugs
         * off the frost while its ridge is cut down around it is a gendarme, which is a thing alpine ridges
         * are actually made of.
         */
        val ALPS = Weathering(
            key = "alps",
            fromY = AlpsField.WORLD_FLOOR + BAND_MARGIN,
            toY = AlpsField.SNOWLINE_Y + SUMMITS_ABOVE_THE_SNOWLINE,
            // Firmer than a river country's and far gentler than a canyon's: the shape already has its
            // large forms, and what is wanted is damage rather than sculpture.
            bite = -0.05,
            keelY = ALPINE_VALLEY_FLOOR,
            atTheKeel = 0.5,
            atTheTip = 0.0,
            atTheRoot = 0.5,
            taper = 2.0,
            // Long above and short below, because the climb is where the subject is. A symmetric reach
            // would spend half of itself on bedrock nobody sees.
            taperReachAbove = AlpsField.SNOWLINE_Y - ALPINE_VALLEY_FLOOR,
            taperReachBelow = 40,
            // Gully-and-buttress scale: ribs and couloirs some tens of blocks across, not a sanded face.
            scale = 1.0,
            windStretch = 1.0,
            // Large against the shelter reach, so resistance holds over most of a face and a whole rib
            // survives or goes rather than the surface coming out pitted.
            verticalScale = 30.0,
            needleScale = 1.5,
            needleThreshold = 0.74,
            needleBonus = 0.6,
            seed = 0xA_1BE_D0CL,
            firstOctave = -3,
            amplitudes = doubleArrayOf(1.0, 0.5, 0.25),
        )

        /**
         * The weather on an impact structure — **the only profile here whose subject is a shape nothing
         * eroded**, which is what decides everything about it.
         *
         * The others are finishing a landform water or frost already made, so they can afford to be
         * gentle. This one is undoing the fact that a crater rim is a cone and a crater bowl is half an
         * ellipsoid: left alone, every surface here is exactly the arithmetic that drew it. What breaks
         * that is the resistance noise being read in **world** coordinates, so a perfectly circular crest
         * is worked differently at every bearing and stops reading as a circle without anything having to
         * know it was one.
         *
         * The vertical profile is [ALPS]' — a keel low down and the punishment landing at the top —
         * because the subject is the high ground. The plain keeps its craters, the basin floor keeps its
         * shore, and the hundred and twenty blocks of wall between them is what gets ribbed.
         *
         * **The keel sits at the waterline rather than at the plain**, which is twenty-one blocks lower
         * than it looks like it should be. At the plain, the whole lower wall was inside the flat of the
         * taper and came out as smooth as the ellipsoid that cut it; from the waterline the wall is
         * worked all the way up from the shore. The plain is only a fifth of the reach above the keel and
         * the taper is superlinear, so it keeps nearly all of its protection anyway — which is the thing
         * that makes the lower keel affordable at all.
         */
        val CRATERLANDS = Weathering(
            key = "craterlands",
            fromY = CraterlandsField.BOWL_FLOOR_Y - BAND_MARGIN,
            toY = CraterlandsField.RIM_CREST_Y + BAND_MARGIN,
            // Between a river country's texture and a range's damage. The face wants sculpting; the plain
            // around it wants no more than a roughening, and one profile has to do both — so this is set
            // against the *wall*, and the keel is what keeps the plain out of it.
            bite = -0.02,
            keelY = CraterlandsField.WATERLINE,
            // Enough that only the softest twentieth of the plain loses its top block, so the crater rims
            // come out ragged rather than eaten.
            atTheKeel = 0.45,
            atTheTip = -0.05,
            // The basin floor is a shore or a sea bed. Working it only deepens water nobody sees through.
            atTheRoot = 0.4,
            // Above one, so the plain's protection is given up slowly and nearly all of the loss lands on
            // the upper half of the wall. Linear would have the crater field paying for the rim's texture.
            taper = 1.6,
            taperReachAbove = CraterlandsField.RIM_CREST_Y - CraterlandsField.WATERLINE,
            taperReachBelow = CraterlandsField.WATERLINE - CraterlandsField.BOWL_FLOOR_Y,
            // Rib-and-gully scale against a hundred-block wall: features some tens of blocks across, so
            // the crest breaks into spurs rather than being sanded smooth or bitten through.
            scale = 0.9,
            windStretch = 1.0,
            /*
             * **Small on purpose, and it is the one dial here that tells a wall from a plain.** The
             * vertical profile cannot: the basin wall runs from the waterline to the crest and the plain
             * sits at 84, so the two overlap in height and anything keyed to height alone works both or
             * neither. Resistance drifting quickly with height is different — on a near-vertical face a
             * column passes through several bands of it and comes out fluted, where on flat ground it
             * passes through one and comes out as it was. The steep half of this landform gets the
             * texture and the crater field does not pay for it.
             */
            verticalScale = 10.0,
            needleScale = 1.5,
            // A rock that shrugs the weather off while the crest is cut down around it leaves a tower on
            // the rim, which is the one thing here that could not have been placed.
            needleThreshold = 0.72,
            needleBonus = 0.6,
            seed = 0xC_2A_7E_2EDL,
            firstOctave = -3,
            amplitudes = doubleArrayOf(1.0, 0.5, 0.25),
        )

        /** How far over the snowline the band still has to reach, since the tallest massifs stand clear. */
        private const val SUMMITS_ABOVE_THE_SNOWLINE = 80

        /** Where an alpine trunk valley runs, which is the level the frost is asked to spare. */
        private const val ALPINE_VALLEY_FLOOR = 70

        /** The profiles a recipe may name, which is what makes [Weathered] serialisable. */
        private val BY_KEY = listOf(SPIRE, CANYON, CLIFFS, CANYONLANDS, RIVERLANDS, ALPS, CRATERLANDS)
            .associateBy(Weathering::key)

        /** The profile [key] names, or null for one this version does not have. */
        fun named(key: String): Weathering? = BY_KEY[key]
    }
}
