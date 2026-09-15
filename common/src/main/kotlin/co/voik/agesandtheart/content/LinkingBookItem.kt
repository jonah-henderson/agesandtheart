package co.voik.agesandtheart.content

import co.voik.agesandtheart.age.Ages
import co.voik.agesandtheart.age.word.WordNames
import co.voik.agesandtheart.book.LinkTarget
import co.voik.agesandtheart.client.BookScreenOpener
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
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
 * A Linking Book: a door to one place that already exists.
 *
 * Blank until it is used, at which point it takes the spot it was written in — so the way you make a way
 * home is to write one *at* home and carry it with you. Opened again, its panel goes there (design §7.8.2).
 *
 * **One item, two states**, distinguished by whether it carries a [LinkTarget]. A separate blank item
 * would need its own recipe, model and name for no gain, and the two are the same object in the fiction —
 * a book, before and after it was written in.
 *
 * Like a Descriptive Book it is **left behind** when used (design §7.8), which is what makes a linking
 * book a one-way trip rather than a teleporter. Two of them, one at each end, is a permanent route — and
 * building that route is the point.
 */
class LinkingBookItem(properties: Properties) : Item(properties) {

    /**
     * Writes a blank book, and opens a bound one rather than linking outright — as a descriptive book does, and
     * for its reason: linking spends the book and can strand you, so it is a click on the panel inside rather
     * than one misclick away from a hotbar slot (see [co.voik.agesandtheart.book.Linking]).
     */
    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        val stack = player.getItemInHand(hand)
        val bound = stack.get(AgeContent.LINK_TARGET) != null
        // Guarded so the screen class is never loaded on a dedicated server.
        if (bound && level.isClientSide) BookScreenOpener.open(stack, hand)
        if (!bound && level is ServerLevel && player is ServerPlayer) return bind(stack, level, player)
        return InteractionResult.SUCCESS
    }

    /** Writing it: the book takes this exact spot, facing the way you were — and the Age behind it. */
    private fun bind(stack: ItemStack, level: ServerLevel, player: ServerPlayer): InteractionResult {
        val target = LinkTarget(
            dimension = level.dimension(),
            position = player.position(),
            yaw = player.yRot,
            name = nameOf(level),
            // Taken now rather than looked up later, because later there may be nothing to look it up in:
            // the whole point is a book that outlives the Age it names (design §9, "Losing the books").
            recipe = Ages.recipeOf(level),
        )
        stack.set(AgeContent.LINK_TARGET, target)
        player.sendSystemMessage(Component.translatable("book.agesandtheart.bound", target.name), true)
        return InteractionResult.SUCCESS
    }

    /** "<place> Linking Book", so a shelf of them reads at a glance. */
    override fun getName(itemStack: ItemStack): Component {
        val target = itemStack.get(AgeContent.LINK_TARGET) ?: return super.getName(itemStack)
        return Component.translatable("item.agesandtheart.linking_book.bound", target.name)
    }

    override fun appendHoverText(
        stack: ItemStack,
        context: TooltipContext,
        display: TooltipDisplay,
        builder: Consumer<Component>,
        flag: TooltipFlag,
    ) {
        val target = stack.get(AgeContent.LINK_TARGET)
        if (target == null) {
            builder.accept(
                Component.translatable("item.agesandtheart.linking_book.blank")
                    .withStyle(ChatFormatting.DARK_GRAY),
            )
            return
        }
        // Coordinates, because a route you built deserves to be checkable.
        builder.accept(
            Component.literal(
                "%d, %d, %d".format(
                    target.position.x.toInt(),
                    target.position.y.toInt(),
                    target.position.z.toInt(),
                ),
            ).withStyle(ChatFormatting.DARK_GRAY),
        )
    }

    private companion object {
        /**
         * What to call the place. An Age's id path is the name its writer gave it; anywhere else falls
         * back to the dimension's own, so a book written in the Overworld reads sensibly too.
         */
        fun nameOf(level: ServerLevel): String =
            WordNames.titleCase(level.dimension().identifier().path.replace('_', ' '))
    }
}
