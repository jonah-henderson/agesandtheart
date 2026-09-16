package co.voik.agesandtheart.worldgen.feature

import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.content.DeepWater
import net.minecraft.core.BlockPos
import net.minecraft.util.RandomSource
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * A vent standing on the floor of an abyss, with a chamber cut under it — design §7.1.2's deep-ocean
 * material, and the place it is won from.
 *
 * **Three parts, and the shape is the whole mechanic.**
 *
 * - A **chimney** on the sea bed, its bore open at the top and nowhere else, carrying sea lanterns on its
 *   outside. Those are what a diver navigates by: `shines_through_the_deep` draws everything in it as an
 *   unfogged point out to 120 blocks, so a vent reads at range exactly like a hadalfish's lure and
 *   cannot be told from one until you have crossed to it. **The gloomgrit inside does not shine**,
 *   deliberately: the material is meant to look like nothing until it is refined.
 * - A **neck** through the sea floor, which is why the chamber can only be entered from above.
 * - A **chamber** beneath, lined with [co.voik.agesandtheart.content.VentLiningBlock] and floored with
 *   magma. The magma makes a downward [co.voik.agesandtheart.content.DeepBubbleColumnBlock] that
 *   fills the bore to the chimney's mouth, so swimming over the top is what takes you in — and
 *   getting out is swimming clear of it with the pressure clock already running, the column being
 *   abyss like everything around it rather than a pocket of ordinary water.
 *
 * **Everything it cuts is filled with deep water, never air.** An air pocket takes the abyss out for
 * [DeepWater.DEPRESSURISED_UNDER_AIR] blocks beneath it, which would make the inside of the vent the one
 * safe place in the sea and stop the lining producing at the same time. The bore is water the whole way
 * down; that it is *hard to be in* is what the place is for.
 *
 * **Organic rather than drawn.** A cylinder reads as built, and one vent looking like the next reads as a
 * dungeon. The silhouette is a sum of a few cosine lobes at phases rolled per vent ([lobeAt]), the axis
 * leans as it climbs on a random walk, and the radius narrows toward the mouth — so no two are the same
 * chimney and none of them is round.
 */
object DeepSeaVent : Feature<NoneFeatureConfiguration>(NoneFeatureConfiguration.CODEC) {

    override fun place(context: FeaturePlaceContext<NoneFeatureConfiguration>): Boolean {
        val level = context.level()
        val random = context.random()
        val origin = context.origin()
        val floor = level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, origin.x, origin.z)
        val chimneyHeight = SHORTEST_CHIMNEY + random.nextInt(TALLEST_CHIMNEY - SHORTEST_CHIMNEY + 1)
        if (!standsInOpenAbyss(level, origin.x, origin.z, floor, chimneyHeight)) return false
        val vent = Silhouette(random)
        val chamberHeight = SHALLOWEST_CHAMBER + random.nextInt(DEEPEST_CHAMBER - SHALLOWEST_CHAMBER + 1)
        val chamberTop = floor - NECK_BLOCKS - 1
        val chamberBottom = chamberTop - chamberHeight + 1
        if (chamberBottom - 1 <= level.minY) return false

