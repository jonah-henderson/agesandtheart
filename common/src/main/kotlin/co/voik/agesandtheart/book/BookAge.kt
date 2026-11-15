package co.voik.agesandtheart.book

import co.voik.agesandtheart.age.AgeRecipe
import co.voik.agesandtheart.age.AgeSavedData
import co.voik.agesandtheart.generation.Ages
import co.voik.agesandtheart.content.AgeComponents
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack

/**
 * The Age a bound Descriptive Book leads to, brought into being if it is not yet.
 *
 * **Shared by linking and by the panel, so the two can never mean different worlds.** A book's Age is
 * allocated once and stamped onto the stack; whichever of the two asks first is the one that mints it, and
 * the other finds it already there. Kept apart from both callers because a book that previewed one Age and
 * then linked you to another would be the worst bug this feature could have, and one file is how that is
 * made impossible rather than merely unlikely.
 *
 * **A bound book has an Age even before anybody visits it** (Jonah, 2026-09-04). Binding is the commitment
 * — the ink is spent, the words are fixed — so the world is already decided and there is nothing left to
 * find out by going. That is why a found book shows its Age at once: it is bound, so it is a place, and
 * §7.5's commit-and-find-out is satisfied by the binding rather than by the journey.
 */
object BookAge {

    /**
     * Opens (or makes) the Age [stack] describes, stamping its id and the time it was written onto the book
     * the first time.
     *
     * Null where the level would not open, which is the same answer linking gets and is reported the same
     * way by each caller.
     */
    fun of(server: MinecraftServer, stack: ItemStack): ServerLevel? {
        val id = idFor(server, stack)
        val level = Ages.ensure(server, id, DescriptiveBookRecipe.of(stack, server, id))
        stampWrittenAt(server, stack, id)
        return level
    }

    /**
     * Gives a book bound without a time — found, or bound before books carried one — its Age's, so an Age
     * rebuilt from the book keeps its clock. A preview's time is never copied, since it is no time at all.
     */
    private fun stampWrittenAt(server: MinecraftServer, stack: ItemStack, id: Identifier) {
        if (stack.has(AgeComponents.BOOK_WRITTEN_AT)) return
        val writtenAt = AgeSavedData.get(server).recipe(id)?.writtenAt ?: return
        if (writtenAt == AgeRecipe.NOT_YET_WRITTEN) return
        stack.set(AgeComponents.BOOK_WRITTEN_AT, writtenAt)
    }

    /**
     * The book's Age id, allocated and written onto the stack if it had none.
     *
     * **The stamp is what makes this idempotent.** Without it a book would mint a fresh id every time it
     * was opened, so a panel and the link that followed would describe two different dimensions holding
     * identical terrain — and the one you looked at would be orphaned the moment you went.
     */
    fun idFor(server: MinecraftServer, stack: ItemStack): Identifier =
        stack.get(AgeComponents.AGE_ID)
            ?: Ages.allocateId(server, stack.get(AgeComponents.BOOK_TITLE).orEmpty())
                .also { stack.set(AgeComponents.AGE_ID, it) }
}
