package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.FieldYield
import co.voik.agesandtheart.worldgen.field.Glaciation
import co.voik.agesandtheart.worldgen.field.MountainRange
import co.voik.agesandtheart.worldgen.field.SurfacingStrategy
import co.voik.agesandtheart.worldgen.field.RangeProfile
import co.voik.agesandtheart.worldgen.field.SeaFill
import co.voik.agesandtheart.worldgen.field.TerrainField
import co.voik.agesandtheart.worldgen.field.Weathered

/**
 * An alpine range: a foreland plain, foothills climbing out of it, and a glaciated crest behind them —
 * the Alps at about **one to sixteen**.
 *
 * **Why that ratio and not a rounder one.** True size is unreachable and not worth reaching for: the
 * vertical budget is 368 blocks once the underground is given up, so a 3,800-metre range can only be
 * compressed, and horizontal is what has to come with it if the *slopes* are to stay honest. Sixteen is
 * what makes both ends fit — Mont Blanc's 4,808 m lands near y=300 with headroom under the ceiling, and
 * major valley floors near 500–1,000 m land where a river can run. It is also as far as compression can go
 * before the thing stops reading: much more and a mountain is small enough to see all of at once, much less
 * and it will not fit.
 *
 * What that buys, and it is the whole point of scaling both axes together: **every hillside in this Age
 * stands at the angle real rock stands at.** Nothing is exaggerated, so nothing looks it.
 *
 * The range front to the far foreland is about 5,000 blocks — a genuine walk, and short enough that a
 * player who sets out for the crest arrives. See [MountainRange] for how the landform is derived at all,
 * and `notes/terrain-architecture.md` for the models behind it.
 */
object MountainousField {

    /**
     * The ground, weathered — a firmer hand than a river country's and a much lighter one than a canyon's.
     * The shape already has its large forms; what this adds is frost damage on the high faces.
     */
    fun world(salt: Long = 0L, scale: Double = SizeScale.ORDINARY): TerrainField =
        Weathered.sculpting(bareWorld(salt, scale), Weathering.MOUNTAINOUS, SHELTER_REACH)

    /**
     * The range before the weather reaches it — the previewer's other half, and nothing else's.
     *
     * [scale] is [SizeScale]'s factor, and it is the range's **footprint**: massifs further apart, broader
     * troughs and cirques, longer strike. Every height stays, and the grade with it, so the drainage decides
     * how tall the peaks get — a small range tops out low because its hillslopes meet early, and a big one
     * climbs until the ice caps it, which is where the tuned range already stands.
     */
    fun bareWorld(salt: Long = 0L, scale: Double = SizeScale.ORDINARY): MountainRange = MountainRange(
        // Where the rock starts, not where the valleys bottom out: any higher leaves void under the Age.
        floorY = VerticalWindow.MIN_Y,
        profile = profile(salt, scale),
        glaciation = glaciation(salt, scale),
        spacing = SPACING * scale,
        jitter = JITTER,
        seed = RANGE_SEED xor salt,
        hillslopeGrade = HILLSLOPE_GRADE,
        incision = INCISION,
        incisionPerOrder = INCISION_PER_ORDER,
        roughness = ROUGHNESS,
        roughnessStretch = ROUGHNESS_STRETCH * scale,
        waterDepth = WATER_DEPTH,
    )

    /**
     * The water standing in the valleys, for `SeaFill.wet` — the same network asked the other question, as
     * a river country's is. A single waterline cannot pour an alpine drainage: its trunks run at y≈70 in
     * the core and reach the foreland at y≈40, so a plane meets it only where it happens to cross.
     */
    fun water(salt: Long = 0L, scale: Double = SizeScale.ORDINARY): TerrainField =
        bareWorld(salt, scale).copy(describes = FieldYield.WATER)

    /**
     * Where the ranges lie and how they climb.
     *
     * **A network of them, not one belt.** The ranges run along the zero contours of a noise, which are long
     * sinuous curves that occasionally meet and enclose irregular cells — so the country is ranges with
     * basins between, and a walk out of the mountains ends on a plain rather than at the far side of a
     * wedge. [RangeProfile.grainAlong] against [RangeProfile.grainAcross] draws those cells out along the
     * strike, which is what makes a range a *range* rather than one wall of a honeycomb.
     */
    fun profile(salt: Long = 0L, scale: Double = SizeScale.ORDINARY): RangeProfile = RangeProfile(
        bearing = BEARING,
        basinY = BASIN_FLOOR,
        crestY = CREST_Y,
        riseShape = RISE_SHAPE,
        rangeThreshold = RANGE_THRESHOLD,
        rangeSharpness = RANGE_SHARPNESS,
        grainAcross = GRAIN_ACROSS * scale,
        grainAlong = GRAIN_ALONG * scale,
        meander = MEANDER * scale,
        warpStretch = WARP_STRETCH * scale,
        seed = PROFILE_SEED xor salt,
    )

