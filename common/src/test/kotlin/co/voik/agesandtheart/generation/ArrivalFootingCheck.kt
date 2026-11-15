package co.voik.agesandtheart.generation

import co.voik.agesandtheart.NEEDS_REGISTRIES
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.BlockPos
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.FluidState
import java.lang.reflect.Proxy

/** A visitor is put on the footing nearest where the bare rock predicted, standing in what does not hold them. */
@Tags(NEEDS_REGISTRIES)
class ArrivalFootingCheck : FunSpec({

    /** Stone up to [GROUND], air above, but for [placed]; a world from y=-64 to 320. */
    fun worldOf(placed: Map<BlockPos, BlockState>): BlockGetter =
        Proxy.newProxyInstance(BlockGetter::class.java.classLoader, arrayOf(BlockGetter::class.java)) { _, method, arguments ->
            fun stateAt(at: BlockPos): BlockState = placed[at] ?: if (at.y <= GROUND) Blocks.STONE.defaultBlockState() else Blocks.AIR.defaultBlockState()
            when (method.name) {
                "getBlockState" -> stateAt(arguments[0] as BlockPos)
                "getFluidState" -> stateAt(arguments[0] as BlockPos).fluidState as FluidState
                "getMinY" -> MIN_Y
                "getHeight" -> HEIGHT
                "getMaxY" -> MIN_Y + HEIGHT - 1
                "getBlockEntity" -> null
                else -> error("the check's world was asked ${method.name}")
            }
        } as BlockGetter

    val predicted = BlockPos(0, GROUND + 1, 0)

    fun footing(placed: Map<BlockPos, BlockState>): BlockPos? = Ages.footingNear(worldOf(placed), predicted) { true }

    test("grass, flowers and a snow layer at the feet are stood in, not carved") {
        for (cover in listOf(Blocks.SHORT_GRASS, Blocks.POPPY, Blocks.SNOW)) {
            check(footing(mapOf(predicted to cover.defaultBlockState())) == predicted) { "${cover} at the feet moved the visitor" }
        }
    }

    test("a drift over all the predicted footing is stood on top of, in the same column") {
        val drift = (-5..5).flatMap { dx -> (-5..5).flatMap { dz -> (0..<3).map { predicted.offset(dx, it, dz) to Blocks.SNOW_BLOCK.defaultBlockState() } } }.toMap()
        check(footing(drift) == predicted.above(3)) { "over a three-block drift the visitor stood at ${footing(drift)}" }
    }

    test("a trunk in the way puts the visitor on the ground beside it, not on the crown") {
        val trunk = (0..<6).associate { predicted.above(it) to Blocks.OAK_LOG.defaultBlockState() }
        val crown = (-2..2).flatMap { dx -> (-2..2).map { dz -> predicted.offset(dx, 6, dz) to Blocks.OAK_LEAVES.defaultBlockState() } }.toMap()
        val found = footing(trunk + crown)
        check(found != null && found.y == predicted.y && found != predicted) { "with a tree in the way the visitor stood at $found" }
    }

    test("nowhere to stand within reach is null, for the caller to carve") {
        val buried = (-5..5).flatMap { dx -> (-5..5).flatMap { dz -> (-7..25).map { dy -> predicted.offset(dx, dy, dz) to Blocks.STONE.defaultBlockState() } } }.toMap()
        check(footing(buried) == null) { "inside solid rock the visitor was put at ${footing(buried)}" }
    }

    test("lava is never stood in") {
        val pool = (-4..4).flatMap { dx -> (-4..4).map { dz -> predicted.offset(dx, 0, dz) to Blocks.LAVA.defaultBlockState() } }.toMap()
        val found = footing(pool)
        check(found == null || found.y != predicted.y) { "the visitor was put in lava at $found" }
    }
}) {
    private companion object {
        const val GROUND = 63
        const val MIN_Y = -64
        const val HEIGHT = 384
    }
}
