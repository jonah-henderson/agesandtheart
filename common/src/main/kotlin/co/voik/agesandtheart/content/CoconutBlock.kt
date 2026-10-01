package co.voik.agesandtheart.content

import net.minecraft.core.BlockPos
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.CocoaBlock
import net.minecraft.world.level.block.state.BlockState

/**
 * A coconut, hanging from a palm's trunk under its crown — vanilla's cocoa pod in everything but what it
 * hangs from: it ripens over the same three stages, takes bonemeal the same way, and drops more when ripe.
 *
 * **On a palm log and nothing else**, where cocoa asks for jungle wood. `CocoaBlock` drops itself when that
 * fails, so a felled palm loses its coconuts as a jungle tree loses its pods.
 */
class CoconutBlock(properties: Properties) : CocoaBlock(properties) {

    override fun canSurvive(state: BlockState, level: LevelReader, pos: BlockPos): Boolean =
        level.getBlockState(pos.relative(state.getValue(FACING))).`is`(PalmWood.LOGS)
}