        cutTheChamber(level, origin, vent, chamberBottom, chamberTop)
        cutTheNeck(level, origin, vent, chamberTop + 1, floor - 1)
        raiseTheChimney(level, origin, vent, floor, floor + chimneyHeight - 1)
        layTheMagma(level, origin, vent, chamberBottom - 1)
        hangTheLanterns(level, origin, vent, floor, chimneyHeight, random)
        return true
    }

    /**
     * Whether this column is open abyss for the whole height the chimney wants.
     *
     * **Both ends are checked and for different reasons.** At the floor, a vent that is not in deep water
     * is not a vent — the biome alone would let one stand in a shallow bay that happens to border an
     * abyss. At the mouth, a chimney taller than the water it stands in would break the surface, and a
     * vent you can see from a boat is not one you had to dive for.
     */
    private fun standsInOpenAbyss(
        level: WorldGenLevel,
        x: Int,
        z: Int,
        floor: Int,
        chimneyHeight: Int,
    ): Boolean {
        val atTheFoot = level.getFluidState(BlockPos(x, floor, z))
        if (!atTheFoot.`is`(DeepWater.DEEP_WATER)) return false
        val overTheMouth = BlockPos(x, floor + chimneyHeight + CLEAR_OVER_THE_MOUTH, z)
        return level.getFluidState(overTheMouth).`is`(DeepWater.DEEP_WATER)
    }

    /** The room under the sea floor: lined all round, open in the middle. */
    private fun cutTheChamber(
        level: WorldGenLevel,
        origin: BlockPos,
        vent: Silhouette,
        fromY: Int,
        toY: Int,
    ) {
        for (y in fromY..toY) {
            layer(level, origin, vent, y, CHAMBER_RADIUS, CHAMBER_RADIUS + CHAMBER_WALL, AgeContent.VENT_LINING.defaultBlockState())
        }
        // A lid over the room, pierced by the bore — this is what makes the chamber reachable only from
        // the neck above it rather than by tunnelling in from the side of the sea bed.
        layer(level, origin, vent, toY + 1, BORE_RADIUS, CHAMBER_RADIUS + CHAMBER_WALL, AgeContent.VENT_LINING.defaultBlockState())
    }

    /** The bore through the sea floor, lined, joining the chamber to the chimney. */
    private fun cutTheNeck(
        level: WorldGenLevel,
        origin: BlockPos,
        vent: Silhouette,
        fromY: Int,
        toY: Int,
    ) {
        for (y in fromY..toY) {
            layer(level, origin, vent, y, BORE_RADIUS, BORE_RADIUS + NECK_WALL, CHIMNEY_STONE)
        }
    }

    /**
     * The tower above the sea bed, narrowing as it climbs.
     *
     * **Stone the whole way through, lining nowhere.** The lining is the chamber's alone, so gloomgrit
     * only ever grows at the bottom of the shaft: a diver who hovers at the mouth and reaches in gets
     * nothing, and the reward stays at the end of the descent rather than along it.
     */
    private fun raiseTheChimney(
        level: WorldGenLevel,
        origin: BlockPos,
        vent: Silhouette,
        fromY: Int,
        toY: Int,
    ) {
        val climb = (toY - fromY).coerceAtLeast(1).toDouble()
        for (y in fromY..toY) {
            val risen = (y - fromY) / climb
            val outer = CHIMNEY_FOOT_RADIUS + (CHIMNEY_MOUTH_RADIUS - CHIMNEY_FOOT_RADIUS) * risen
            layer(level, origin, vent, y, BORE_RADIUS, outer, CHIMNEY_STONE)
        }
    }

    /**
     * Magma across the chamber floor, which is what drives the whirlpool above it — and each block asked
     * to start its own column.
     *
     * **A bubble column is grown by the *water*, not by the magma.** `LiquidBlock.tick` is what calls
     * `BubbleColumnBlock.updateColumn`, and `DeepWaterBlock` inherits it — but a feature writes block
     * states without the neighbour updates that would schedule that tick, so a freshly generated vent
     * would sit with magma at the bottom and still water above it until something disturbed it.
     *
     * **One tick per magma block and not one for the vent**, because `updateColumn` walks straight up a
     * single column from where it is asked. Asking at the axis alone would light the middle of the chamber
     * and leave the rest of the floor inert — and with the chimney leaning as it climbs, the axis at this
     * depth is not where the feature was handed either.
     */
    private fun layTheMagma(level: WorldGenLevel, origin: BlockPos, vent: Silhouette, y: Int) {
        layer(level, origin, vent, y, NOTHING_OPEN, CHAMBER_RADIUS + CHAMBER_WALL, AgeContent.VENT_LINING.defaultBlockState())
        layer(level, origin, vent, y, NOTHING_OPEN, CHAMBER_RADIUS - MAGMA_INSET, Blocks.MAGMA_BLOCK.defaultBlockState()) { at ->
            val overhead = at.above()
            level.scheduleTick(overhead, level.getBlockState(overhead).block, COLUMN_FORMS_IN)
        }
    }

    /**
     * One lantern at least, and up to [MOST_LANTERNS], set into the chimney's outside.
     *
     * Placed by walking outward along a bearing until the wall runs out, so a lantern always lands on the
     * skin of the tower however lumpy this one came out — a fixed radius would bury some and leave others
     * hanging in open water.
     */
    private fun hangTheLanterns(
        level: WorldGenLevel,
        origin: BlockPos,
        vent: Silhouette,
        floor: Int,
        chimneyHeight: Int,
        random: RandomSource,
    ) {
        val lanterns = 1 + random.nextInt(MOST_LANTERNS)
        for (each in 0..<lanterns) {
            val y = floor + random.nextInt(chimneyHeight)
            val bearing = random.nextDouble() * FULL_TURN
            val stepX = cos(bearing)
            val stepZ = kotlin.math.sin(bearing)
            var outermost: BlockPos? = null
            var reach = BORE_RADIUS
            while (reach <= WIDEST_ANY_VENT) {
                val at = BlockPos(
                    origin.x + vent.leanXAt(y) + (stepX * reach).roundToInt(),
                    y,
                    origin.z + vent.leanZAt(y) + (stepZ * reach).roundToInt(),
                )
                if (level.getBlockState(at).`is`(CHIMNEY_STONE.block)) outermost = at
                reach += HALF_A_BLOCK
            }
            outermost?.let { setBlock(level, it, Blocks.SEA_LANTERN.defaultBlockState()) }
        }
    }

    /**
     * One horizontal slice: open inside [open], [fill] out to [outer], and nothing beyond.
     *
     * [laid] is told where each block of [fill] went, for the one caller that has to come back to them.
     */
    private fun layer(
        level: WorldGenLevel,
        origin: BlockPos,
        vent: Silhouette,
        y: Int,
        open: Double,
        outer: Double,
        fill: BlockState,
        laid: (BlockPos) -> Unit = {},
    ) {
        val abyss = DeepWater.deepWater()
        val span = ceil(outer * MOST_A_LOBE_ADDS).toInt() + 1
        val leanX = vent.leanXAt(y)
        val leanZ = vent.leanZAt(y)
        for (offsetX in -span..span) {
            for (offsetZ in -span..span) {
                val reach = hypot(offsetX.toDouble(), offsetZ.toDouble())
                val lobe = vent.lobeAt(atan2(offsetZ.toDouble(), offsetX.toDouble()))
                val at = BlockPos(origin.x + leanX + offsetX, y, origin.z + leanZ + offsetZ)
                // Strictly inside, so an open radius of nothing opens nothing — the axis itself sits at a
                // reach of exactly zero.
                if (reach < open * lobe) {
                    setBlock(level, at, abyss)
                } else if (reach <= outer * lobe) {
                    setBlock(level, at, fill)
                    laid(at)
                }
            }
        }
    }

    /**
     * One vent's own outline: a few cosine lobes at rolled phases, and an axis that wanders as it climbs.
     *
     * **Rolled once per vent rather than per block**, which is what makes the shape *a* shape — sampling
     * noise per column would give a fuzzy circle, where a fixed set of lobes gives a chimney with sides.
     * The harmonics are low and odd so the lobes do not line up into something that reads as symmetrical.
     */
    private class Silhouette(random: RandomSource) {

        private val phases = DoubleArray(HARMONICS.size) { random.nextDouble() * FULL_TURN }
        private val leanX = random.nextDouble() * LEAN_PER_BLOCK - LEAN_PER_BLOCK / 2.0
        private val leanZ = random.nextDouble() * LEAN_PER_BLOCK - LEAN_PER_BLOCK / 2.0
        private val leansFrom = random.nextInt(LEAN_ORIGIN_SPREAD)

        /** How far this bearing's wall stands from the axis, as a multiple of the nominal radius. */
        fun lobeAt(bearing: Double): Double {
            var swell = 0.0
            for (each in HARMONICS.indices) {
                swell += cos(HARMONICS[each] * bearing + phases[each]) / HARMONICS[each]
            }
            return 1.0 + swell * LOBE_DEPTH
        }

        fun leanXAt(y: Int): Int = ((y - leansFrom) * leanX).roundToInt()

        fun leanZAt(y: Int): Int = ((y - leansFrom) * leanZ).roundToInt()
    }

    /** Low and odd, so the lobes never line up into a symmetry. */
    private val HARMONICS = intArrayOf(2, 3, 5)

    /** How far a lobe may push a wall in or out, as a share of the radius. */
    private const val LOBE_DEPTH = 0.22

    /** The most [lobeAt] can ever return, which is what the scan has to be wide enough to reach. */
    private const val MOST_A_LOBE_ADDS = 1.0 + LOBE_DEPTH * 2.0

    private const val FULL_TURN = 2.0 * Math.PI

    /** Blocks of sideways drift per block of climb — enough to lean, far too little to topple. */
    private const val LEAN_PER_BLOCK = 0.18

    /** So two vents in one Age do not lean the same way from the same height. */
    private const val LEAN_ORIGIN_SPREAD = 64

    private const val SHORTEST_CHIMNEY = 7
    private const val TALLEST_CHIMNEY = 15

    /** Deep water wanted over the mouth, so a chimney never breaks the surface. */
    private const val CLEAR_OVER_THE_MOUTH = 4

    private const val SHALLOWEST_CHAMBER = 4
    private const val DEEPEST_CHAMBER = 7

    /** How much sea floor the neck passes through — what makes the chamber reachable only from above. */
    private const val NECK_BLOCKS = 3

    private const val BORE_RADIUS = 1.6
    private const val NECK_WALL = 1.8
    private const val CHAMBER_RADIUS = 4.5
    private const val CHAMBER_WALL = 1.5
    private const val CHIMNEY_FOOT_RADIUS = 3.6
    private const val CHIMNEY_MOUTH_RADIUS = 2.4

    /** How far short of the chamber wall the magma stops, so the rim is standable and the middle is not. */
    private const val MAGMA_INSET = 1.0

    /** A radius of nothing — an unbroken slice, or a slice with no lining. */
    private const val NOTHING_OPEN = 0.0

    private const val MOST_LANTERNS = 3
    private const val HALF_A_BLOCK = 0.5

    /** Past any silhouette this can draw, so the outward walk always runs out rather than stopping short. */
    private const val WIDEST_ANY_VENT = CHIMNEY_FOOT_RADIUS * MOST_A_LOBE_ADDS + 1.0

    /** Ticks before the water over the magma is asked to build its column. One is enough; it only has to
     * happen after the chunk is real. */
    private const val COLUMN_FORMS_IN = 1

    private val CHIMNEY_STONE: BlockState = Blocks.BLACKSTONE.defaultBlockState()
}
