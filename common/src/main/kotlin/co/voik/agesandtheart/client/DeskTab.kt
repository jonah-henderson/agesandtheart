package co.voik.agesandtheart.client

import co.voik.agesandtheart.content.AgeContent
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/** The four things a writer's desk is for. */
enum class DeskTab(val key: String, val icon: () -> ItemStack) {
    ARCHIVE("archive", { ItemStack(AgeContent.PAGE) }),
    WRITE_PAGE("write_page", { ItemStack(Items.PAPER) }),
    WRITE_BOOK("write_book", { ItemStack(AgeContent.DESCRIPTIVE_BOOK) }),
    SUPPLIES("supplies", { ItemStack(Items.CHEST) }),
    ;

    /**
     * The tabs that show the player their own inventory.
     *
     * Supplies only, at this panel height. A page can still be laid out from hand — the carried stack
     * survives a tab switch — but the rows themselves do not fit beside a work surface.
     */
    val showsInventory: Boolean get() = this == SUPPLIES

    val title: Component get() = Component.translatable("container.agesandtheart.writers_desk.$key")
}