    /** What the ice did — see [Glaciation], and the note on [SNOWLINE_Y] for what it is worth. */
    fun glaciation(salt: Long = 0L, scale: Double = SizeScale.ORDINARY): Glaciation = Glaciation(
        snowlineY = SNOWLINE_Y,
        summitScatter = SUMMIT_SCATTER,
        summitStretch = SUMMIT_STRETCH * scale,
        summitRounding = SUMMIT_ROUNDING,
        troughBase = TROUGH_BASE * scale,
        troughPerOrder = TROUGH_PER_ORDER * scale,
        troughPower = TROUGH_POWER,
        glacialOrder = GLACIAL_ORDER,
        cirqueRadius = CIRQUE_RADIUS * scale,
        cirqueDeepening = CIRQUE_DEEPENING,
        headwallSteepening = HEADWALL_STEEPENING,
        cirqueLowestY = CIRQUE_LOWEST_Y,
        seed = GLACIATION_SEED xor salt,
    )

    /**
     * The floor of a basin, and the level the drainage then cuts a further ten or so beneath — which is what
     * puts the low ground down at the stone band above bedrock.
     *
     * Where the ground stands relative to the water decides nothing here, because there is no water to
     * stand over: a landlocked Age reads its continentalness off its own relief instead. See
     * [co.voik.agesandtheart.worldgen.biome.Elevation].
     */
    const val BASIN_FLOOR = -38

    /** The ground a crest is built on, before any relief. About 3,400 m; the summits are [SNOWLINE_Y]'s. */
    const val CREST_Y = 210

    /**
     * How late the climb out of a basin comes on. **Well above one, and that is the whole of what makes a
     * basin a floor** — at one the ground ramps straight from basin to crest and the middle of every cell
     * sits at some intermediate height, which is a country of slopes with no plains in it.
     */
    private const val RISE_SHAPE = 2.6

    /** Where a basin's rolling ground averages out, which is what `Elevation` datums its climate at. */
    const val PLAIN_Y = BASIN_FLOOR + 20

    /**
     * How far off the network's zero contour still counts as a range, and how sharply a range gives way to
     * its basin.
     *
     * **Together these are how much of the country is mountain.** The threshold is a share of the noise's
     * own spread rather than a distance, so widening the cells widens the ranges with them and the
     * proportions hold. Read the column-top percentiles the previewer prints: a country that is mostly
     * mountain has its median up near the crests, and one with room in it does not.
     */
    private const val RANGE_THRESHOLD = 0.55
    private const val RANGE_SHARPNESS = 3.0

    /**
     * How far it is from one range to the next, across the strike and along it — **equal, and that is the
     * decision rather than the number.**
     *
     * Drawn out along the strike the cells become long lenses, and a lens network is a set of parallel
     * ranges: they all run one way, they rarely meet, and a basin is a corridor between two of them. Equant
     * cells give the honeycomb instead — ranges that curve right round, meet at junctions, and enclose a
     * basin on every side. That is a different country to be in, and it is the one this Age wants.
     *
     * At this stretch the network's wavelength is about 4,500 blocks, so a basin runs a couple of thousand
     * across between ranges of much the same width. **Large on purpose**: the whole point of the network is
     * that the low ground is somewhere to be rather than something to cross.
     */
    private const val GRAIN_ACROSS = 70.0
    private const val GRAIN_ALONG = 70.0

    /**
     * How far the frame is warped, and over what distance it swings that far.
     *
     * **These are what stop the range reading as a lattice.** A reach is a straight segment between two
     * nodes, so its channel, its trough and the spurs and ridge lines its hillslope makes with its
     * neighbours' are all straight too — and no amount of good cross-section hides a valley ruled from
     * junction to junction. 170 blocks of swing against a 620-block reach carries a valley visibly round;
     * the finer octaves put the kinks and the frayed spur ends in.
     *
     * The stretch is what bounds it: displacement over wavelength is the shear, and much past a seventh the
     * frame folds back through itself. 1,360 blocks against 170 is well inside that.
     */
    private const val MEANDER = 85.0
    private const val WARP_STRETCH = 38.0

    /** Which way the range runs. North–south, so a walk out along +X crosses it. */
    private const val BEARING = 0.0

    /**
     * How far apart the valleys are — **and so, with [HILLSLOPE_GRADE], how much relief this range has**.
     * Relief is `spacing × grade / 2` and nothing else.
     *
     * Wide enough that a valley floor is a floor rather than a crease, and that a peak reads as its own
     * mountain rather than as one tooth of a corrugation. Tightened from 520 because a range wider than the
     * render distance cannot be seen as a range at all — at 460 a whole cell fits in a view, and the pair
     * with the steeper grade leaves relief slightly up rather than down.
     */
    private const val SPACING = 460.0

    /**
     * The angle a hillside stands at, as a tangent — **the only thing that decides how steep a climb is**.
     * The ground is the lower envelope of cones rising from the channels at this grade, so no amount of
     * lowering the basins or sharpening [RISE_SHAPE] can make an ascent steeper than this; those move where
     * the high ground is, not how hard it is to walk up.
     *
     * Above `MountainRange.DEFAULT_HILLSLOPE_GRADE`, which is the ~30° threshold slope soil and scree
     * actually stand at. This is 35°, which rock does hold and a glaciated range shows plenty of — the
     * default is the conservative answer and this preset wants the dramatic one.
     */
    private const val HILLSLOPE_GRADE = 0.70

