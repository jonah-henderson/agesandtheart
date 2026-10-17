package co.voik.agesandtheart.generation

import co.voik.agesandtheart.age.aspect.Materials
import co.voik.agesandtheart.age.aspect.Options
import co.voik.agesandtheart.age.aspect.Parameter
import co.voik.agesandtheart.age.aspect.Structures
import net.minecraft.core.BlockPos
import net.minecraft.core.RegistryAccess
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.data.BlockFamilies
import net.minecraft.data.BlockFamily
import net.minecraft.resources.Identifier
import net.minecraft.util.RandomSource
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.EmptyBlockGetter
import net.minecraft.world.level.LevelHeightAccessor
import net.minecraft.world.level.StructureManager
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.structure.Structure
import net.minecraft.world.level.levelgen.structure.StructureStart
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.Optional
import java.util.WeakHashMap
import java.util.function.Predicate

/**
 * What a book built the Age's structures of — `nether_bricks structures`, `nether_bricks villages` — laid in
 * as each structure is placed: **its commonest full block becomes the material**, and so does every variant of
 * that block the material has one of too, so cobblestone stairs become nether brick stairs. Where the material
 * has no variants, as a block of diamond has none, only the commonest block is swapped.
 *
 * The commonest block is found once per structure, by replaying all its pieces into a draft and counting, so
 * every chunk of one structure agrees on it. Answered off a thread-local the Age's generator sets around its own
 * decoration, and asked by `StructureStartMixin`, so anywhere else it costs one read.
 */
object StructureMaterials {

    /** What a book built the structures of: every one of them, and the sets it named. */
    data class Written(val everyStructure: Block?, val bySet: Map<Identifier, Block>) {

        val isNothing: Boolean get() = everyStructure == null && bySet.isEmpty()

        /** The material of a structure in [sets] — its set's own, else the Age's. */
        fun of(sets: List<Identifier>): Block? = sets.firstNotNullOfOrNull(bySet::get) ?: everyStructure

        companion object {
            val NONE = Written(null, emptyMap())

            fun of(options: Options): Written {
                val holders = options.holdersOfAny(Structures.MATERIAL)
                val everyStructure = if (holders.everyone) blockNamed(options.of(Structures.MATERIAL)) else null
                val bySet = holders.only.mapNotNull { member ->
                    val set = Identifier.tryParse(member) ?: return@mapNotNull null
                    blockNamed(options.of(Structures.MATERIAL, member = member))?.let { set to it }
                }.toMap()
                return Written(everyStructure, bySet)
            }

            private fun blockNamed(spelled: String): Block? {
                if (spelled == Parameter.UNCHANGED) return null
                val id = Identifier.tryParse(spelled)?.let(Materials::laidAs) ?: return null
                return BuiltInRegistries.BLOCK.getOptional(id).orElse(null)
            }
        }
    }

    private val building = ThreadLocal<Building?>()

    private class Building(val written: Written, registries: RegistryAccess) {
        /** Which sets hold each structure. One the registry's sets do not hold — a star fissure — is built of nothing. */
        private val setsHolding: Map<Structure, List<Identifier>> by lazy {
            registries.lookupOrThrow(Registries.STRUCTURE_SET).listElements().toList()
                .flatMap { set -> set.value().structures().map { it.structure().value() to set.key().identifier() } }
                .groupBy({ it.first }, { it.second })
        }

        fun materialOf(structure: Structure): Block? {
            val sets = setsHolding[structure] ?: return null
            return written.of(sets)
        }
    }

    /** Runs [decorating] with this thread laying [written] into the structures it places. */
    fun building(written: Written, registries: RegistryAccess, decorating: () -> Unit) {
        if (written.isNothing) return decorating()
        val was = building.get()
        building.set(Building(written, registries))
        try {
            decorating()
        } finally {
            building.set(was)
        }
    }

    private val palettes: MutableMap<StructureStart, Palette> = Collections.synchronizedMap(WeakHashMap())

    /**
     * The level [start] is to be placed through, or null to place it as it is. Asked by `StructureStartMixin`
     * at the head of `placeInChunk`, which places it again through what this hands back.
     */
    @JvmStatic
    fun levelFor(start: StructureStart, level: WorldGenLevel, manager: StructureManager, generator: ChunkGenerator): WorldGenLevel? {
        if (Proxy.isProxyClass(level.javaClass)) return null
        val material = building.get()?.materialOf(start.structure) ?: return null
        val palette = palettes.getOrPut(start) { Palette.of(commonestBlockOf(start, level, manager, generator), material) }
        if (palette.isEmpty) return null
        return through(level, Swapping(level, palette))
    }

    /** The blocks one structure's material takes the place of, each by the block it becomes. */
    private class Palette(private val swaps: Map<Block, Block>) {

        val isEmpty: Boolean get() = swaps.isEmpty()

        fun swapped(state: BlockState): BlockState = swaps[state.block]?.withPropertiesOf(state) ?: state

