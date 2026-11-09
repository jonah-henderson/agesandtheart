package co.voik.agesandtheart.book

import co.voik.agesandtheart.content.AgeComponents
import co.voik.agesandtheart.desk.DeskSlots
import co.voik.agesandtheart.location
import io.netty.buffer.ByteBuf
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.ItemTags
import net.minecraft.util.StringUtil
import net.minecraft.world.Container
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.BannerItem
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * The bookbinder's screen, a loom's: the book, and beside it the banner it wears as its cover — put one in to cover
 * the book, take it out to uncover it. Once covered, the three [CoverLayout]s are buttons; and the book is named as
 * an anvil names things, for nothing. The name is inked as a sign's writing is — a dye colours it, a glow ink sac
 * makes it glow and an ink sac stops it, and a gold nugget gilds it again: the book as it would be shows in the
 * output, and taking it from there spends the ink and takes the book out of the press.
 */
class BookbinderMenu(
    containerId: Int,
    playerInventory: Inventory,
    private val binder: Container,
) : AbstractContainerMenu(Bookbinder.MENU, containerId) {

    /** The client's, which vanilla's slot syncing fills in. */
    constructor(containerId: Int, playerInventory: Inventory) :
        this(containerId, playerInventory, SimpleContainer(BookbinderBlockEntity.SLOT_COUNT))

    val book: ItemStack get() = binder.getItem(BookbinderBlockEntity.BOOK)

    /** The ink waiting for the name, the menu's own and handed back as it shuts, as a loom's inputs are. */
    private val inks = object : SimpleContainer(1) {
        override fun setChanged() {
            super.setChanged()
            slotsChanged(this)
        }
    }

    init {
        addSlot(object : Slot(binder, BookbinderBlockEntity.BOOK, BookbinderSlots.BOOK_X, BookbinderSlots.SLOTS_Y) {
            override fun mayPlace(stack: ItemStack): Boolean = LecternBooks.isOurs(stack)

            override fun getMaxStackSize(): Int = 1

            override fun getNoItemIcon(): Identifier = BOOK_HINT
        })
        addSlot(CoverSlot())
        addSlot(object : Slot(inks, 0, BookbinderSlots.INK_X, BookbinderSlots.INK_Y) {
            override fun mayPlace(stack: ItemStack): Boolean = TitleInk.of(stack) != null

            override fun getNoItemIcon(): Identifier = DYE_HINT
        })
        addSlot(InkedSlot())
        addStandardInventorySlots(playerInventory, DeskSlots.INVENTORY_X, BookbinderSlots.INVENTORY_Y)
    }

    /** The book as the ink waiting would leave its name, or nothing where there is no book, no ink, or no change. */
    private fun inkedBook(): ItemStack {
        if (book.isEmpty) return ItemStack.EMPTY
        val ink = TitleInk.of(inks.getItem(0)) ?: return ItemStack.EMPTY
        return ink.on(book) ?: ItemStack.EMPTY
    }

    override fun removed(player: Player) {
        super.removed(player)
        clearContainer(player, inks)
    }

    /** A layout button: lays the book's cover out as [CoverLayout.entries] at [id] says. */
    override fun clickMenuButton(player: Player, id: Int): Boolean {
        val layout = CoverLayout.entries.getOrNull(id) ?: return false
        if (book.isEmpty || !BookBanner.isCovered(book)) return false
        book.set(AgeComponents.COVER_LAYOUT, layout)
        binder.setChanged()
        return true
    }

    /** Names the book [name], or takes its name off where [name] is blank, as an anvil does. */
    fun rename(name: String) {
        val filtered = StringUtil.filterText(name)
        if (book.isEmpty || filtered.length > MAX_NAME_LENGTH) return
        if (StringUtil.isBlank(filtered)) book.remove(DataComponents.CUSTOM_NAME) else book.set(DataComponents.CUSTOM_NAME, Component.literal(filtered))
        binder.setChanged()
    }

    /**
     * A book goes in the press, a banner on the book and ink beside it; any of them comes back out to the
     * inventory, and the inked book out of the output only whole, spending the ink as a click there would.
     */
    override fun quickMoveStack(player: Player, index: Int): ItemStack {
        val slot = slots.getOrNull(index) ?: return ItemStack.EMPTY
        if (!slot.hasItem()) return ItemStack.EMPTY
        val moved = slot.item.copy()
        val original = moved.copy()
        val isTheBinders = index < FIRST_PLAYER_SLOT
        val landed = when {
            isTheBinders -> moveItemStackTo(moved, FIRST_PLAYER_SLOT, slots.size, true)
            LecternBooks.isOurs(moved) -> moveItemStackTo(moved, BOOK_SLOT, BOOK_SLOT + 1, false)
            moved.item is BannerItem -> moveItemStackTo(moved, COVER_SLOT, COVER_SLOT + 1, false)
            TitleInk.of(moved) != null -> moveItemStackTo(moved, INK_SLOT, INK_SLOT + 1, false)
            else -> false
        }
        if (!landed) return ItemStack.EMPTY
        if (index == INKED_SLOT) {
            if (!moved.isEmpty) return ItemStack.EMPTY
            slot.onTake(player, original)
            return original
        }
        slot.setByPlayer(if (moved.isEmpty) ItemStack.EMPTY else moved)
        return original
    }

    override fun stillValid(player: Player): Boolean = binder.stillValid(player)

    /**
     * The banner the book wears, which is the book's cover and nothing kept apart from it: what the slot holds is
     * read off the book, putting a banner in covers it, and taking the banner out uncovers it.
     */
    private inner class CoverSlot : Slot(CoverOfTheBook(), 0, BookbinderSlots.COVER_X, BookbinderSlots.SLOTS_Y) {
        override fun mayPlace(stack: ItemStack): Boolean {
            val canBeCovered = !book.isEmpty && !BookBanner.isCovered(book)
            return canBeCovered && stack.item is BannerItem
        }

        override fun getMaxStackSize(): Int = 1

        override fun getNoItemIcon(): Identifier = BANNER_HINT
    }

    /** The book with its name inked, read off the book and the ink; taking it spends the ink and empties the press. */
    private inner class InkedSlot : Slot(InkedBook(), 0, BookbinderSlots.OUTPUT_X, BookbinderSlots.OUTPUT_Y) {
        override fun mayPlace(stack: ItemStack): Boolean = false

        override fun onTake(player: Player, carried: ItemStack) {
            inks.removeItem(0, 1)
            binder.setItem(BookbinderBlockEntity.BOOK, ItemStack.EMPTY)
            super.onTake(player, carried)
        }
    }

    /** The one-slot container [InkedSlot] reads, which is only ever the book as the ink would leave it. */
    private inner class InkedBook : SimpleContainer(1) {
        override fun getItem(slot: Int): ItemStack = inkedBook()

        override fun setItem(slot: Int, itemStack: ItemStack) = Unit

        override fun removeItem(slot: Int, count: Int): ItemStack = if (count > 0) inkedBook() else ItemStack.EMPTY

        override fun removeItemNoUpdate(slot: Int): ItemStack = inkedBook()

        override fun isEmpty(): Boolean = inkedBook().isEmpty

        override fun getMaxStackSize(): Int = 1
    }

    /** The one-slot container [CoverSlot] reads and writes, which is the book's cover. */
    private inner class CoverOfTheBook : SimpleContainer(1) {
        override fun getItem(slot: Int): ItemStack = if (book.isEmpty) ItemStack.EMPTY else BookBanner.wornBy(book)

        override fun setItem(slot: Int, itemStack: ItemStack) {
            if (book.isEmpty) return
            if (itemStack.isEmpty) BookBanner.takeOff(book) else BookBanner.putOn(book, itemStack)
            binder.setChanged()
        }

        override fun removeItem(slot: Int, count: Int): ItemStack {
            val worn = getItem(slot)
            if (worn.isEmpty || count <= 0) return ItemStack.EMPTY
            setItem(slot, ItemStack.EMPTY)
            return worn
        }

        override fun removeItemNoUpdate(slot: Int): ItemStack = removeItem(slot, 1)

        override fun isEmpty(): Boolean = getItem(0).isEmpty

        override fun getMaxStackSize(): Int = 1
    }

    companion object {
        const val BOOK_SLOT = 0
        const val COVER_SLOT = 1
        const val INK_SLOT = 2
        const val INKED_SLOT = 3
        private const val FIRST_PLAYER_SLOT = 4

        /** The loom's own empty-slot hints, and a book drawn as they are, which vanilla has none of. */
        private val BANNER_HINT: Identifier = Identifier.withDefaultNamespace("container/slot/banner")
        private val DYE_HINT: Identifier = Identifier.withDefaultNamespace("container/slot/dye")
        private val BOOK_HINT: Identifier = "container/slot/book".location()


        /** As long a name as an anvil gives. */
        const val MAX_NAME_LENGTH = 50
    }
}

