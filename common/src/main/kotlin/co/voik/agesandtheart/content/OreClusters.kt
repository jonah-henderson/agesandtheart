package co.voik.agesandtheart.content

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import java.util.concurrent.ConcurrentHashMap
import net.minecraft.world.level.levelgen.XoroshiroRandomSource
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * The shape of one body of drifting ore — **a cluster of cells grown from a number**, so no two bodies in
 * the sky are the same rock model turned round (design §7.1.2).
 *
 * **Pure, and that is the point twice over.** It needs no level, no registries and no client, so the shape
 * of a body can be checked in a unit test rather than by looking at one — and the same call answers
 * identically on the server, on every client, and in the renderer, so nothing has to be sent but the
 * number it was grown from.
 *
 * **Bounded on purpose.** There are [SHAPES] of them per tier and no more, which is what lets a renderer
 * cache the geometry: unique-per-body shapes would be prettier for about a second and then rebuild every
 * mesh every frame. Sixty-four is far past the count anybody will notice repeating in a sky.
 *
 * **They do not tumble** (Jonah, 2026-09-09), and that is what makes the cells worth having at all: a
 * `VoxelShape` is axis-aligned and cannot be rotated, so a body that holds still *can* carry an exact
 * collider. A tumbling rock would have had to be a box however carefully it was drawn.
 */
object OreClusters {

    /** One cell of a body: where it sits against the middle, and whether it is crystal or the rock round it. */
    data class Cell(val at: BlockPos, val isCrystal: Boolean)

    /**
     * The body [shape] weathers into at [tier] — **a cube with bites taken out of it**, the same every time.
     *
     * **Eroded rather than grown**, and the difference is the whole of why it reads as a boulder. Growing a
     * body outward from a seed cell is dendritic: it wanders, and five cells came out spanning seven blocks
     * — a twig rather than a rock. A cube cannot do that. It starts compact and can only get more so.
     *
     * **Bites are taken from the outside in, corners first, and beside one another.** A cell is scored by
     * how exposed it is and by how much of it has already gone, so the corners of the cube go before its
     * faces and a bite widens instead of a new hole opening across the rock. That is what turns a cube into
     * a boulder rather than into swiss cheese.
     */
    fun of(shape: Int, tier: Int): List<Cell> =
        remembered.computeIfAbsent(shape * DriftingOre.MOST_TIERS + tier) { weathered(shape, tier) }

    private fun weathered(shape: Int, tier: Int): List<Cell> {
        val random = XoroshiroRandomSource(shape.toLong() * SHAPE_SALT xor (tier.toLong() * TIER_SALT))
        val side = sideOf(tier)
        val from = -(side / 2)
        val standing = LinkedHashSet<BlockPos>()
        for (x in 0..<side) for (y in 0..<side) for (z in 0..<side) {
            standing += BlockPos(from + x, from + y, from + z)
        }
        val taking = (standing.size * TAKEN_AWAY).toInt()
        repeat(taking) {
            val next = standing.maxByOrNull { weathering(it, standing, random) } ?: return@repeat
            standing -= next
        }
        // The crystal is scattered through the rock rather than cased in it, so a body reads as ore from
        // any side. Drawn after the shape, so the same cluster is the same cluster whichever cells glow.
        val loose = standing.toMutableList()
        val glowing = HashSet<BlockPos>()
        repeat(crystalsIn(tier).coerceAtMost(loose.size)) { glowing += loose.removeAt(random.nextInt(loose.size)) }
        return standing.map { Cell(it, it in glowing) }
    }

    /**
     * How ready a cell is to come away: **how much of it is already exposed, and nothing else.**
     *
     * A corner has three faces to the sky and a cell in the middle of a face has one, so corners round off
     * first — which is what a boulder is. As cells go their neighbours become more exposed in turn, so a
     * bite widens on its own without being told to.
     *
     * **There used to be a second term for that widening and it made one bite eat the body** (sliced and
     * read, 2026-09-09). An eaten neighbour is already counted here — it is not standing, so it is open —
     * and scoring it again on top made an eaten face worth more than a pristine corner. The erosion then
     * compounded into whichever corner it started on and left the other seven square, which is exactly the
     * "still reading mainly as cubes" a walk reported. Openness alone rounds the whole rock at once.
     *
     * The jitter is what makes sixty-four of these differ at all; without it every cube would weather into
     * the same rock.
     */
    private fun weathering(at: BlockPos, standing: Set<BlockPos>, random: XoroshiroRandomSource): Double {
        val open = Direction.entries.count { at.relative(it) !in standing }
        if (open == NOTHING_EXPOSED) return NEVER
        return open * PER_OPEN_FACE + random.nextDouble() * JITTER
    }

    /** How wide a body of this tier is before anything is taken off it: two, four, six. */
    fun sideOf(tier: Int): Int = SMALLEST_SIDE + tier.coerceIn(0, DriftingOre.MOST_TIERS - 1) * SIDE_A_TIER

