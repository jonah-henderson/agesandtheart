package co.voik.agesandtheart.command

import co.voik.agesandtheart.BlockLightFlood
import co.voik.agesandtheart.age.Report
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.coordinates.BlockPosArgument
import net.minecraft.core.BlockPos
import net.minecraft.world.level.LightLayer

internal object LightInstruments {

    fun addTo(age: LiteralArgumentBuilder<CommandSourceStack>) {
        age.then(lightFloodSubcommand())
    }

    /**
     * `/age lightflood <pos>` — **whether coloured light reaches exactly where vanilla's does**: the block at
     * [POS_ARGUMENT] flooded by [BlockLightFlood], against the block light the server's own engine holds, at every
     * block either reaches. Meant with nothing else lit nearby, where the two should agree at every block.
     */
    private fun lightFloodSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        reporting("lightflood") { reportFor ->
            Commands.argument(POS_ARGUMENT, BlockPosArgument.blockPos())
                .executes { context -> runLightFlood(context, reportFor(context)) }
        }

    private fun runLightFlood(context: CommandContext<CommandSourceStack>, report: Report): Int {
        val level = context.source.level
        val source = BlockPosArgument.getLoadedBlockPos(context, POS_ARGUMENT)
        val emitted = level.getBlockState(source).lightEmission
        val flooded = BlockLightFlood.from(level, source, emitted)
        var compared = 0
        var differing = 0
        val reach = emitted.coerceAtLeast(1)
        for (at in BlockPos.betweenClosed(source.offset(-reach, -reach, -reach), source.offset(reach, reach, reach))) {
            val ours = flooded.get(at.asLong()).toInt()
            val theirs = level.getBrightness(LightLayer.BLOCK, at)
            if (ours == 0 && theirs == 0) continue
            compared++
            if (ours == theirs) continue
            differing++
            report.entry("differing", mapOf("at" to "${at.x} ${at.y} ${at.z}", "flooded" to ours, "vanilla" to theirs)) {
                "${at.x} ${at.y} ${at.z}: flooded $ours, vanilla $theirs"
            }
        }
        report.fact("compared", compared) { "$compared lit block(s) compared around a light of $emitted" }
        report.fact("differing", differing) { "$differing differ" }
        report.finish()
        return SUCCESS
    }

    private const val POS_ARGUMENT = "pos"
}
