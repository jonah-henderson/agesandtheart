package co.voik.agesandtheart.advancement

import co.voik.agesandtheart.location
import net.minecraft.advancements.triggers.CriterionTrigger
import net.minecraft.advancements.triggers.PlayerTrigger
import net.minecraft.resources.Identifier

/**
 * The advancement triggers of our own (plan, "The advancement tree"). Each loader registers [triggers]
 * under `Registries.TRIGGER_TYPE`; everything else the tree asks is vanilla's.
 */
object AgeTriggers {
    val LEARNED_WORD = LearnedWordTrigger()
    val LINKED = LinkedTrigger()
    val ENTERED_AGE = EnteredAgeTrigger()
    val WROTE_AGE = WroteAgeTrigger()
    val DESK_FURNISHED = DeskFurnishedTrigger()

    /** A book set into a receptacle opened a linking portal. */
    val OPENED_LINKING_PORTAL = PlayerTrigger()

    /** A fusion-compounder woken with contained plasma. */
    val REPAIRED_COMPOUNDER = PlayerTrigger()

    /** A block broken with a nara pickaxe. */
    val MINED_WITH_COMPOUNDED_STONE = PlayerTrigger()

    /** A linking or descriptive book a player threw reached a star fissure. */
    val GAVE_A_BOOK_TO_A_FISSURE = PlayerTrigger()

    val triggers: List<Pair<Identifier, CriterionTrigger<*>>> = listOf(
        "learned_word".location() to LEARNED_WORD,
        "linked".location() to LINKED,
        "entered_age".location() to ENTERED_AGE,
        "wrote_age".location() to WROTE_AGE,
        "desk_furnished".location() to DESK_FURNISHED,
        "opened_linking_portal".location() to OPENED_LINKING_PORTAL,
        "repaired_compounder".location() to REPAIRED_COMPOUNDER,
        "mined_with_compounded_stone".location() to MINED_WITH_COMPOUNDED_STONE,
        "gave_a_book_to_a_fissure".location() to GAVE_A_BOOK_TO_A_FISSURE,
    )
}
