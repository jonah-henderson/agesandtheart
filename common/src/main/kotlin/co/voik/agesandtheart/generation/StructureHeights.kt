package co.voik.agesandtheart.generation

import co.voik.agesandtheart.age.aspect.Options
import co.voik.agesandtheart.age.aspect.Structures
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.util.Mth
import net.minecraft.world.level.levelgen.structure.Structure
import net.minecraft.world.level.levelgen.structure.StructureStart
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The height a book set an Age's jigsaw structures at — `buried villages` under the ground, `lost structures` under it
 * and abandoned. **The whole start is moved once it is built** ([moved]), every piece by the same distance: a village's
 * streets are laid on the terrain whatever height its start was given, and its houses hang off its streets, so lowering
 * the start alone left all but its centre on the surface (walked).
 *
 * **Jigsaws only.** A temple or an igloo finds its own height again as its piece is built, so moving its start moves
 * nothing.
 */
object StructureHeights {

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

    /** [start], every piece moved to the height [heights] set its structure at, or [start] itself where none was set. */
    fun moved(start: StructureStart, heights: Map<Structure, Double>): StructureStart {
        if (!start.isValid) return start
        val height = heights[start.structure] ?: return start
        val by = offsetAt(height, start.chunkPos.getMiddleBlockPosition(0)) ?: return start
        for (piece in start.pieces) piece.move(0, by, 0)
        return StructureStart(start.structure, start.chunkPos, start.references, PiecesContainer(start.pieces))
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

    /** How deep the whole of the dial buries a structure, and the depth from which every start is buried. */
    private const val BURIED_MOST = 40
    private const val SURELY_SUNK = 6

    /** How far over the ground a structure stands at the least and the most. */
    private const val SKY_LEAST = 24
    private const val SKY_MOST = 64

    private const val START_X_MIXER = 0x2545_F491_4F6C_DD1DL
    private const val START_Z_MIXER = 0x6C8E_9CF5_7093_2BD5L
}
