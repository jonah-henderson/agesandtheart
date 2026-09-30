package co.voik.agesandtheart.command

import co.voik.agesandtheart.age.reward.PaperTreeWindow
import co.voik.agesandtheart.age.reward.ScarabHabitat
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.content.PaperTreeRootBlockEntity
import co.voik.agesandtheart.generation.AgeGeneration
import net.minecraft.core.BlockPos
import co.voik.agesandtheart.content.ScarabArrivals
import co.voik.agesandtheart.generation.Ages
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component

internal object RewardInstruments {

    /** What an Age a player wrote will give back, forced rather than waited for. */
    fun addTo(age: LiteralArgumentBuilder<CommandSourceStack>) {
        age.then(scarabSubcommand())
            .then(yemaSubcommand())
    }

    /**
     * `/age yema` — whether this Age holds the paper tree's window, and how the nearest tree's heart stands:
     * its moisture and band, its strain and the stage that has reached, and its seed.
     *
     * A readout and nothing more. A tree is grown with `/place feature agesandtheart:paper_tree`, and its
     * roots wetted and dried with `/age tide`.
     */
    private fun yemaSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("yema").executes(::runYema)

    private fun runYema(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val level = source.level
        val window = Ages.recipeOf(level)?.let { PaperTreeWindow.read(it, AgeGeneration.skySpec(it)) }
        val windowSaid = window?.let {
            "Written by a player: ${it.writtenByAPlayer}; polar sun: ${it.polarSun}; tidal: ${it.tidal}."
        } ?: "No written Age, and no window."
        source.sendSuccess({ Component.literal(windowSaid) }, false)
        val from = BlockPos.containing(source.position)
        val corner = from.offset(-HEART_SEARCH, -HEART_SEARCH, -HEART_SEARCH)
        val farCorner = from.offset(HEART_SEARCH, HEART_SEARCH, HEART_SEARCH)
        val heart = BlockPos.betweenClosedStream(corner, farCorner)
            .filter { level.getBlockState(it).`is`(AgeContent.PAPER_TREE_ROOT_BLOCK) }
            .map(BlockPos::immutable)
            .toList()
            .minByOrNull { it.distSqr(from) }
            ?: return SUCCESS.also {
                source.sendSuccess({ Component.literal("No yema heart within reach.") }, false)
            }
        val root = level.getBlockEntity(heart) as? PaperTreeRootBlockEntity ?: return FAILURE
        val said = "The heart at ${heart.toShortString()}: ${root.describe(level)}"
        source.sendSuccess({ Component.literal(said) }, false)
        return SUCCESS
    }

    /** How far `/age yema` looks for a heart, every way. */
    private const val HEART_SEARCH = 16

    /**
     * `/age scarab` — a scarab brought in near you now, by the same placement a natural arrival takes, and
     * the reading its odds would have come from.
     *
     * **The odds are skipped and nothing else is**: the placement still wants jungle ground and daylight,
     * and what the scarab does next is its own search, so one forced into an Age that falls short wanders
     * and goes exactly as a natural stray would.
     */
    private fun scarabSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("scarab").executes(::runScarab)

    private fun runScarab(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.player ?: return FAILURE.also {
            source.sendFailure(Component.literal("Only a player can have a scarab brought near them"))
        }
        val level = source.level
        val age = Ages.recipeOf(level)?.let { ScarabHabitat.readAge(level, it) } ?: return FAILURE.also {
            source.sendFailure(Component.literal("This is no written Age, and no scarab comes to one"))
        }
        val met = ScarabArrivals.conditionsMet(level, player.blockPosition(), age)
        source.sendSuccess(
            {
                Component.literal(
                    "Written by a player: ${age.writtenByAPlayer}; warm enough: ${age.isWarmEnough}; " +
                        "jungle: ${age.anyJungle}; torchflowers wild: ${age.growsTorchflowersWild}. " +
                        "Arrival conditions met here: $met of 3.",
                )
            },
            false,
        )
        val scarab = ScarabArrivals.bringOneNear(level, player) ?: return FAILURE.also {
            source.sendFailure(Component.literal("Nowhere to bring one: no jungle ground in reach, or it is night"))
        }
        source.sendSuccess({ Component.literal("A scarab arrives at ${scarab.blockPosition().toShortString()}") }, false)
        return SUCCESS
    }
}
