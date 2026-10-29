package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.location
import co.voik.agesandtheart.worldgen.field.Density
import co.voik.agesandtheart.worldgen.field.Grid
import co.voik.agesandtheart.worldgen.field.HollowTree
import co.voik.agesandtheart.worldgen.field.HollowTree.Part
import co.voik.agesandtheart.worldgen.field.Instanced
import co.voik.agesandtheart.worldgen.field.MaterialLayer
import co.voik.agesandtheart.worldgen.field.NoiseHeightmap
import co.voik.agesandtheart.worldgen.field.TerrainField
import co.voik.agesandtheart.worldgen.field.Union
import co.voik.agesandtheart.worldgen.field.Variation
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ColumnPos
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.block.Blocks
import kotlin.math.roundToInt

/**
 * Vast dead trees standing alone in a shallow sea (`arboreal`), each so far from the next that none can be seen from
 * another: the trunks are [HollowTree]s, their wood the tree's own ([layers]), and the life inside them three biomes of
 * their own ([biomes]). A link arrives on the highest point of the rim of the tree nearest the origin ([summit]).
 *
 * The scale is the size ladder's, so an unsaid tree stands 128 blocks over the water and a colossal one reaches the
 * top of the world.
 */
object HollowTreesField {
    const val SEA_LEVEL = 63

    fun world(salt: Long, scale: Double): TerrainField = Union(listOf(seaFloor(salt), trees(salt, scale, Part.WHOLE)))

    /** The trees' wood, from the bark in to the soil on their shelves and the mud at the foot of their hollows. */
    fun layers(salt: Long, scale: Double): List<MaterialLayer> = listOf(
        MaterialLayer(trees(salt, scale, Part.BARK), listOf(BARK)),
        MaterialLayer(trees(salt, scale, Part.HEARTWOOD), listOf(HEARTWOOD)),
        MaterialLayer(trees(salt, scale, Part.LIVING_SHELVES), listOf(HEARTWOOD), LIVING_SOIL, LIVING_SOIL_DEPTH),
        MaterialLayer(trees(salt, scale, Part.DEAD_SHELVES), listOf(BARK, HEARTWOOD), DEAD_SOIL, DEAD_SOIL_DEPTH),
        MaterialLayer(trees(salt, scale, Part.HOLLOW_FLOOR), listOf(MUD)),
    )

    /** The swamp at the foot of each hollow, the forest over it, and the deadwood of the crown and the trunk outside. */
    fun biomes(salt: Long, scale: Double): List<Pair<ResourceKey<Biome>, TerrainField>> = listOf(
        HOLLOW_SWAMP to trees(salt, scale, Part.SWAMP),
        HOLLOW_FOREST to trees(salt, scale, Part.FOREST),
        DEADWOOD to trees(salt, scale, Part.CROWN),
    )

    /** The column of the highest point on the rim of the tree nearest the origin. */
    fun summit(salt: Long, scale: Double): ColumnPos? {
        val nearest = trees(salt, scale, Part.WHOLE).instanceNearest(0, 0) ?: return null
        val tree = nearest.template as? HollowTree ?: return null
        val (x, _, z) = tree.summit()
        return ColumnPos(nearest.originX + x, nearest.originZ + z)
    }

    /**
     * One part of every tree, laid on one lattice from one seed, so each part lands on the same trees as the rest. Each
     * template is its own tree at its own height; none is turned, so where one stands is where its summit is.
     */
    private fun trees(salt: Long, scale: Double, part: Part): Instanced = Instanced(
        TEMPLATE_HEIGHTS.mapIndexed { index, share ->
            HollowTree(salt xor (index + 1) * TEMPLATE_STRIDE, FLOOR_Y, topYAt(scale, share), part)
        },
        Grid(SPACING, JITTER, Density.uniform()),
        Variation.NONE,
        salt xor TREE_SALT,
    )

    private fun topYAt(scale: Double, share: Double): Int =
        minOf(VerticalWindow.HIGHEST_BLOCK_Y - ROOM_TO_STAND, SEA_LEVEL + (HEIGHT_OVER_THE_WATER * scale * share).roundToInt())

    private fun seaFloor(salt: Long): TerrainField = NoiseHeightmap(
        seed = salt xor FLOOR_SALT,
        firstOctave = FLOOR_OCTAVE,
        amplitudes = FLOOR_AMPLITUDES,
        scaleX = 1.0,
        scaleZ = 1.0,
        baseY = FLOOR_Y,
        relief = FLOOR_RELIEF,
        flatY = VerticalWindow.MIN_Y,
    )

    val HOLLOW_SWAMP: ResourceKey<Biome> = ResourceKey.create(Registries.BIOME, "hollow_swamp".location())
    val HOLLOW_FOREST: ResourceKey<Biome> = ResourceKey.create(Registries.BIOME, "hollow_forest".location())
    val DEADWOOD: ResourceKey<Biome> = ResourceKey.create(Registries.BIOME, "deadwood".location())

    /** How far a tree stands over the water at the size ladder's factor of one; an unsaid tree is twice it. */
    private const val HEIGHT_OVER_THE_WATER = 64.0

    /** Under the top of the world, so the summit of the tallest has room to stand on. */
    private const val ROOM_TO_STAND = 3

    /** Each template's height against the tallest, so no two trees in sight of the arrival look cast alike. */
    private val TEMPLATE_HEIGHTS = listOf(1.0, 0.96, 0.92, 0.98, 0.94)

    // Twice the farthest anyone sees, and more: no tree stands in sight of another.
    private const val SPACING = 1600.0
    private const val JITTER = 240.0

    // A shallow sea, a few blocks deep over sand.
    private const val FLOOR_Y = 57
    private const val FLOOR_OCTAVE = -7
    private val FLOOR_AMPLITUDES = listOf(1.0, 0.5)
    private const val FLOOR_RELIEF = 3.0

    private const val TEMPLATE_STRIDE = 0x2C1B_3C6DL
    private const val TREE_SALT = 0x7EE5_0A4DL
    private const val FLOOR_SALT = 0x5EAF_100DL

    private val BARK = Blocks.PALE_OAK_WOOD.defaultBlockState()
    private val HEARTWOOD = Blocks.STRIPPED_PALE_OAK_WOOD.defaultBlockState()
    private val MUD = Blocks.MUD.defaultBlockState()
    private val LIVING_SOIL = listOf(Blocks.ROOTED_DIRT, Blocks.MOSS_BLOCK, Blocks.ROOTED_DIRT).map { it.defaultBlockState() }
    private const val LIVING_SOIL_DEPTH = 2
    private val DEAD_SOIL = listOf(Blocks.COARSE_DIRT, Blocks.PALE_MOSS_BLOCK, Blocks.ROOTED_DIRT).map { it.defaultBlockState() }
    private const val DEAD_SOIL_DEPTH = 2
}