    /** How far a node stands off its lattice point. Large, or the valleys come out on a grid. */
    private const val JITTER = 0.42

    /** How deep a headwater cuts, and how much each confluence adds — the trunks are the deep valleys. */
    private const val INCISION = 26.0
    private const val INCISION_PER_ORDER = 0.6

    /**
     * How deeply the hillsides are gullied, and how wide one gully runs.
     *
     * **Not a texture setting — this is the drainage below the lattice's own resolution**, and it is doing
     * structural work: without it the flanks come out as great planar fans wherever one deep trunk's
     * hillslope wins over a wide area. Twenty-six blocks against a hundred and forty of relief is roughly
     * the ratio a real dissected hillside carries.
     */
    private const val ROUGHNESS = 30.0
    private const val ROUGHNESS_STRETCH = 7.0

    /** Shallow: an alpine river you can wade, deepening as it gathers. */
    private const val WATER_DEPTH = 3.0

    /**
     * Where the summits pile up — about 3,300 m, between today's alpine snowline and the ice age's.
     *
     * **This is what stops a crest reading as a sawtooth of equal teeth.** Glacial erosion is fiercest just
     * above the equilibrium line, so real ranges have their summit heights clustered near it with a handful
     * of massifs standing clear; a landform with no such cap has its peaks decided by whatever the noise
     * did, which is the vanilla mountain problem restated.
     */
    const val SNOWLINE_Y = 272

    /**
     * How far over [SNOWLINE_Y] the weathering band still has to reach, since the tallest massifs stand
     * clear of the cap — and where a trunk valley runs, which is the level the frost is asked to spare.
     *
     * Here rather than with the profile that reads them: both are alpine geometry, and the profile and the
     * shape were designed together (`decisions.md`, "Weathering belongs to a landform").
     */
    const val SUMMITS_ABOVE_THE_SNOWLINE = 80
    const val VALLEY_FLOOR = 70

    /** How far a massif stands above or below it, and how wide one massif is. The exceptions live here. */
    private const val SUMMIT_SCATTER = 26.0
    private const val SUMMIT_STRETCH = 9.0

    /**
     * Over how many blocks a summit rounds into the cap. Wide, because the whole job is to stop the cap
     * reading as a table: a rounding much shorter than the overshoot leaves a flat top with soft edges.
     */
    private const val SUMMIT_ROUNDING = 55.0

    /**
     * How wide a valley floor is: at the head of one, and how much more each stream gathered buys.
     *
     * **These are what make the range somewhere you can stand back in.** A trunk gathering four comes out
     * with a floor some 460 blocks across and a headwater about 100 — so the low ground is a place rather
     * than a line, and a peak four hundred blocks off across it is a thing you can look at.
     */
    private const val TROUGH_BASE = 70.0
    private const val TROUGH_PER_ORDER = 30.0

    /** How the trough floor curves out to its shoulder. Glaciated cross-sections fit powers near this. */
    private const val TROUGH_POWER = 1.8

    /**
     * How many streams must join before a valley carried a glacier. **One, and it is the whole of why there
     * are hanging valleys**: a trunk was deepened by ice and its tributaries were not, so their floors are
     * left standing above its own and their streams arrive over a lip.
     */
    private const val GLACIAL_ORDER = 0.0

    /** How far a cirque reaches back from a valley head, and how far it is scooped below its channel. */
    private const val CIRQUE_RADIUS = 135.0
    private const val CIRQUE_DEEPENING = 20.0

    /** How much steeper than a hillslope a headwall stands. This puts one near fifty degrees. */
    private const val HEADWALL_STEEPENING = 1.6

    /** The lowest land a cirque forms on — below this there was never ice standing long enough to cut one. */
    private const val CIRQUE_LOWEST_Y = 118

    /**
     * The flat sea, and **this Age has none** — the range is landlocked, and every drop of water in it is
     * the network's own.
     *
     * The number is the *window's* floor rather than the world's, and the difference is load-bearing: a sea
     * at −49 would sit one block under the bedrock and still fill the fifteen empty layers below it, laying
     * down a sheet of water beneath the world. At the window's own floor there is nothing left below to
     * fill, so the sea is exactly nothing.
     *
     * It cannot simply be dropped. A null waterline makes `Sea.pour` answer `SeaFill.NONE`, whose substance
     * is air — and a fill of air refuses the shape's *own* water too, which would take the rivers with it.
     */
    const val WATERLINE = -64

    /**
     * How far into a face the weather works. Shallower than a canyon wall's: what this profile is for is
     * frost damage on the high ground, and a deep reach on a mass this large riddles it with pockets.
     */
    private const val SHELTER_REACH = 20

    private const val RANGE_SEED = 0xA1_9550L
    private const val PROFILE_SEED = 0x020_6E4L
    private const val GLACIATION_SEED = 0x1CE_A6EL
}
