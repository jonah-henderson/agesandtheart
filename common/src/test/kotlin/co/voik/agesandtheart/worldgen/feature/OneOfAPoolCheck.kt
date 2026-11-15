package co.voik.agesandtheart.worldgen.feature

import co.voik.agesandtheart.NEEDS_REGISTRIES
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.Holder
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import java.lang.reflect.Proxy

/** A feature made of a pool is laid of one block of it each time it is placed, and the same one every time there. */
@Tags(NEEDS_REGISTRIES)
class OneOfAPoolCheck : FunSpec({

    val pool = listOf(Blocks.HAY_BLOCK, Blocks.PUMPKIN, Blocks.MELON, Blocks.SNOW_BLOCK, Blocks.PACKED_ICE)
    val heap = OneOfAPool(Holder.direct(Heap(net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider.holderOf(Blocks.HAY_BLOCK.defaultBlockState()))), pool)

    test("each placement draws one block, the same at the same place, and the pool is all drawn somewhere") {
        val seed = 1234L
        val origins = (0..<200).map { BlockPos(it * 37, 64, it * -53) }
        check(origins.all { heap.drawnAt(it, seed) == heap.drawnAt(it, seed) }) { "one place drew two ways" }
        val drawn = origins.map { heap.drawnAt(it, seed) }.toSet()
        check(drawn == pool.toSet()) { "200 placements drew only $drawn" }
    }

    test("every block of the pool a placement lays is laid as the one drawn, keeping its state, and nothing else is changed") {
        val blocks = mutableMapOf<BlockPos, BlockState>()
        val level = Proxy.newProxyInstance(WorldGenLevel::class.java.classLoader, arrayOf(WorldGenLevel::class.java)) { _, method, arguments ->
            when (method.name) {
                "setBlock" -> {
                    blocks[(arguments[0] as BlockPos).immutable()] = arguments[1] as BlockState
                    true
                }
                else -> error("the check's level was asked ${method.name}")
            }
        } as WorldGenLevel
        val view = OneOfAPool.viewOf(level, pool.toSet(), Blocks.HAY_BLOCK)
        view.setBlock(BlockPos(0, 0, 0), Blocks.PUMPKIN.defaultBlockState(), 2)
        view.setBlock(BlockPos(1, 0, 0), Blocks.MELON.defaultBlockState(), 2)
        view.setBlock(BlockPos(2, 0, 0), Blocks.STONE.defaultBlockState(), 2)
        val laidOnItsSide = Blocks.HAY_BLOCK.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X)
        view.setBlock(BlockPos(3, 0, 0), Blocks.SNOW_BLOCK.defaultBlockState(), 2)
        view.setBlock(BlockPos(4, 0, 0), laidOnItsSide, 2)
        val pooled = listOf(0, 1, 3, 4).map { blocks.getValue(BlockPos(it, 0, 0)) }
        check(pooled.all { it.`is`(Blocks.HAY_BLOCK) }) { "the pool was laid as $pooled" }
        check(blocks.getValue(BlockPos(2, 0, 0)).`is`(Blocks.STONE)) { "a block outside the pool was changed" }
        check(blocks.getValue(BlockPos(4, 0, 0)) == laidOnItsSide) { "the hay lost its axis" }
    }
})