/**
 * Where the bookbinder's slots, name and layout buttons sit — stated once for the menu and the screen. The name runs
 * across the top with its ink to the right; under it, the book and its banner, the layouts stacked, and the
 * loom-sized output, all centred on the layouts' column.
 */
object BookbinderSlots {
    const val PANEL_HEIGHT = 182
    const val INVENTORY_Y = PANEL_HEIGHT - 83
    const val INVENTORY_LABEL_Y = INVENTORY_Y - 11

    const val INK_X = 152
    const val INK_Y = 18
    const val NAME_X = 8
    const val NAME_Y = INK_Y + 2
    const val NAME_HEIGHT = 12
    const val NAME_WIDTH = INK_X - 6 - NAME_X

    const val LAYOUTS_Y = 38
    const val LAYOUT_WIDTH = 60
    const val LAYOUT_HEIGHT = 14
    const val LAYOUT_PITCH = 15
    private const val LAYOUTS_MIDDLE = LAYOUTS_Y + (2 * LAYOUT_PITCH + LAYOUT_HEIGHT) / 2

    /** The book and its banner in ordinary slots a little apart, lined up with the inventory's slots on the left. */
    private const val SLOT_GAP = 2
    const val SLOTS_Y = LAYOUTS_MIDDLE - 8
    const val BOOK_X = DeskSlots.INVENTORY_X
    const val COVER_X = BOOK_X + 18 + SLOT_GAP

