package co.voik.agesandtheart.age.consequence

import net.minecraft.util.RandomSource
import net.minecraft.world.level.levelgen.placement.PlacedFeature
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool

/**
 * What the strange tier does to an Age's ground as it generates (Jonah, 2026-10-12): every feature the same shape,
 * trees with no leaves, and every village the abandoned one.
 *
 * Asked by `FeaturePlacerMixin`, `TreeFeatureMixin` and `JigsawPlacementMixin` for every feature, tree and
 * structure anywhere, and answered from what the Age's generator sets around its own decoration and structure
 * starts, on the worker thread doing them — never by looking the Age up each time. Elsewhere, one read.
 */
object StrangeGround {

    private val decorating = ThreadLocal<Decorating?>()
    private val abandoning = ThreadLocal<Boolean?>()

    private data class Decorating(val stampedOff: Long?, val leafless: Boolean)

    /** Runs [decoration] with this thread doing to it whatever [consequence] bought, off the Age's [seed]. */
    fun decorating(consequence: Consequence, seed: Long, decoration: () -> Unit) {
        val wanted = Decorating(seed.takeIf { consequence.stampsShapes }, consequence.leafless)
        if (wanted.stampedOff == null && !wanted.leafless) return decoration()
        val was = decorating.get()
        decorating.set(wanted)
        try {
            decoration()
        } finally {
            decorating.set(was)
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
     * The random [placed]'s feature should be drawn with: [random] itself, unless this thread is stamping, when
     * it is one seeded per feature — so where a feature goes is the world's own and what goes there is not.
     */
    @JvmStatic
    fun randomFor(placed: PlacedFeature, random: RandomSource): RandomSource {
        val seed = decorating.get()?.stampedOff ?: return random
        val feature = placed.feature().unwrapKey().map { it.identifier().toString() }
            .orElseGet { placed.feature().value().javaClass.name }
        return RandomSource.create(seed xor feature.hashCode().toLong())
    }

    /** Whether a tree placed on this thread should be left without its leaves. */
    @JvmStatic
    fun isLeafless(): Boolean = decorating.get()?.leafless == true

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
