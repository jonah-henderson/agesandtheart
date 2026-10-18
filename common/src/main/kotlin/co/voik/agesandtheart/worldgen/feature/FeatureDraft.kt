package co.voik.agesandtheart.worldgen.feature

import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.server.level.WorldGenRegion
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.FluidState
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.function.Predicate
import kotlin.math.abs

/**
 * What a feature lays, kept rather than written, so it can be written out changed — bigger ([ScaledFeature]) or
 * with its trunk thickened ([ThickTrunk]). The feature reads the real world through [level] with its own blocks
 * laid over it, so it places itself exactly as it would have.
 *
 * **Past the chunks worldgen may touch, it reads open air.** A feature grown bigger than vanilla made it asks about
 * blocks farther out than it ever did — a colossal crown two chunks away — and worldgen forbids reading there.
 * What it lays there is never written either ([canWrite]).
 */
class FeatureDraft(val real: WorldGenLevel) {

    /** Every block the feature laid, by where it laid it. */
    val laid: Map<BlockPos, BlockState> get() = drawn

    private val drawn = HashMap<BlockPos, BlockState>()

    /** The level to hand the feature: the real one, except that what is set is kept here. */
    val level: WorldGenLevel = Proxy.newProxyInstance(
        WorldGenLevel::class.java.classLoader,
        arrayOf(WorldGenLevel::class.java),
    ) { _, method, arguments -> answer(method, arguments ?: emptyArray()) } as WorldGenLevel

    private fun stateAt(at: BlockPos): BlockState = drawn[at] ?: if (canWrite(at)) real.getBlockState(at) else AIR

    private fun fluidAt(at: BlockPos): FluidState = drawn[at]?.fluidState ?: if (canWrite(at)) real.getFluidState(at) else AIR.fluidState

    @Suppress("UNCHECKED_CAST")
    private fun answer(method: Method, arguments: Array<Any?>): Any? = when (method.name) {
        "setBlock" -> true.also { drawn[(arguments[0] as BlockPos).immutable()] = arguments[1] as BlockState }
        "removeBlock", "destroyBlock" -> true.also { drawn[(arguments[0] as BlockPos).immutable()] = AIR }
        "getBlockState" -> stateAt(arguments[0] as BlockPos)
        "getFluidState" -> fluidAt(arguments[0] as BlockPos)
        "isStateAtPosition" -> (arguments[1] as Predicate<BlockState>).test(stateAt(arguments[0] as BlockPos))
        "isFluidAtPosition" -> (arguments[1] as Predicate<FluidState>).test(fluidAt(arguments[0] as BlockPos))
        "isEmptyBlock" -> stateAt(arguments[0] as BlockPos).isAir
        else -> try {
            method.invoke(real, *arguments)
        } catch (failure: InvocationTargetException) {
            throw failure.cause ?: failure
        }
    }

    /** Whether worldgen may write at [at]: inside the world, and in the chunk being decorated or one beside it. */
    fun canWrite(at: BlockPos): Boolean {
        if (real.isOutsideBuildHeight(at)) return false
        val region = real as? WorldGenRegion ?: return real.hasChunkAt(at)
        val center = region.center
        val withinX = abs(SectionPos.blockToSectionCoord(at.x) - center.x) <= WRITABLE_CHUNKS_AWAY
        val withinZ = abs(SectionPos.blockToSectionCoord(at.z) - center.z) <= WRITABLE_CHUNKS_AWAY
        return withinX && withinZ
    }

    private companion object {
        /** How many chunks from the one being decorated a feature may still write in. */
        const val WRITABLE_CHUNKS_AWAY = 1

        val AIR: BlockState = Blocks.AIR.defaultBlockState()
    }
}