    /** The loom's result slot, 26 square, its item in the middle, lined up with the ink slot on the right. */
    const val OUTPUT_SIZE = 26
    private const val OUTPUT_INSET = (OUTPUT_SIZE - 16) / 2
    const val OUTPUT_FRAME_X = INK_X + 17 - OUTPUT_SIZE
    const val OUTPUT_FRAME_Y = LAYOUTS_MIDDLE - OUTPUT_SIZE / 2
    const val OUTPUT_X = OUTPUT_FRAME_X + OUTPUT_INSET
    const val OUTPUT_Y = OUTPUT_FRAME_Y + OUTPUT_INSET

    /** Centred between the banner's slot and the output. */
    const val LAYOUTS_X = (COVER_X + 17 + OUTPUT_FRAME_X - LAYOUT_WIDTH) / 2
}

/**
 * What the name on a book's cover may be inked with, as a sign's writing is: a dye's colour, a glow ink sac's glow or
 * an ink sac taking the glow away — and, the book's own, a gold nugget gilding it again.
 */
sealed interface TitleInk {
    /** [book] with its name so inked, or null where it would make no change. */
    fun on(book: ItemStack): ItemStack?

    data class Dye(val colour: DyeColor) : TitleInk {
        override fun on(book: ItemStack): ItemStack? =
            if (book.get(AgeComponents.TITLE_COLOUR) == colour) null else book.copy().also { it.set(AgeComponents.TITLE_COLOUR, colour) }
    }

    data object Gilt : TitleInk {
        override fun on(book: ItemStack): ItemStack? =
            if (!book.has(AgeComponents.TITLE_COLOUR)) null else book.copy().also { it.remove(AgeComponents.TITLE_COLOUR) }
    }

    data object Glow : TitleInk {
        override fun on(book: ItemStack): ItemStack? =
            if (book.getOrDefault(AgeComponents.TITLE_GLOWS, false)) null else book.copy().also { it.set(AgeComponents.TITLE_GLOWS, true) }
    }

    data object Plain : TitleInk {
        override fun on(book: ItemStack): ItemStack? =
            if (!book.has(AgeComponents.TITLE_GLOWS)) null else book.copy().also { it.remove(AgeComponents.TITLE_GLOWS) }
    }

    companion object {
        /** What [stack] inks a name with, or null where it is no ink: a dye as a loom takes one, or one of the three. */
        fun of(stack: ItemStack): TitleInk? {
            val dye = stack.get(DataComponents.DYE)
            return when {
                dye != null && stack.`is`(ItemTags.LOOM_DYES) -> Dye(dye)
                stack.`is`(Items.GOLD_NUGGET) -> Gilt
                stack.`is`(Items.GLOW_INK_SAC) -> Glow
                stack.`is`(Items.INK_SAC) -> Plain
                else -> null
            }
        }
    }
}

/** A name for the book in a bookbinder, sent as it is typed, as an anvil's is. */
data class BookbinderRenamePayload(val name: String) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<BookbinderRenamePayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<BookbinderRenamePayload> = CustomPacketPayload.Type("bookbinder_rename".location())

        val STREAM_CODEC: StreamCodec<ByteBuf, BookbinderRenamePayload> =
            ByteBufCodecs.stringUtf8(BookbinderMenu.MAX_NAME_LENGTH).map(::BookbinderRenamePayload, BookbinderRenamePayload::name)

        fun handle(player: ServerPlayer, payload: BookbinderRenamePayload) {
            (player.containerMenu as? BookbinderMenu)?.rename(payload.name)
        }
    }
}
