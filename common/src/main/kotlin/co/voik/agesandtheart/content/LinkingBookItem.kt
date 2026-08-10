package co.voik.agesandtheart.content

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.age.AgeRecipe
import co.voik.agesandtheart.age.AgeSavedData
import co.voik.agesandtheart.age.Ages
import co.voik.agesandtheart.age.word.WordNames
import co.voik.agesandtheart.book.BookEntity
import co.voik.agesandtheart.book.LinkTarget
import co.voik.runtimelevels.sky.LevelAppearance
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
 * home is to write one *at* home and carry it with you. Used again, it goes there.
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

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        val stack = player.getItemInHand(hand)
        if (level !is ServerLevel || player !is ServerPlayer) return InteractionResult.SUCCESS
        val target = stack.get(AgeContent.LINK_TARGET)
        return if (target == null) bind(stack, level, player) else travel(stack, target, level, player, hand)
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
            recipe = recipeBehind(level),
        )
        stack.set(AgeContent.LINK_TARGET, target)
        player.sendSystemMessage(Component.translatable("book.agesandtheart.bound", target.name), true)
        return InteractionResult.SUCCESS
    }

    /** The recipe of the Age this book is being written in, and null anywhere that is not one of ours. */
    private fun recipeBehind(level: ServerLevel): AgeRecipe? {
        val id = level.dimension().identifier()
        if (id !in AgeSavedData.get(level.server).ages) return null
        return AgeSavedData.get(level.server).recipe(id)
    }

    /** Puts back an Age this book outlived, or null where there is nothing to put back. */
    private fun restore(target: LinkTarget, level: ServerLevel): ServerLevel? {
        val recipe = target.recipe ?: return null
        Constants.LOG.info("Restoring '{}' from a linking book that outlived it", target.dimension.identifier())
        return Ages.ensure(level.server, target.dimension.identifier(), recipe)
    }

    /** Going. The book stays where it was used, exactly as a Descriptive Book does. */
    private fun travel(
        stack: ItemStack,
        target: LinkTarget,
        level: ServerLevel,
        player: ServerPlayer,
        hand: InteractionHand,
    ): InteractionResult {
        // Linking is travel *between* worlds. A book that moves you within one is not a linking book —
        // which is both the lore and, incidentally, what stops this being an overland taxi.
        if (target.dimension == level.dimension()) {
            player.sendSystemMessage(Component.translatable("book.agesandtheart.same_world"), true)
            return InteractionResult.FAIL
        }
        // A missing destination is an Age that was collected while this book survived, so the book puts
        // it back — same terrain, deterministically, and empty of whatever was built in it. Only a
        // vanilla dimension can still be genuinely unreachable, and none of those is ours to restore.
        val destination = level.server.getLevel(target.dimension) ?: restore(target, level)
        if (destination == null) {
            player.sendSystemMessage(Component.translatable("book.agesandtheart.no_destination"), true)
            return InteractionResult.FAIL
        }
        val leftAt = player.position()
        val left = stack.copy()
        player.setItemInHand(hand, ItemStack.EMPTY)

        // Before the move, not after: one stream carries both, so a sky sent first cannot arrive late.
        LevelAppearance.expecting(player, destination.dimension())
        player.teleportTo(
            destination,
            target.position.x,
            target.position.y,
            target.position.z,
            emptySet(),
            target.yaw,
            player.xRot,
            true,
        )
        BookEntity.leaveBehind(level, leftAt, left)
        player.sendSystemMessage(Component.translatable("book.agesandtheart.linked", target.name), true)
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
