package co.voik.agesandtheart.book

import net.minecraft.world.level.block.LecternBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BooleanProperty

/**
 * Whether the book on a lectern lies open (design §7.8.2) — a property vanilla's lectern does not have.
 *
 * Carried by the same two seams as deep waterlogging: [belongsOn] is asked by `StateDefinitionBuilderMixin`
 * as a block builds its state definition, and [closed] by `BlockDefaultStateMixin`, because a
 * `BooleanProperty` orders `true` first and every lectern would otherwise be placed open.
 *
 * Every lectern carries it, and it means something only on one holding a book of ours ([LecternBooks]).
 */
object LecternOpening {

    @JvmField
    val BOOK_OPEN: BooleanProperty = BooleanProperty.create("book_open")

    /** Whether a state definition being built belongs to a lectern. */
    @JvmStatic
    fun belongsOn(owner: Any?): Boolean = owner is LecternBlock

    /** [state] with its book shut. */
    @JvmStatic
    fun closed(state: BlockState): BlockState =
        if (state.hasProperty(BOOK_OPEN)) state.setValue(BOOK_OPEN, false) else state

    fun isOpen(state: BlockState): Boolean = state.hasProperty(BOOK_OPEN) && state.getValue(BOOK_OPEN)
}
