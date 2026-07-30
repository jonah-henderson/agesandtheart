package co.voik.agesandtheart.worldgen.carver

import co.voik.agesandtheart.worldgen.SpireField
import kotlin.math.abs
import kotlin.math.pow
import net.minecraft.world.level.levelgen.XoroshiroRandomSource
import net.minecraft.world.level.levelgen.synth.NormalNoise

/**
 * Which rock survives being worn away, and which does not — the rule [ErosionCarver] applies.
 *
 * Kept apart from the carver because the rule is *pure*: whether a block goes depends only on where it is.
 * Nothing here touches a chunk, a registry or a running server, so the terrain preview can evaluate this
 * **exact object** offline and draw what it will do. What we look at therefore cannot drift from what
 * generates — a preview that reimplemented the rule would eventually lie about the very thing it was built
 * to judge.
 *
 * **On the name, and what is really being modelled.** This began as wind erosion and is no longer that, so
 * it is worth being straight about which parts are physics and which are invention:
 * - *Genuinely wind:* [windStretch]. Yardangs really are streamlined parallel to the prevailing wind, and
 *   that directional bias is what combs the rock into aligned fins rather than isotropic lumps.
 * - *Generic to all weathering:* the resistance threshold itself. Rock survives where it is tougher than
 *   whatever is working on it. Hoodoos are largely the work of water and frost, not wind; nothing about
 *   differential resistance belongs to any one agent.
 * - *Frankly unphysical:* the vertical profile. Real abrasion is fiercest near the ground and eases upward —
 *   monotonic — which yields gentle slopes, not spires. This instead spares a keel and attacks *both*
 *   extremes, which no natural process does, because it is what makes crowns above and hanging needles
 *   below. [needleBonus] is likewise a device for drama, not a mechanism.
 *
 * Hence `Weathering`: the umbrella term for rock breaking down in place, deliberately agnostic about the
 * agent, which is honest for a rule whose only agent-specific feature is a directional bias. The
 * wind-named parameters keep their names, since that part really is wind.
 *
 * On *why* it is shaped the way it is — a threshold judged per column and extruded, rather than a surface
 * sliding up one — see [ErosionCarver].
 *
 * **What the offline preview CANNOT show you, and it cost a whole tuning round.** The preview applies this rule
 * to the field's geometry, which is what makes it trustworthy about *shape* — but a carver in the game does not
 * cut on this rule alone. Every cut goes through vanilla's `WorldCarver.carveBlock`, which first asks
 * `canReplaceBlock(config, state)` — `state.is(config.replaceable)`. A block outside that tag is **never
 * removed, however hard the wind blows.**
 *
 * So a preview showing heavy erosion and a world showing an intact mass is not a contradiction: it means the
 * rock is not in the tag. That is exactly what happened when the Spire's substance became basalt, blackstone and
 * gravel — none of which is in `#minecraft:base_stone_overworld`, so the carver could cut nothing at all while
 * the preview looked right. The configured carvers now name `#agesandtheart:erodible`, which is ours to keep in
 * step with what a writer can put in the ground. **When the preview and the game disagree about how eroded
 * something is, check the tag before touching a number here.**
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
     * Over how many blocks above and below the keel the advantage decays — *separately* from the band.
     *
     * These are two different ideas and tying them together is a trap. The band must cover every block of
     * rock, or whatever it fails to reach is left floating above the gaps opened beneath it. The taper wants
     * to finish quickly, or crowns stay welded into a lump. A tall band with the taper measured against it
     * cannot do both; measured against its own reach, it can.
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

    /**
     * Whether the wind takes the block at this position.
     *
     * Kept alongside [cuts] rather than replaced by it: the offline preview reads *erosion* specifically,
     * and a name that says which agent is at work is worth more there than one that says only "cut".
     */
    fun erodes(worldX: Int, worldY: Int, worldZ: Int): Boolean =
        erodesGiven(worldX, worldY, worldZ, favour = 0.0)

    /**
     * The same question, with an extra [favour] the caller has worked out for itself.
     *
     * It exists for one reason: **this rule cannot see where an island is, and something has to.**
     * `Weathered` measures how thick the rock in a column stands and hands the answer back here, so a column deep
     * in an island's middle survives where a thin one on its rim does not — which is how the central landmass
     * holds together while the edges wear away. Kept as an argument rather than a field because it is a property
     * of a *place in a shape*, and this rule is deliberately ignorant of shapes.
     */
    fun erodesGiven(worldX: Int, worldY: Int, worldZ: Int, favour: Double): Boolean {
        if (worldY !in fromY..toY) return false
        val standing = profile(worldY) + favour + if (isNeedle(worldX, worldZ)) needleBonus else 0.0
        return resistanceAt(worldX, worldY, worldZ) + standing <= bite
    }

    /**
     * The same wind, blowing [lift] blocks higher up the world — the companion to
     * [co.voik.agesandtheart.worldgen.field.Raised].
     *
     * Only the three absolute heights move. The noise scales, the bite and the profile's own shape are all
     * *relative*, so raising them would change how the rock erodes rather than merely where. (The resistance
     * pattern does shift, since it is sampled at world `y` — which is a different noise field, not a different
     * character of erosion.)
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
     * How much of a *column's* survival bonus reaches this height — all of it at the keel, none at the taper reach.
     *
     * Without this, a bonus earned by a column being deep applied over the column's whole height, so a central
     * column survived from its root to its crown and came out a monolith. The point of favouring deep rock was
     * to hold the **deck** together, and a deck is a band, not a column — above it the wind should be as harsh as
     * anywhere. Fading over the same reach [profile] uses keeps the two in step: one leniency ridge at the keel,
     * not two arguing about where the middle is.
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
     * The rock's advantage at a given height: generous at and below the keel, falling away above it.
     *
     * This is the shape of a spire: a ridge of leniency at the keel that falls away in both directions.
     * A *rising* profile — survival growing with height — would broaden tops into plateaus and cut the bases
     * from under them, which reads as loose columns rather than spires on common ground. Sparing everything
     * below the keel instead is the other failure: it welds the whole underside into one lump.
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

        /**
         * The wind over the Spire archipelago. The band spans an island completely on purpose: one that
         * stopped short would leave the untouched remainder floating above the gaps it opened below.
         */
        // Chosen against the measured spread of the resistance noise, which runs about -1.0 to +0.8 with
        // its tenth and ninetieth percentiles near ∓0.39. Thresholds only mean something relative to that:
        // an earlier set sat around ±0.1, far too tight to select anything, so the profile did nothing and
        // roughly half of every column eroded regardless of height. `./gradlew :common:preview` prints the
        // distribution — read it before moving these.
        private const val BAND_MARGIN = 4

        val SPIRE = Weathering(
            // **Read from `SpireField` rather than written out, because they went stale once already.** The band
            // covers every block an island can occupy — nothing may be left untouched above a gap — and an
            // island's extent is the field's business, not the wind's. Hardcoded, these said 38..296 against a
            // deck at y=190; when the deck moved to 148 to buy a two-to-one split they silently described the
            // wrong world, and [keelY] with them. A margin below the floor is harmless (there is no rock there
            // to spare); a band that stops short of the ceiling is not.
            fromY = SpireField.SPIKE_FLOOR - BAND_MARGIN,
            toY = SpireField.PEAK_CEILING + BAND_MARGIN,
            // **0.085 → 0.28**: Jonah asked for erosion "aggressive enough that it is difficult to recognise the
            // primitives", and this is the dial the docs above call the most important by some way. Measured
            // against the wind switched off (`--args=spire-nowind`, 8.83M solid blocks), this now removes **74%**
            // of the mass where the old setting removed 65%.
            //
            // Raise it together with [atTheKeel]: their *difference* is the threshold at the deck, so moving one
            // alone either dissolves the slab or stops the tips eroding. `bite - atTheKeel = -0.38` is what keeps
            // a stable deck while the extremes are eaten.
            bite = 0.28,
            // Spared up to just above the deck, so the islands keep a body; punished hard toward the top.
            // [toY] matters more than it looks: the taper is measured against `toY - keelY`, so a band
            // reaching far above where rock actually stands leaves the taper only part-finished at the
            // summit — a solid mass with notches rather than spires. The preview prints where rock tops out.
            // **At the deck, which is the point of a keel.** It was 168 — twenty-two blocks below a deck that
            // then sat at 190 — and stayed there when the deck dropped, so the wind was sparing a band of open
            // sky and eating the slab. Tied to the deck now.
            keelY = SpireField.DECK_Y,
            atTheKeel = 0.66,
            atTheTip = -0.05,
            // Mirrored on the tip, so the underside narrows into hanging needles the way the crowns rise.
            atTheRoot = -0.15,
            taper = 0.5,
            // **Short, and shorter than they were** (57/51), because Jonah asked for "a relatively narrow stable
            // deck/slab in the middle" with the mass above and below worn to spires. The reach is how far the
            // keel's protection extends before the wind takes over, so it *is* the width of that stable slab —
            // shortening it is what makes the deck read as a deck rather than as a thick middle.
            taperReachAbove = 32,
            taperReachBelow = 28,
            // Pillar-scale, not island-scale: ~24-block features with crags at 12 and 6. An earlier
            // scale of 34 gave 270-block features, wider than an island, so each read as one smooth slope.
            scale = 0.9,
            windStretch = 2.0,
            // **60 → 22, and this is what finally made them read as spires.** Erosion cannot taper a column —
            // it keeps or removes whole ones — so at 60 the resistance barely drifted with height and every
            // survivor stood at its full field height with a flat top: a forest of rectangular pillars. At 22 the
            // threshold wanders enough vertically for a column to be taken in its upper reaches and spared lower
            // down, which is what puts a point on a spire and what stops the underlying noise heightmap reading
            // as a periodic row of teeth. Lower still starts closing hollows over into arches.
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
