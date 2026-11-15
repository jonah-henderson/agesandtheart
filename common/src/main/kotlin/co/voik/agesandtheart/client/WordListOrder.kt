package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.word.WordNames
import co.voik.agesandtheart.client.ui.ListGroup
import co.voik.agesandtheart.client.ui.OrderButton
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier

/** A word list's [OrderButton] and the ordering behind it, shared by the archive and the desk. */
object WordListOrder {

    fun button(size: Int, current: WordOrder, offered: () -> List<WordOrder>, chosen: (WordOrder) -> Unit) =
        OrderButton(size, current, offered, WordOrder::glyph, { Component.translatable("$KEY.order.${it.key}") }, chosen)

    /** [rows] under [order], with what this client was told of each word, searched for [searchedFor]. */
    fun <T : OrderedWord> grouped(rows: List<T>, order: WordOrder, searchedFor: String): List<ListGroup<T>> = WordOrdering.grouped(
        rows,
        order,
        searchedFor,
        rarityOf = KnownWords::rarityOf,
        appliesTo = KnownWords::appliesTo,
        takenAs = KnownWords::takenAs,
        names = WordOrdering.Names(
            rarity = { Component.translatable("$KEY.rarity.${it.key}").string },
            of = { WordNames.readable(it).string },
            parts = Component.translatable("$KEY.parts").string,
            baseWorlds = Component.translatable("$KEY.base_worlds").string,
            grammar = Component.translatable("$KEY.grammar").string,
            concept = ::conceptName,
            namesAPart = KnownWords::namesAPart,
            isGrammar = KnownWords::isGrammar,
            membersOf = { part -> Component.translatableWithFallback("$KEY.members.${part.path}", kinds()).string },
        ),
    )

    private fun kinds() = Component.translatable("$KEY.members").string

    /** What words taken as [concept] are folded under: its own name in the language file, or its id read as words. */
    private fun conceptName(concept: Identifier): String {
        val readAsWords = concept.path.replace('_', ' ').replaceFirstChar(Char::uppercaseChar)
        return Component.translatableWithFallback("concept.${concept.namespace}.${concept.path}", readAsWords).string
    }

    /**
     * Whether [groups] start folded: ordered by what words apply to, unless a search found few enough rows to open
     * out — a short search finds thousands, a word under every heading it applies to, and building them all is slow.
     */
    fun <T : Any> startsCollapsed(order: WordOrder, searchedFor: String, groups: List<ListGroup<T>>): Boolean {
        if (order != WordOrder.APPLIES_TO) return false
        val foundFew = searchedFor.isNotBlank() && groups.sumOf { it.size } <= MOST_ROWS_OPENED
        return !foundFew
    }

    /** How many rows a search may find and still open every group it found them in. */
    private const val MOST_ROWS_OPENED = 150

    /** How long the search waits for typing to pause before it rebuilds the list, in ticks. */
    const val SEARCH_PAUSE_TICKS = 3

    /** [current] where [offered] still holds it, and the first otherwise — a guide carried away takes its order with it. */
    fun stillOffered(current: WordOrder, offered: List<WordOrder>): WordOrder = if (current in offered) current else offered.first()

    private const val KEY = "container.agesandtheart.words"}
