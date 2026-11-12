package co.voik.agesandtheart.generation

import co.voik.agesandtheart.worldgen.Perforation
import co.voik.agesandtheart.age.aspect.Options
import co.voik.agesandtheart.age.aspect.Structures
import net.minecraft.core.BlockPos
import net.minecraft.data.BlockFamilies
import net.minecraft.data.BlockFamily
import net.minecraft.resources.Identifier
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.RotatedPillarBlock
import net.minecraft.world.level.block.state.BlockState

/**
 * What a book did to the Age's structures besides their material, and only where it was aimed at them (R5, Jonah):
 * `eroded villages` crumble, `ashen structures` are charred, `porous villages` are holed through ([Perforation]). An
 * eroded, ashen or porous Age leaves its structures alone.
 *
 * Laid as each block is set, through the same view [StructureMaterials] swaps the material through, and never on
 * what holds something — a chest, a bed, a spawner always stands.
 */
data class StructureWeathering(val crumble: Double, val charred: Boolean, val porosity: Double = 0.0, val holesSeed: Long = 0L) {

    val isNothing: Boolean get() = crumble <= 0.0 && !charred && porosity <= 0.0

    private val holes: Perforation by lazy { Perforation(holesSeed xor HOLES_SALT, HOLE_SIZE, porosity) }

    /** [state] as laid at [at] in an Age of [seed], or null where it is not laid at all. */
    // NeoForge deprecates `ignitedByLava` for a level-aware form, and a block not yet laid has no level to ask.
    @Suppress("DEPRECATION")
    fun weathered(at: BlockPos, state: BlockState, seed: Long): BlockState? {
        if (state.isAir || state.hasBlockEntity() || !state.fluidState.isEmpty || isMarker(state)) return state
        if (crumble > 0.0 && drawAt(at, seed xor CRUMBLE_SALT) < crumble * MOST_CRUMBLED) return null
        if (porosity > 0.0 && holes.isHoleAt(at.x, at.y, at.z)) return null
        if (!charred || !state.ignitedByLava()) return state
        val blackened = blackened(state) ?: return null
        return if (drawAt(at, seed xor CHAR_SALT) < BURNED_AWAY) null else blackened
    }

    private fun isMarker(state: BlockState) = state.`is`(Blocks.STRUCTURE_VOID) || state.`is`(Blocks.JIGSAW)

    companion object {
        val NONE = StructureWeathering(crumble = 0.0, charred = false)

        /** How big a hole through a wall is, in blocks. */
        private const val HOLE_SIZE = 2.5
        private const val HOLES_SALT = 0x484F_4C45_53L

        /** The weathering [options] ask of the structures in [set], or of every structure where [set] is null. */
        fun of(options: Options, salt: Long, set: Identifier? = null): StructureWeathering {
            val member = set?.toString()
            val crumble = (options.steer(Structures.CRUMBLE, salt, member = member) ?: 0.0).coerceIn(0.0, 1.0)
            val porosity = (options.steer(Structures.POROSITY, salt, member = member) ?: 0.0).coerceIn(0.0, 1.0)
            return StructureWeathering(crumble, options.isTrue(Structures.CHARRED, member = member), porosity, salt)
        }

        /** What burnt wood is: a log basalt along its own axis, wood blackstone where blackstone has the shape. */
        private fun blackened(state: BlockState): BlockState? {
            // A log is a pillar that burns; hay is one too, and burns away.
            val isALog = state.block is RotatedPillarBlock && !state.`is`(Blocks.HAY_BLOCK)
            if (isALog) return Blocks.BASALT.withPropertiesOf(state)
            if (state.block !in SHAPES) return null
            val black = SHAPES[state.block]?.let(BLACKSTONE.variants::get) ?: BLACKSTONE.baseBlock.takeIf { SHAPES[state.block] == null }
            return black?.withPropertiesOf(state)
        }

        private val BLACKSTONE: BlockFamily = BlockFamilies.POLISHED_BLACKSTONE

        /** Every family block by its shape: null for a family's base, its variant otherwise. */
        private val SHAPES: Map<Block, BlockFamily.Variant?> by lazy {
            BlockFamilies.getAllFamilies().toList().flatMap { family ->
                listOf(family.baseBlock to null) + family.variants.map { (variant, block) -> block to variant }
            }.toMap()
        }

        /** A number in `0..1` fixed by the block's place and the Age, as [CrumbledFeatures] draws. */
        private fun drawAt(at: BlockPos, seed: Long): Double {
            var mixed = at.asLong() * PLACE_MIXER xor seed
            mixed = (mixed xor (mixed ushr SHIFT_ONE)) * SPREAD_ONE
            mixed = (mixed xor (mixed ushr SHIFT_TWO)) * SPREAD_TWO
            mixed = mixed xor (mixed ushr SHIFT_THREE)
            return (mixed ushr FRACTION_SHIFT).toDouble() / FRACTION_RANGE
        }

        /** At the most, two blocks in five of a crumbled structure go. */
        private const val MOST_CRUMBLED = 0.4

        /** How much of the wood that blackens burns away all the same. */
        private const val BURNED_AWAY = 0.25

        private const val CRUMBLE_SALT = 0x1D87_2B41_A9E3_0C55L
        private const val CHAR_SALT = 0x5A03_77C2_E618_94B9L
        private const val PLACE_MIXER = -0x61c8864680b583ebL
        private const val SPREAD_ONE = -0x40a7b892e31b1a47L
        private const val SPREAD_TWO = -0x6b2fb644ecceee15L
        private const val SHIFT_ONE = 30
        private const val SHIFT_TWO = 27
        private const val SHIFT_THREE = 31
        private const val FRACTION_SHIFT = 11
        private const val FRACTION_RANGE = (1L shl 53).toDouble()
    }
}
