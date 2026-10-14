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

    /** A player came out of a star fissure's fall into the overworld. */
    val FELL_HOME = PlayerTrigger()

    /** A player in temperstone climbers went up a temperstone wall. */
    val CLIMBED_TEMPERSTONE = PlayerTrigger()

    /** A mob died to an electrified copper assembly a player was near. */
    val KILLED_WITH_LIVE_COPPER = PlayerTrigger()

    /** A player was pulled by an iron assembly. */
    val PULLED_BY_LIVE_IRON = PlayerTrigger()

    /** A player was pushed by a gold assembly. */
    val PUSHED_BY_LIVE_GOLD = PlayerTrigger()

    /** A player in a chainmail chestplate stood in an assembly's field and was not moved. */
    val CAGED_FROM_A_FIELD = PlayerTrigger()

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
        "fell_home".location() to FELL_HOME,
        "climbed_temperstone".location() to CLIMBED_TEMPERSTONE,
        "killed_with_live_copper".location() to KILLED_WITH_LIVE_COPPER,
        "pulled_by_live_iron".location() to PULLED_BY_LIVE_IRON,
        "pushed_by_live_gold".location() to PUSHED_BY_LIVE_GOLD,
        "caged_from_a_field".location() to CAGED_FROM_A_FIELD,
    )
}
