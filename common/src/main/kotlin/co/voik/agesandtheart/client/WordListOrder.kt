package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.word.WordNames
import co.voik.agesandtheart.client.ui.ListGroup
import co.voik.agesandtheart.client.ui.OrderButton
import net.minecraft.network.chat.Component

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
        isAMaterial = KnownWords::isAMaterial,
        names = WordOrdering.Names(
            rarity = { Component.translatable("$KEY.rarity.${it.key}").string },
            of = { WordNames.readable(it).string },
            parts = Component.translatable("$KEY.parts").string,
            unplaced = Component.translatable("$KEY.whole_worlds").string,
            materials = Component.translatable("$KEY.materials").string,
            namesMembers = KnownWords::namesMembers,
            namesAPart = KnownWords::namesAPart,
            membersOf = { part -> Component.translatableWithFallback("$KEY.members.${part.path}", kinds()).string },
        ),
    )

    private fun kinds() = Component.translatable("$KEY.members").string

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
