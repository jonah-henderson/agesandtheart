package co.voik.agesandtheart.generation

import co.voik.agesandtheart.age.aspect.Options
import co.voik.agesandtheart.age.aspect.Structures
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.util.Mth
import net.minecraft.world.level.levelgen.structure.Structure
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * How far an Age's jigsaw structures sprawl (R4, Jonah): a size on a set that builds by jigsaw — villages,
 * bastions, trial chambers, ancient cities, trail ruins, outposts — is how deep its pieces are laid, never a
 * structure scaled. Doubled at the largest and halved at the smallest, with the reach from the start going with it.
 *
 * **Laid around the Age's own structure starts**, as [IncongruousStructures] is: `JigsawStructureMixin` reads it
 * where the pieces are laid, and a structure anywhere else is laid as vanilla lays it.
 */
object JigsawSizes {

    private val sizing = ThreadLocal<Map<Structure, Double>?>()

    /** Each jigsaw structure [options] sized, keyed on the registry's own structure, at [salt]'s draw in its span. */
    fun of(server: MinecraftServer, options: Options, salt: Long): Map<Structure, Double> {
        val sets = server.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET)
        val sized = HashMap<Structure, Double>()
        val holders = options.holdersOfAny(Structures.SIZE)
        val named = holders.only + if (holders.everyone) allSets(server) else emptySet()
        for (said in named) {
            val set = Identifier.tryParse(said)?.let { sets.get(ResourceKey.create(Registries.STRUCTURE_SET, it)).orElse(null) } ?: continue
            val size = options.steer(Structures.SIZE, salt, member = said) ?: continue
            for (entry in set.value().structures()) {
                val structure = entry.structure().value()
                if (structure is JigsawStructure) sized[structure] = size
            }
        }
        return sized
    }

    private fun allSets(server: MinecraftServer): Set<String> =
        server.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET).listElementIds().map { it.identifier().toString() }.toList().toSet()

    /** [starting] with [sizes] read by the jigsaws it lays. */
    fun starting(sizes: Map<Structure, Double>, starting: () -> Unit) {
        if (sizes.isEmpty()) return starting()
        val was = sizing.get()
        sizing.set(sizes)
        try {
            starting()
        } finally {
            sizing.set(was)
        }
    }

    /** How many times as deep [structure]'s pieces go here, or one where nothing sized it. */
    private fun scaleOf(structure: Structure): Double {
        val size = sizing.get()?.get(structure) ?: return ORDINARY
        return LARGEST_SCALE.pow(size.coerceIn(-1.0, 1.0))
    }

    /** [depth] as deep as [structure] is sized to go. */
    @JvmStatic
    fun depthOf(structure: Structure, depth: Int): Int {
        val scale = scaleOf(structure)
        if (scale == ORDINARY) return depth
        return (depth * scale).roundToInt().coerceIn(SHALLOWEST, DEEPEST)
    }

    /** [reach] blocks from the start widened or narrowed with the depth, never past vanilla's cap. */
    @JvmStatic
    fun reachOf(structure: Structure, reach: Int): Int {
        val scale = scaleOf(structure)
        if (scale == ORDINARY) return reach
        return Mth.clamp((reach * scale).roundToInt(), SHALLOWEST, FURTHEST_REACH)
    }

    private const val ORDINARY = 1.0

    /** How many times as deep the largest goes, and as shallow (its reciprocal) the smallest. */
    private const val LARGEST_SCALE = 2.0
    private const val SHALLOWEST = 1

    /** Past vanilla's codec ceiling of twenty, since a trial chamber is laid at twenty already. */
    private const val DEEPEST = 32

    /** Vanilla's ceiling on a jigsaw's reach, in blocks: past it pieces fall outside the chunks that would place them. */
    private const val FURTHEST_REACH = 128
}
