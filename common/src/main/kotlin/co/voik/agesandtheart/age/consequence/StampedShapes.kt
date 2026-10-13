package co.voik.agesandtheart.age.consequence

import net.minecraft.util.RandomSource
import net.minecraft.world.level.levelgen.placement.PlacedFeature

/**
 * Every feature in the Age comes out the same shape (Jonah, 2026-10-12): where it is placed is the world's own
 * random, and what is placed there is drawn from one seed per feature — so every oak is the same oak.
 *
 * Asked by `FeaturePlacerMixin` for every feature placed anywhere, and answered from a flag the Age's generator
 * sets around its own decoration, on the worker thread doing it — never by looking the Age up per feature.
 */
object StampedShapes {

    private val stampingWith = ThreadLocal<Long?>()

    /** Runs [decoration] with this thread stamping shapes off [seed], if [stamps]. */
    fun decorating(stamps: Boolean, seed: Long, decoration: () -> Unit) {
        if (!stamps) return decoration()
        val was = stampingWith.get()
        stampingWith.set(seed)
        try {
            decoration()
        } finally {
            stampingWith.set(was)
        }
    }

    /** The random [placed]'s feature should be drawn with: [random] itself, unless this thread is stamping. */
    @JvmStatic
    fun randomFor(placed: PlacedFeature, random: RandomSource): RandomSource {
        val seed = stampingWith.get() ?: return random
        val feature = placed.feature().unwrapKey().map { it.identifier().toString() }
            .orElseGet { placed.feature().value().javaClass.name }
        return RandomSource.create(seed xor feature.hashCode().toLong())
    }
}
