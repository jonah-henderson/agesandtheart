package co.voik.agesandtheart.content

import co.voik.agesandtheart.age.word.Acquaintance
import co.voik.agesandtheart.age.word.Acquainted
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.TooltipDisplay
import net.minecraft.world.level.Level
import java.util.function.Consumer

/**
 * The D'ni surveying device: learn what the Art calls the place you are standing in (design §8.3).
 *
 * **A tool rather than a station**, because a biome is not holdable — so where the analysis machine's gate
 * is possession, this one's is *presence*. To write a crimson forest you must have been to the Nether,
 * which is exactly the right price for a referent whose whole nature is where it is.
 *
 * **A survey consumes nothing.** A place cannot be consumed, and the travel already was the cost. That
 * makes surveying an exploration reward, and it closes a loop worth having: write a cheap vague Age, let
 * the resolver put you somewhere you never named, survey it, and now you can name it precisely — vague
 * writing feeding precise writing.
 */
class SurveyingDeviceItem(properties: Properties) : Item(properties) {

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val surveyor = player as? ServerPlayer ?: return InteractionResult.FAIL
        val outcome = Acquaintance.withPlace(surveyor)
        Acquaintance.tell(surveyor, outcome)
        if (outcome is Acquainted.Learned) {
            level.playSound(null, player.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, VOLUME, PITCH)
        }
        return InteractionResult.SUCCESS
    }

    override fun appendHoverText(
        stack: ItemStack,
        context: TooltipContext,
        display: TooltipDisplay,
        builder: Consumer<Component>,
        flag: TooltipFlag,
    ) {
        builder.accept(
            Component.translatable("item.agesandtheart.surveying_device.hint").withStyle(ChatFormatting.DARK_GRAY),
        )
    }

    private companion object {
        const val VOLUME = 1.0f
        const val PITCH = 1.0f
    }
}
