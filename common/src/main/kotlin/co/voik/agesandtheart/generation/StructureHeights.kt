package co.voik.agesandtheart.generation

import co.voik.agesandtheart.age.aspect.Options
import co.voik.agesandtheart.age.aspect.Structures
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.util.Mth
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.structure.Structure
import java.util.Optional
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The height a book set an Age's jigsaw structures at — `buried villages` under the ground, `lost structures` under it
 * and abandoned — off a thread-local the Age's generator sets around its own structure starts, as [JigsawSizes] are.
 * Asked by `JigsawStructureMixin` before [IncongruousStructures]: a height a writer named is not drawn.
 *
 * **Jigsaws only.** A temple or an igloo sits on the ground through two helpers vanilla also reads to refuse a start
 * below sea level, and its piece finds its own height again as it is built, so burying one through them only refuses
 * it.
 */
object StructureHeights {

    private val placing = ThreadLocal<Map<Structure, Double>?>()

    /** Each structure [options] set a height for, keyed on the registry's own, at [salt]'s draw in its span. */
    fun of(server: MinecraftServer, options: Options, salt: Long): Map<Structure, Double> {
        val sets = server.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET)
        val holders = options.holdersOfAny(Structures.HEIGHT)
        val named = holders.only + if (holders.everyone) sets.listElementIds().map { it.identifier().toString() }.toList() else emptyList()
        val heights = HashMap<Structure, Double>()
        for (said in named) {
            val set = Identifier.tryParse(said)?.let { sets.get(ResourceKey.create(Registries.STRUCTURE_SET, it)).orElse(null) } ?: continue
            val height = options.steer(Structures.HEIGHT, salt, member = said) ?: continue
            for (entry in set.value().structures()) heights[entry.structure().value()] = height
        }
        return heights
    }

    /** [starting] with [heights] read by the jigsaws it lays. */
    fun starting(heights: Map<Structure, Double>, starting: () -> Unit) {
        if (heights.isEmpty()) return starting()
        val was = placing.get()
        placing.set(heights)
        try {
            starting()
        } finally {
            placing.set(was)
        }
    }

    /**
     * How far over the ground (or under it, below nought) a structure at [height] starting at [start] stands, or null
     * as written.
     * Under the ground the dial is how deep, nothing at nought to [BURIED_MOST] at the whole of it, and **a burial
     * shallower than [SURELY_SUNK] is patchy**: it takes only that share of the starts, each a block or more down to
     * its depth — `lost villages` mostly stand, now and then a block or two sunk (Jonah).
     */
    internal fun offsetAt(height: Double, start: BlockPos): Int? {
        val towards = height.coerceIn(-1.0, 1.0)
        if (towards > 0.0) return Mth.lerp(towards, SKY_LEAST.toDouble(), SKY_MOST.toDouble()).roundToInt()
        val depth = (-towards * BURIED_MOST).roundToInt()
        if (depth <= 0) return null
        if (depth >= SURELY_SUNK) return -depth
        val draw = Random(start.x.toLong() * START_X_MIXER xor start.z.toLong() * START_Z_MIXER)
        if (draw.nextDouble() >= depth.toDouble() / SURELY_SUNK) return null
        return -(1 + draw.nextInt(depth))
    }

    /** A jigsaw [structure]'s start set at its height over the surface, or null to leave it. */
    @JvmStatic
    fun jigsawStart(structure: Structure, start: BlockPos): IncongruousStructures.JigsawStart? {
        val height = placing.get()?.get(structure) ?: return null
        val by = offsetAt(height, start) ?: return null
        return IncongruousStructures.JigsawStart(start.atY(by), Optional.of(Heightmap.Types.WORLD_SURFACE_WG))
    }

    /** How deep the whole of the dial buries a structure, and the depth from which every start is buried. */
    private const val BURIED_MOST = 40
    private const val SURELY_SUNK = 6

    /** How far over the ground a structure stands at the least and the most. */
    private const val SKY_LEAST = 24
    private const val SKY_MOST = 64

    private const val START_X_MIXER = 0x2545_F491_4F6C_DD1DL
    private const val START_Z_MIXER = 0x6C8E_9CF5_7093_2BD5L
}
