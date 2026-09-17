package co.voik.agesandtheart.desk

import co.voik.agesandtheart.content.AgeContent
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * The three things the **centre** of a writer's desk is for.
 *
 * It was four. Supplies left for the wings, which are their own blocks with their own screens, and page
 * writing left for the archive, which now shows every word you know and offers to write one — so a tab
 * whose whole job was picking a word and pressing a button had nothing left to do (the desk rework, 2026-08-05).
 */
enum class DeskTab(val key: String, val icon: () -> ItemStack) {
    ARCHIVE("archive", { ItemStack(AgeContent.PAGE) }),
    WRITE_BOOK("write_book", { ItemStack(Items.WRITABLE_BOOK) }),
    BIND("bind", { ItemStack(AgeContent.DESCRIPTIVE_BOOK) }),
    ;

    /**
     * The tabs that show the player their own inventory.
     *
     * Only the archive. The work surface needs its room and the bind screen wants its readout — a page can
     * still be laid out from hand on either, since a carried stack survives a tab switch.
     */
    val showsInventory: Boolean get() = this == ARCHIVE

    val title: Component get() = Component.translatable("container.agesandtheart.writers_desk.$key")
}