        companion object {
            private val FAMILIES: Map<Block, BlockFamily> by lazy {
                BlockFamilies.getAllFamilies().toList().associateBy(BlockFamily::getBaseBlock)
            }

            fun of(commonest: Block?, material: Block): Palette {
                if (commonest == null || commonest == material) return Palette(emptyMap())
                val theirs = FAMILIES[commonest]?.variants.orEmpty()
                val ours = FAMILIES[material]?.variants.orEmpty()
                val variants = theirs.mapNotNull { (variant, block) -> ours[variant]?.let { block to it } }
                return Palette(mapOf(commonest to material) + variants)
            }
        }
    }

    /**
     * The full block [start] lays most of, found by replaying every piece into a draft that answers air
     * wherever nothing has been laid yet. A piece that asks the draft something it cannot answer is left out
     * of the count rather than failing the chunk.
     */
    private fun commonestBlockOf(start: StructureStart, level: WorldGenLevel, manager: StructureManager, generator: ChunkGenerator): Block? {
        val pieces = start.pieces
        if (pieces.isEmpty()) return null
        val draft = Drafting(level)
        val first = pieces.first().boundingBox
        val pivot = BlockPos(first.center.x, first.minY(), first.center.z)
        for (piece in pieces) {
            val box = piece.boundingBox
            try {
                piece.postProcess(through(level, draft), manager, generator, RandomSource.create(start.chunkPos.pack()), box, ChunkPos.containing(box.center), pivot)
            } catch (_: RuntimeException) {
                continue
            }
        }
        return draft.counts.maxByOrNull { it.value }?.key
    }

    private fun through(level: WorldGenLevel, handler: InvocationHandler): WorldGenLevel =
        Proxy.newProxyInstance(level.javaClass.classLoader, arrayOf(WorldGenLevel::class.java), handler) as WorldGenLevel

    /** The real level, with every block set into it swapped through [palette]. */
    private class Swapping(private val real: WorldGenLevel, private val palette: Palette) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, arguments: Array<Any?>?): Any? {
            val given = arguments ?: emptyArray()
            if (method.name == "setBlock") given[1] = palette.swapped(given[1] as BlockState)
            return invokeOn(real, method, given)
        }
    }

    /** A draft that remembers what is laid in it and counts its full blocks, touching the real level for nothing. */
    private class Drafting(private val real: WorldGenLevel) : InvocationHandler {

        val counts = HashMap<Block, Int>()
        private val laid = HashMap<BlockPos, BlockState>()

        private fun stateAt(at: BlockPos): BlockState = laid[at] ?: AIR

        @Suppress("UNCHECKED_CAST")
        override fun invoke(proxy: Any, method: Method, arguments: Array<Any?>?): Any? {
            val given = arguments ?: emptyArray()
            return when (method.name) {
                "setBlock" -> true.also { lay((given[0] as BlockPos).immutable(), given[1] as BlockState) }
                "getBlockState" -> stateAt(given[0] as BlockPos)
                "getFluidState" -> stateAt(given[0] as BlockPos).fluidState
                "isStateAtPosition" -> (given[1] as Predicate<BlockState>).test(stateAt(given[0] as BlockPos))
                "isEmptyBlock" -> stateAt(given[0] as BlockPos).isAir
                "ensureCanWrite" -> true
                in ANSWERED_BY_THE_LEVEL -> invokeOn(real, method, given)
                else -> if (method.declaringClass == LevelHeightAccessor::class.java) invokeOn(real, method, given) else nothing(method, given)
            }
        }

        private fun lay(at: BlockPos, state: BlockState) {
            laid[at] = state
            if (countsAsBuilding(state)) counts.merge(state.block, 1, Int::plus)
        }

        private fun countsAsBuilding(state: BlockState): Boolean {
            val isSolid = !state.isAir && state.fluidState.isEmpty
            val isMarker = state.`is`(Blocks.STRUCTURE_VOID) || state.`is`(Blocks.JIGSAW)
            return isSolid && !isMarker && state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
        }

        /** What a question the draft does not answer is told: a position it asked about, else nothing at all. */
        private fun nothing(method: Method, given: Array<Any?>): Any? = when (method.returnType) {
            java.lang.Boolean.TYPE -> false
            Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0.0f
            java.lang.Double.TYPE -> 0.0
            Optional::class.java -> Optional.empty<Any>()
            BlockPos::class.java -> given.firstOrNull { it is BlockPos }
            else -> null
        }
    }

    private fun invokeOn(real: WorldGenLevel, method: Method, given: Array<Any?>): Any? = try {
        method.invoke(real, *given)
    } catch (failure: InvocationTargetException) {
        throw failure.cause ?: failure
    }

    /** What a draft may still ask the real level: what it is, never what is in it. */
    private val ANSWERED_BY_THE_LEVEL = setOf(
        "registryAccess", "getSeed", "getLevel", "getRandom", "enabledFeatures", "dimensionType", "holderLookup",
        "getServer", "equals", "hashCode", "toString",
    )

    private val AIR = Blocks.AIR.defaultBlockState()
}
