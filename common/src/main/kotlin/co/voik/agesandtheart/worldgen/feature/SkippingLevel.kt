package co.voik.agesandtheart.worldgen.feature

import net.minecraft.core.BlockPos
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.state.BlockState
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * A view of a level that lays every block but those [skips] answers for, reporting a skipped one laid so the feature
 * placing it carries on as it would.
 */
object SkippingLevel {

    fun of(real: WorldGenLevel, skips: (BlockPos, BlockState) -> Boolean): WorldGenLevel =
        Proxy.newProxyInstance(real.javaClass.classLoader, arrayOf(WorldGenLevel::class.java), Laying(real, skips)) as WorldGenLevel

    private class Laying(private val real: WorldGenLevel, private val skips: (BlockPos, BlockState) -> Boolean) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, arguments: Array<Any?>?): Any? {
            val at = arguments?.getOrNull(0) as? BlockPos
            val state = arguments?.getOrNull(1) as? BlockState
            val isLaying = method.name == SET_BLOCK && at != null && state != null
            if (isLaying && skips(requireNotNull(at), requireNotNull(state))) return true
            return try {
                method.invoke(real, *(arguments ?: emptyArray()))
            } catch (thrown: InvocationTargetException) {
                throw thrown.targetException
            }
        }
    }

    private const val SET_BLOCK = "setBlock"
}
