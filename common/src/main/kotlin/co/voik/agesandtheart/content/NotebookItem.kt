package co.voik.agesandtheart.content

import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.SlotAccess
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ClickAction
import net.minecraft.world.inventory.Slot
import net.minecraft.world.inventory.tooltip.BundleTooltip
import net.minecraft.world.inventory.tooltip.TooltipComponent
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ItemStackTemplate
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.BundleContents
import net.minecraft.world.item.component.TooltipDisplay
import java.util.Optional
import java.util.function.Consumer

/**
 * Holds pages, and behaves like a bundle doing it.
 *
 * The clicks are the point: right-click a page onto a notebook to file it, right-click a notebook onto a
 * slot to take one out. Without those you can only tip the whole thing out, which is useless when what
 * you want is the one page you are looking for.
 *
 * **Unlimited, which is why it is not literally a bundle.** A bundle's capacity lives in a private weight
 * check, so reusing `BundleContents` would cap this at sixty-four pages — plenty for a pocket, nowhere
 * near enough for a catalogue of a corpus that runs to four figures. The contents are ours; only the
 * *tooltip* is borrowed, so it still previews like the thing it imitates.
 *
 * Pages stay pages throughout. A found page is what a book gets written from, so filing one must never
 * dissolve it into a list of words.
 */
class NotebookItem(properties: Properties) : Item(properties) {

    /** Right-clicking the notebook while it is the carried stack, onto a slot. */
    override fun overrideStackedOnOther(
        self: ItemStack,
        slot: Slot,
        clickAction: ClickAction,
        player: Player,
    ): Boolean {
        if (clickAction != ClickAction.SECONDARY) return false
        val held = pagesIn(self)
        val target = slot.item
        return if (target.isEmpty) {
            // Onto an empty slot: the most recently filed page comes back out.
            val top = held.lastOrNull() ?: return false
            if (!slot.mayPlace(top)) return false
            slot.setByPlayer(top)
            setPages(self, held.dropLast(1))
            play(player, false)
            true
        } else {
            // Onto something: file it, if it is a page.
            if (!isPage(target)) return false
            setPages(self, held + slot.safeTake(target.count, target.count, player))
            play(player, true)
            true
        }
    }

    /** Right-clicking something onto the notebook while the notebook sits in a slot. */
    override fun overrideOtherStackedOnMe(
        self: ItemStack,
        other: ItemStack,
        slot: Slot,
        clickAction: ClickAction,
        player: Player,
        carriedItem: SlotAccess,
    ): Boolean {
        if (clickAction != ClickAction.SECONDARY || !slot.allowModification(player)) return false
        val held = pagesIn(self)
        if (other.isEmpty) {
            // Empty hand: take the top page into it.
            val top = held.lastOrNull() ?: return false
            carriedItem.set(top)
            setPages(self, held.dropLast(1))
            play(player, false)
            return true
        }
        if (!isPage(other)) return false
        setPages(self, held + other.copy())
        other.setCount(0)
        play(player, true)
        return true
    }

    /** The bundle preview grid, borrowed — it shows the first few and says how many are behind them. */
    override fun getTooltipImage(itemStack: ItemStack): Optional<TooltipComponent> {
        val held = pagesIn(itemStack)
        if (held.isEmpty()) return Optional.empty()
        val shown = held.takeLast(PREVIEWED).reversed().map(ItemStackTemplate::fromNonEmptyStack)
        return Optional.of(BundleTooltip(BundleContents(shown)))
    }

    override fun appendHoverText(
        stack: ItemStack,
        context: TooltipContext,
        display: TooltipDisplay,
        builder: Consumer<Component>,
        flag: TooltipFlag,
    ) {
        val held = pagesIn(stack)
        if (held.isEmpty()) {
            builder.accept(Component.translatable("item.agesandtheart.notebook.empty"))
            return
        }
        builder.accept(Component.translatable("item.agesandtheart.notebook.count", held.sumOf { it.count }))
    }

    override fun isBarVisible(stack: ItemStack): Boolean = pagesIn(stack).isNotEmpty()

    /** There is no full, so the bar shows *something held* rather than a proportion. */
    override fun getBarWidth(stack: ItemStack): Int =
        if (pagesIn(stack).isEmpty()) 0 else FULL_BAR

    override fun getBarColor(stack: ItemStack): Int = BAR_COLOUR

    private fun play(player: Player, filing: Boolean) {
        val sound = if (filing) SoundEvents.BUNDLE_INSERT else SoundEvents.BUNDLE_REMOVE_ONE
        player.playSound(sound, SOUND_VOLUME, SOUND_PITCH)
    }

    companion object {
        private const val PREVIEWED = 12
        private const val FULL_BAR = 13
        private const val BAR_COLOUR = 0x5C93FF
        private const val SOUND_VOLUME = 0.8f
        private const val SOUND_PITCH = 0.8f

        fun isPage(stack: ItemStack): Boolean =
            stack.item === AgeContent.PAGE && stack.get(AgeContent.PAGE_WORD) != null

        /** Newest last, so "take one out" returns what you most recently put in. */
        fun pagesIn(notebook: ItemStack): List<ItemStack> =
            notebook.getOrDefault(AgeContent.NOTEBOOK_PAGES, emptyList())

        fun setPages(notebook: ItemStack, pages: List<ItemStack>) {
            if (pages.isEmpty()) notebook.remove(AgeContent.NOTEBOOK_PAGES)
            else notebook.set(AgeContent.NOTEBOOK_PAGES, pages.map { it.copy() })
        }
    }
}
