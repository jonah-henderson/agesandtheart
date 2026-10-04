package co.voik.agesandtheart.worldgen

import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.levelgen.material.MaterialRuleContext
import net.minecraft.world.level.levelgen.material.rule.MaterialRule
import net.minecraft.world.level.levelgen.material.rule.RuleEvaluator

/**
 * [rule], painting only where [chunk] still holds the Age's [rock] — **26.1's surface rule, which 26.3
 * dropped.**
 *
 * 26.1's `SurfaceSystem` consulted its rules only where the block was the settings' default block. 26.3's
 * `MaterialSystem` consults them on every solid block, ore veins having become a rule of their own. Our
 * generation leans on the old rule everywhere it lays something that is not the rock: the obsidian a crater
 * lake is held in came out as the gravel of an underwater floor, and a landmass of several stones had its
 * second one painted over where the first stood.
 *
 * Built for one chunk's surface pass and never written down, as vanilla's own `HolderHolder` is never.
 */
class DefaultBlockOnly(
    private val rule: MaterialRule,
    private val chunk: ChunkAccess,
    private val rock: Block,
) : MaterialRule {

    override fun compile(context: MaterialRuleContext): RuleEvaluator {
        val inner = rule.compile(context)
        val cursor = BlockPos.MutableBlockPos()
        return RuleEvaluator { x, y, z ->
            if (chunk.getBlockState(cursor.set(x, y, z)).`is`(rock)) inner.tryApply(x, y, z) else null
        }
    }

    override fun codec(): MapCodec<out MaterialRule> =
        throw UnsupportedOperationException("a surface pass's own rule is never serialised")
}
