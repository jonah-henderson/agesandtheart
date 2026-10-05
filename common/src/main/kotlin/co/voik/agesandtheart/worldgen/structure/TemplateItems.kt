package co.voik.agesandtheart.worldgen.structure

import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.Tag
import net.minecraft.world.item.ItemStack

/**
 * The items a template saved inside its blocks: a container's `Items` (chests, shelves, chiseled
 * bookshelves) and a lectern's `Book`, rewritten one stack at a time as the template is placed.
 */
object TemplateItems {

    /**
     * [saved] with each stack passed through [rewrite], or null where no stack changed. [rewrite] answers
     * the stack to keep in its place, the same instance when it leaves it alone.
     */
    fun rewritten(saved: CompoundTag, registries: HolderLookup.Provider, rewrite: (ItemStack) -> ItemStack): CompoundTag? {
        val ops = registries.createSerializationContext(NbtOps.INSTANCE)
        fun rewriteOne(tag: Tag): Tag? {
            val stack = ItemStack.CODEC.parse(ops, tag).result().orElse(null) ?: return null
            val replaced = rewrite(stack)
            if (replaced === stack) return null
            return ItemStack.CODEC.encodeStart(ops, replaced).result().orElse(null)
        }

        val copy = saved.copy()
        var changed = false
        copy.getList(ITEMS).orElse(null)?.let { items ->
            val replacement = ListTag()
            for (entry in items) {
                val slot = (entry as? CompoundTag)?.getByte(SLOT)?.orElse(null)
                val encoded = rewriteOne(entry) as? CompoundTag
                if (encoded == null) {
                    replacement.add(entry)
                } else {
                    slot?.let { encoded.putByte(SLOT, it) }
                    replacement.add(encoded)
                    changed = true
                }
            }
            copy.put(ITEMS, replacement)
        }
        copy.get(BOOK)?.let { book ->
            rewriteOne(book)?.let { copy.put(BOOK, it); changed = true }
        }
        return copy.takeIf { changed }
    }

    /** Whether [saved] holds any item at all, the cheap test before decoding one. */
    fun holdsItems(saved: CompoundTag): Boolean = saved.contains(ITEMS) || saved.contains(BOOK)

    private const val ITEMS = "Items"
    private const val BOOK = "Book"
    private const val SLOT = "Slot"
}
