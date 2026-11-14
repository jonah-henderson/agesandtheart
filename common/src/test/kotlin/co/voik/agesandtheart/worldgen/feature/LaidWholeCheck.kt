package co.voik.agesandtheart.worldgen.feature

import co.voik.agesandtheart.NEEDS_REGISTRIES
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.BlockPos
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BedPart
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import java.lang.reflect.Proxy

/** A bed, a tall flower or a door laid through [LaidWhole] stands whole or not at all. */
@Tags(NEEDS_REGISTRIES)
class LaidWholeCheck : FunSpec({

    /** A level of air, but for what is set in it, written anywhere. */
    fun levelOf(blocks: MutableMap<BlockPos, BlockState>): WorldGenLevel =
        Proxy.newProxyInstance(WorldGenLevel::class.java.classLoader, arrayOf(WorldGenLevel::class.java)) { _, method, arguments ->
            when (method.name) {
                "getBlockState" -> blocks[arguments[0] as BlockPos] ?: Blocks.AIR.defaultBlockState()
                "setBlock" -> {
                    blocks[(arguments[0] as BlockPos).immutable()] = arguments[1] as BlockState
                    true
                }
                "ensureCanWrite" -> true
                else -> error("the check's level was asked ${method.name}")
            }
        } as WorldGenLevel

    fun isWhole(blocks: Map<BlockPos, BlockState>): Boolean = blocks.all { (at, state) ->
        when {
            state.hasProperty(BlockStateProperties.BED_PART) -> {
                val facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING)
                val isFoot = state.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT
                val other = if (isFoot) at.relative(facing) else at.relative(facing.opposite)
                val otherPart = if (isFoot) BedPart.HEAD else BedPart.FOOT
                blocks[other]?.let { it.`is`(state.block) && it.getValue(BlockStateProperties.BED_PART) == otherPart } == true
            }
            state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) -> {
                val isLower = state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER
                blocks[if (isLower) at.above() else at.below()]?.`is`(state.block) == true
            }
            else -> true
        }
    }

    test("a heap of beds laid a block at a time is every bed whole, and none laid over another") {
        val blocks = mutableMapOf<BlockPos, BlockState>()
        val view = LaidWhole.viewOf(levelOf(blocks))
        val beds = listOf(Blocks.BED.red(), Blocks.BED.blue(), Blocks.BED.white())
        for (x in 0..<6) for (z in 0..<6) view.setBlock(BlockPos(x, 0, z), beds[(x + z) % beds.size].defaultBlockState(), 2)
        val laid = blocks.values.count { it.hasProperty(BlockStateProperties.BED_PART) }
        check(laid > 0 && laid % 2 == 0) { "the heap laid $laid bed halves" }
        check(isWhole(blocks)) { "a bed was left without its other half: $blocks" }
    }

    test("a bed with no room beside it is not laid, and a head copied alone is dropped") {
        val blocks = mutableMapOf<BlockPos, BlockState>()
        val at = BlockPos(0, 0, 0)
        for (side in listOf(at.north(), at.south(), at.east(), at.west())) blocks[side] = Blocks.STONE.defaultBlockState()
        val view = LaidWhole.viewOf(levelOf(blocks))
        view.setBlock(at, Blocks.BED.red().defaultBlockState(), 2)
        val head = Blocks.BED.red().defaultBlockState().setValue(BlockStateProperties.BED_PART, BedPart.HEAD)
        view.setBlock(BlockPos(5, 0, 5), head, 2)
        check(blocks.values.none { it.`is`(Blocks.BED.red()) }) { "a bed was laid in half: $blocks" }
    }

    test("a tall flower stands with its upper half, or not under a roof") {
        val blocks = mutableMapOf<BlockPos, BlockState>()
        blocks[BlockPos(2, 1, 0)] = Blocks.STONE.defaultBlockState()
        val view = LaidWhole.viewOf(levelOf(blocks))
        view.setBlock(BlockPos(0, 0, 0), Blocks.PEONY.defaultBlockState(), 2)
        view.setBlock(BlockPos(2, 0, 0), Blocks.PEONY.defaultBlockState(), 2)
        check(blocks[BlockPos(0, 1, 0)]?.`is`(Blocks.PEONY) == true) { "the open peony grew no upper half" }
        check(blocks[BlockPos(2, 0, 0)] == null) { "a peony was laid under a roof it could not grow through" }
        check(isWhole(blocks)) { "a half was left alone: $blocks" }
    }
})
