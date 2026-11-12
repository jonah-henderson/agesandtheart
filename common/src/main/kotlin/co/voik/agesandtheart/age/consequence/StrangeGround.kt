package co.voik.agesandtheart.age.consequence

import co.voik.agesandtheart.age.aspect.Holders
import co.voik.agesandtheart.age.aspect.TreeFoliage
import co.voik.agesandtheart.worldgen.feature.TreeKinds
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.levelgen.feature.TreeFeature
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.feature.foliageplacers.FoliagePlacer
import net.minecraft.util.RandomSource
import net.minecraft.world.level.levelgen.placement.PlacedFeature
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool
import java.util.function.Predicate

/**
 * What the strange tier does to an Age's ground as it generates (Jonah, 2026-10-12): every feature the same shape,
 * trees with no leaves, and every village the abandoned one — and, on the same seam, the block a book grew its trees
 * with in place of their leaves ([TreeFoliage]).
 *
 * Asked by `FeaturePlacerMixin`, `TreeFeatureMixin` and `JigsawPlacementMixin` for every feature, tree and
 * structure anywhere, and answered from what the Age's generator sets around its own decoration and structure
 * starts, on the worker thread doing them — never by looking the Age up each time. Elsewhere, one read.
 */
object StrangeGround {

    private val decorating = ThreadLocal<Decorating?>()
    private val abandoning = ThreadLocal<Boolean?>()

    /** The block a sapling growing on this thread grows in place of its leaves, where its Age said one. */
    private val saplingGrowsWith = ThreadLocal<BlockState?>()

    /** The placed feature this thread is placing now, so a tree inside it can ask whether it is one of the bare. */
    private val placing = ThreadLocal<String?>()

    private data class Decorating(
        val seed: Long,
        val stamped: Holders,
        val leafless: Holders,
        val foliage: TreeFoliage,
        val idOf: (PlacedFeature) -> String?,
        /** The logs of the kinds of tree left bare (`bare birch`), each bare wherever it stands. */
        val leaflessLogs: Set<Block>,
        /** What each kind of tree a book grew of something grows in place of its leaves (`diamond_block cherry`). */
        val foliageByLog: Map<Block, BlockState>,
    )

    /**
     * Runs [decoration] with this thread doing to it whatever [consequence] holds, off the Age's [seed]. [idOf]
     * names a placed feature, since what a book said of `trees` it said of those features and no others; [logsOf] the
     * logs of the trees a placed feature grows, since what it said of one kind of tree it said of every tree of its log.
     */
    fun decorating(
        consequence: Consequence,
        foliage: TreeFoliage,
        seed: Long,
        idOf: (PlacedFeature) -> String?,
        logsOf: (String) -> Set<Block>,
        decoration: () -> Unit,
    ) {
        val leaflessLogs = consequence.leafless.only.flatMap(logsOf).toSet()
        val foliageByLog = foliage.byFeature.flatMap { (member, block) -> logsOf(member).map { log -> log to block } }.toMap()
        val wanted = Decorating(seed, consequence.stamped, consequence.leafless, foliage, idOf, leaflessLogs, foliageByLog)
        if (wanted.stamped.isNobody && wanted.leafless.isNobody && foliage.isNothing) return decoration()
        val was = decorating.get()
        decorating.set(wanted)
        try {
            decoration()
        } finally {
            decorating.set(was)
        }
    }

    /** Grows a sapling through [growing] with [block] in place of its leaves, answering whether it grew. */
    fun growingWith(block: BlockState, growing: () -> Boolean): Boolean {
        val was = saplingGrowsWith.get()
        saplingGrowsWith.set(block)
        try {
            return growing()
        } finally {
            saplingGrowsWith.set(was)
        }
    }

    /** Runs [starting] with this thread abandoning every village it starts, if [consequence] bought it. */
    fun startingStructures(consequence: Consequence, starting: () -> Unit) {
        if (!consequence.abandonsVillages) return starting()
        val was = abandoning.get()
        abandoning.set(true)
        try {
            starting()
        } finally {
            abandoning.set(was)
        }
    }

    /**
     * Places [placed] through [place], with the random its feature should be drawn with: [random] itself, unless
     * this thread is stamping it, when it is one seeded per feature — so where a feature goes is the world's own
     * and what goes there is not. Remembers which placed feature this is while it places, for [isLeafless].
     */
    @JvmStatic
    fun place(placed: PlacedFeature, random: RandomSource, place: Predicate<RandomSource>): Boolean {
        val doing = decorating.get() ?: return place.test(random)
        val id = doing.idOf(placed)
        val was = placing.get()
        placing.set(id)
        try {
            return place.test(if (doing.stamped.includes(id)) stampedRandom(doing.seed, placed) else random)
        } finally {
            placing.set(was)
        }
    }

    private fun stampedRandom(seed: Long, placed: PlacedFeature): RandomSource {
        val feature = placed.feature().unwrapKey().map { it.identifier().toString() }
            .orElseGet { placed.feature().value().javaClass.name }
        return RandomSource.create(seed xor feature.hashCode().toLong())
    }

    /** Whether [tree], placed on this thread, should be left without its leaves: its feature or its kind left bare. */
    @JvmStatic
    fun isLeafless(tree: TreeFeature): Boolean {
        val doing = decorating.get() ?: return false
        return doing.leafless.includes(placing.get()) || TreeKinds.logOf(tree) in doing.leaflessLogs
    }

    /**
     * [setter] setting, wherever [tree] placed on this thread would set a leaf, the block its book grew it with in
     * place of its leaves — said of its feature, of its kind, or of every tree — or [setter] itself where it grows leaves.
     */
    @JvmStatic
    fun grownWithItsFoliage(setter: FoliagePlacer.FoliageSetter, tree: TreeFeature): FoliagePlacer.FoliageSetter {
        val doing = decorating.get()
        val ofItsKind = doing?.foliageByLog?.get(TreeKinds.logOf(tree))
        val ofItsFeature = placing.get()?.let { doing?.foliage?.byFeature?.get(it) }
        val grownWith = ofItsFeature ?: ofItsKind ?: doing?.foliage?.everyTree ?: saplingGrowsWith.get() ?: return setter
        return object : FoliagePlacer.FoliageSetter {
            override fun set(pos: BlockPos, state: BlockState) = setter.set(pos, grownWith)

            override fun isSet(pos: BlockPos): Boolean = setter.isSet(pos)
        }
    }

    /**
     * The piece a structure starts from: [drawn] itself, unless this thread is abandoning villages and [pool]
     * holds an abandoned one — a village's start pool keeps its zombie town centres beside the living ones, and
     * the start decides which the whole village is.
     */
    @JvmStatic
    fun startFrom(pool: StructureTemplatePool, random: RandomSource, drawn: StructurePoolElement): StructurePoolElement {
        if (abandoning.get() != true) return drawn
        return pool.getShuffledTemplates(random).firstOrNull(::isAbandoned) ?: drawn
    }

    private fun isAbandoned(element: StructurePoolElement): Boolean {
        val single = element as? SinglePoolElement ?: return false
        // A piece built from an inline template rather than a file names nothing, and `orThrow` says so by throwing.
        val location = runCatching { single.templateLocation }.getOrNull() ?: return false
        return location.path.contains(ABANDONED_PIECES)
    }

    /** Where vanilla keeps an abandoned village's pieces: `village/<kind>/zombie/…`. */
    private const val ABANDONED_PIECES = "/zombie/"
}
