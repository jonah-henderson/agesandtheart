package co.voik.agesandtheart.generation

import co.voik.agesandtheart.NEEDS_REGISTRIES
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.RotatedPillarBlock

/**
 * A charred structure's wood goes black or burns, a crumbled one loses some of its blocks, and neither ever touches
 * what holds something (R5).
 */
@Tags(NEEDS_REGISTRIES)
class StructureWeatheringCheck : FunSpec({

    val charred = StructureWeathering(crumble = 0.0, charred = true)
    val crumbled = StructureWeathering(crumble = 1.0, charred = false)
    val places = (0..<PLACES).map { BlockPos(it % 40, it / 1600, it / 40 % 40) }

    test("what holds something always stands") {
        val chest = Blocks.CHEST.defaultBlockState()
        check(places.all { charred.weathered(it, chest, SEED) == chest && crumbled.weathered(it, chest, SEED) == chest }) {
            "a chest was weathered"
        }
    }

    test("charred planks go to blackstone or burn, logs to basalt along their axis, fences burn, stone stands") {
        val planks = places.map { charred.weathered(it, Blocks.OAK_PLANKS.defaultBlockState(), SEED)?.block }.toSet()
        check(planks == setOf(Blocks.POLISHED_BLACKSTONE, null)) { "charred planks became $planks" }
        val log = Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X)
        val burnt = places.mapNotNull { charred.weathered(it, log, SEED) }.first()
        check(burnt.`is`(Blocks.BASALT) && burnt.getValue(RotatedPillarBlock.AXIS) == Direction.Axis.X) { "a charred log became $burnt" }
        check(places.all { charred.weathered(it, Blocks.OAK_FENCE.defaultBlockState(), SEED) == null }) { "a charred fence stood, though blackstone has no fence" }
        val stone = Blocks.STONE_BRICKS.defaultBlockState()
        check(places.all { charred.weathered(it, stone, SEED) == stone }) { "charring touched stone" }
    }

    test("a crumble in full takes about two blocks in five, and none at nought") {
        val stone = Blocks.COBBLESTONE.defaultBlockState()
        val gone = places.count { crumbled.weathered(it, stone, SEED) == null }.toDouble() / PLACES
        check(gone in MOST_CRUMBLED_ROUGHLY) { "a full crumble took $gone" }
        check(places.all { StructureWeathering.NONE.weathered(it, stone, SEED) == stone }) { "nothing crumbled something" }
    }
}) {
    private companion object {
        const val SEED = 20261105L
        const val PLACES = 4000
        val MOST_CRUMBLED_ROUGHLY = 0.35..0.45
    }
}