    /**
     * **Concurrent because two threads genuinely ask.** The class note says the same call answers on the
     * server, on every client and in the renderer — and in single player that is the server thread sizing
     * a body's box while the render thread asks the same question for the same body. A plain `HashMap`
     * resized under that can lose an entry or spin in a corrupted bucket.
     */
    private val remembered = ConcurrentHashMap<Int, List<Cell>>()

    /**
     * The cells of this body that anything can see — **what a renderer actually submits**.
     *
     * A cell walled in on all six sides draws nothing and costs a submit, and a solid-ish cube is mostly
     * walled in: culling them is a third of the work at the top tier. Worth being its own question rather
     * than a filter inside a renderer, because it is also the honest measure of what a body costs to draw.
     */
    fun facesOf(shape: Int, tier: Int): List<Cell> =
        drawn.computeIfAbsent(shape * DriftingOre.MOST_TIERS + tier) {
            val standing = of(shape, tier).associateBy { it.at }
            standing.values.filter { cell -> Direction.entries.any { cell.at.relative(it) !in standing } }
        }

    /** Remembered for [remembered]'s reason, and because a renderer asks this per body per frame. */
    private val drawn = ConcurrentHashMap<Int, List<Cell>>()

    /**
     * The exact shape of a body — **what it can be stood on and shot at**, rather than the cube it
     * weathered from.
     *
     * A weathered rock is a third gaps, so the box was a third wrong in every direction and it was too
     * easy to stand on and shoot at nothing (Jonah, 2026-09-09). Built from unit boxes on the same lattice
     * the cells are drawn on, so the collider and the drawing cannot disagree: a cell at offset `o` fills
     * `o` to `o + 1`, exactly as `DriftingOreRenderer` places it.
     *
     * Remembered, because merging a hundred and forty boxes is not something to do per entity per tick —
     * though it is cheaper than it looks, the merged shape only ever having seven coordinates an axis.
     */
    fun shapeOf(shape: Int, tier: Int): VoxelShape =
        shapes.computeIfAbsent(shape * DriftingOre.MOST_TIERS + tier) {
            of(shape, tier)
                .fold(Shapes.empty()) { built, cell -> Shapes.or(built, ONE_CELL.move(cell.at)) }
                .optimize()
        }

    private val shapes = ConcurrentHashMap<Int, VoxelShape>()

    private val ONE_CELL: VoxelShape = Shapes.block()

    /**
     * How wide a body of this tier is, in blocks — what a collider and a bounding box are sized from.
     *
     * **The smallest cube centred on the body that holds every cell of every shape.** A cell at offset `o`
     * fills `o` to `o + 1`, so its reach from the middle is one more on the positive side than the offset
     * says; and the shapes differ in what the weather took, so a box sized from the first would have let
     * the others hang out of it. Remembered, because it is asked per body and answered from a table of
     * three.
     */
    fun spanOf(tier: Int): Double = widest[tier.coerceIn(0, DriftingOre.MOST_TIERS - 1)]

    private val widest: List<Double> by lazy {
        (0..<DriftingOre.MOST_TIERS).map { tier ->
            val reach = (0..<SHAPES).maxOf { shape ->
                of(shape, tier).maxOf { cell -> reachOf(cell.at) }
            }
            (reach * 2).toDouble()
        }
    }

    private fun reachOf(at: BlockPos): Int =
        maxOf(-at.x, at.x + 1, -at.y, at.y + 1, -at.z, at.z + 1)

    /** How many distinct bodies there are per tier. Far past what anybody will catch repeating in a sky. */
    const val SHAPES = 64

    /** The cube a body starts as, and how much wider each tier is: two, four, six a side. */
    private const val SMALLEST_SIDE = 2
    private const val SIDE_A_TIER = 2

    /**
     * What share of the cube the weather takes.
     *
     * **The same share at every tier**, so the ladder is a size rather than a texture: a big body is a big
     * rock and not a lacier one. It is the lever a richer band would move later.
     *
     * Raised from a fifth 2026-09-09: a fifth off a cube is still a cube, and the slices say so.
     */
    private const val TAKEN_AWAY = 0.36

    /** What a cell's readiness is made of, and the jitter that makes sixty-four cubes weather differently. */
    private const val PER_OPEN_FACE = 1.0
    private const val JITTER = 2.4
    private const val NEVER = -1.0
    private const val NOTHING_EXPOSED = 0

    /**
     * How many cells of a body are crystal rather than the rock it grew in — **a count, not a share**.
     *
     * A share of a quarter put thirty-five crystals in a top-tier body, which reads as a lump of gemstone
     * rather than as ore in stone (Jonah, 2026-09-09). A handful is what makes the rock read as *bearing*
     * something. It says nothing about the yield, which comes only from breaking the smallest tier.
     */
    private fun crystalsIn(tier: Int): Int = CRYSTALS_A_TIER[tier.coerceIn(CRYSTALS_A_TIER.indices)]

    private val CRYSTALS_A_TIER = listOf(1, 4, 6)

    private const val SHAPE_SALT = 0x9E_37_79_B1L
    private const val TIER_SALT = 0x51_0E_1EL
}
