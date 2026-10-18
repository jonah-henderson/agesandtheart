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

    /**
     * Clicking with the notebook carried, onto a slot — **a bundle's controls**: a left click on a page files it,
     * and a right click on an empty slot puts the most recently filed page back out.
     */
    override fun overrideStackedOnOther(
        self: ItemStack,
        slot: Slot,
        clickAction: ClickAction,
        player: Player,
    ): Boolean {
        val held = pagesIn(self)
        val target = slot.item
        val filesIt = clickAction == ClickAction.PRIMARY && !target.isEmpty
        val takesOneOut = clickAction == ClickAction.SECONDARY && target.isEmpty
        return when {
            filesIt -> {
                if (!isPage(target)) return false
                setPages(self, held + slot.safeTake(target.count, target.count, player))
                play(player, true)
                true
            }
            takesOneOut -> {
                val top = held.lastOrNull() ?: return false
                if (!slot.mayPlace(top)) return false
                slot.setByPlayer(top)
                setPages(self, held.dropLast(1))
                play(player, false)
                true
            }
            else -> false
        }
    }

    /**
     * Clicking onto the notebook where it sits in a slot — **a bundle's controls**: a left click with a page files
     * it, and a right click with an empty hand takes the top page out. A left click with nothing picks the
     * notebook up, as it does a bundle.
     */
    override fun overrideOtherStackedOnMe(
        self: ItemStack,
        other: ItemStack,
        slot: Slot,
        clickAction: ClickAction,
        player: Player,
        carriedItem: SlotAccess,
    ): Boolean {
        if (!slot.allowModification(player)) return false
        val held = pagesIn(self)
        val filesIt = clickAction == ClickAction.PRIMARY && !other.isEmpty
        val takesOneOut = clickAction == ClickAction.SECONDARY && other.isEmpty
        return when {
            filesIt -> {
                if (!isPage(other)) return false
                setPages(self, held + other.copy())
                other.setCount(0)
                play(player, true)
                true
            }
            takesOneOut -> {
                val top = held.lastOrNull() ?: return false
                carriedItem.set(top)
                setPages(self, held.dropLast(1))
                play(player, false)
                true
            }
            else -> false
        }
    }

    /** The bundle preview grid, borrowed — it shows the first few and says how many are behind them. */
    override fun getTooltipImage(itemStack: ItemStack): Optional<TooltipComponent> {
        val held = pagesIn(itemStack)
        if (held.isEmpty()) return Optional.empty()
        val shown = held.takeLast(PREVIEWED).reversed().map(ItemStackTemplate::fromNonEmptyStack)
        return Optional.of(BundleTooltip(BundleContents(shown)))
    }

    @Suppress("OVERRIDE_DEPRECATION")
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

    // No fill bar. A bar states a proportion and this has no full, so it could only ever have said
    // "not empty" — which the tooltip already says, in words, with the count.

    private fun play(player: Player, filing: Boolean) {
        val sound = if (filing) SoundEvents.BUNDLE_INSERT else SoundEvents.BUNDLE_REMOVE_ONE
        player.playSound(sound, SOUND_VOLUME, SOUND_PITCH)
    }

    companion object {
        private const val PREVIEWED = 12
        private const val SOUND_VOLUME = 0.8f
        private const val SOUND_PITCH = 0.8f

        fun isPage(stack: ItemStack): Boolean =
            stack.item === AgeContent.PAGE && stack.get(AgeComponents.PAGE_WORD) != null

        /** Newest last, so "take one out" returns what you most recently put in. */
        fun pagesIn(notebook: ItemStack): List<ItemStack> =
            notebook.getOrDefault(AgeComponents.NOTEBOOK_PAGES, emptyList())

        fun setPages(notebook: ItemStack, pages: List<ItemStack>) {
            if (pages.isEmpty()) notebook.remove(AgeComponents.NOTEBOOK_PAGES)
            else notebook.set(AgeComponents.NOTEBOOK_PAGES, pages.map { it.copy() })
        }
    }
}
