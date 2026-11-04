package co.voik.agesandtheart.generation

import co.voik.agesandtheart.age.aspect.Features
import co.voik.agesandtheart.age.aspect.Options
import co.voik.agesandtheart.location
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.tags.TagKey
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * What an Age's features build of rock above the ground, crumbled — `eroded features`, `worn features`
 * ([Features.CRUMBLE]). A share of those blocks is never laid, scattered by the block's own place, so a boulder or a
 * spike or a formation stands broken rather than whole.
 *
 * **Only rock, and only above the ground.** What crumbles is [CRUMBLES], stone and its kin, so the trees and plants are
 * left alone. And only what stands over the terrain's own surface, read off `OCEAN_FLOOR_WG`, which the features do not
 * move: that is the rock as the fill left it, so anything placed under it, an ore vein high in a mountain or a geode,
 * is inside the ground and stays whole.
 *
 * Set around the Age's decoration by its generator, and asked of each feature's placement by `FeaturePlacerMixin`; it
 * costs one thread-local read anywhere it is not set.
 */
object CrumbledFeatures {

    /** The blocks a crumbling feature may lose: rock, never wood or leaves. */
    val CRUMBLES: TagKey<Block> = TagKey.create(Registries.BLOCK, "crumbles".location())

    private class Crumbling(val share: Double, val seed: Long)

    private val crumbling = ThreadLocal<Crumbling?>()

    /** How much of what the features build of rock above the ground goes: none unsaid, [MOST_CRUMBLED] at the most. */
    fun shareIn(options: Options, salt: Long): Double =
        (options.steer(Features.CRUMBLE, salt) ?: UNSAID).coerceIn(0.0, 1.0) * MOST_CRUMBLED

    /** Runs [decorate] with the features crumbling [share] of their rock, or untouched where it is none. */
    fun decorating(share: Double, seed: Long, decorate: () -> Unit) {
        if (share <= 0.0) return decorate()
        val before = crumbling.get()
        crumbling.set(Crumbling(share, seed))
        try {
            decorate()
        } finally {
            crumbling.set(before)
        }
    }

    /** [level], or a view of it that lays only what does not crumble, while an Age's features are crumbling. */
    @JvmStatic
    fun levelFor(level: WorldGenLevel): WorldGenLevel {
        val now = crumbling.get() ?: return level
        val handler = Laying(level, now)
        return Proxy.newProxyInstance(level.javaClass.classLoader, arrayOf(WorldGenLevel::class.java), handler) as WorldGenLevel
    }

    /** Whether the block [state] at [at] is one of the share that is never laid. */
    private fun crumblesAt(level: WorldGenLevel, at: BlockPos, state: BlockState, crumbling: Crumbling): Boolean {
        if (!state.`is`(CRUMBLES)) return false
        val standsOverTheGround = at.y >= level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, at.x, at.z)
        return standsOverTheGround && drawAt(at, crumbling.seed) < crumbling.share
    }

    /** A number in `0..1` fixed by the block's place and the Age, so a chunk comes out the same every time. */
    private fun drawAt(at: BlockPos, seed: Long): Double {
        var mixed = at.asLong() * PLACE_MIXER xor seed
        mixed = (mixed xor (mixed ushr SHIFT_ONE)) * SPREAD_ONE
        mixed = (mixed xor (mixed ushr SHIFT_TWO)) * SPREAD_TWO
        mixed = mixed xor (mixed ushr SHIFT_THREE)
        return (mixed ushr FRACTION_SHIFT).toDouble() / FRACTION_RANGE
    }

    /** Lays every block but the share that crumbles, which it reports laid so a feature carries on as it would. */
    private class Laying(private val real: WorldGenLevel, private val crumbling: Crumbling) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, arguments: Array<Any?>?): Any? {
            val at = arguments?.getOrNull(0) as? BlockPos
            val state = arguments?.getOrNull(1) as? BlockState
            val isLaying = method.name == SET_BLOCK && at != null && state != null
            if (isLaying && crumblesAt(real, requireNotNull(at), requireNotNull(state), crumbling)) return true
            return try {
                method.invoke(real, *(arguments ?: emptyArray()))
            } catch (thrown: InvocationTargetException) {
                throw thrown.targetException
            }
        }
    }

    private const val SET_BLOCK = "setBlock"

    private const val UNSAID = 0.0

    /** At the most, two blocks in five of what stands over the ground go. */
    private const val MOST_CRUMBLED = 0.4

    // A 64-bit mix of the place (SplitMix64's constants), its top 53 bits read as a share.
    private const val PLACE_MIXER = -0x61c8864680b583ebL
    private const val SPREAD_ONE = -0x40a7b892e31b1a47L
    private const val SPREAD_TWO = -0x6b2fb644ecceee15L
    private const val SHIFT_ONE = 30
    private const val SHIFT_TWO = 27
    private const val SHIFT_THREE = 31
    private const val FRACTION_SHIFT = 11
    private const val FRACTION_RANGE = (1L shl 53).toDouble()
}
