package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.word.WordNames
import co.voik.agesandtheart.client.ui.ListGroup
import co.voik.agesandtheart.client.ui.OrderButton
import net.minecraft.network.chat.Component

/** A word list's [OrderButton] and the ordering behind it, shared by the archive and the desk. */
object WordListOrder {

    fun button(size: Int, current: WordOrder, offered: () -> List<WordOrder>, chosen: (WordOrder) -> Unit) =
        OrderButton(size, current, offered, WordOrder::glyph, { Component.translatable("$KEY.order.${it.key}") }, chosen)

    /** [rows] under [order], with what this client was told of each word. */
    fun <T : OrderedWord> grouped(rows: List<T>, order: WordOrder): List<ListGroup<T>> = WordOrdering.grouped(
        rows,
        order,
        rarityOf = KnownWords::rarityOf,
        rarityName = { Component.translatable("$KEY.rarity.${it.key}").string },
        appliesTo = KnownWords::appliesTo,
        nameOf = { WordNames.readable(it).string },
        naming = Component.translatable("$KEY.naming").string,
    )

    /** [current] where [offered] still holds it, and the first otherwise — a guide carried away takes its order with it. */
    fun stillOffered(current: WordOrder, offered: List<WordOrder>): WordOrder = if (current in offered) current else offered.first()

    private const val KEY = "container.agesandtheart.words"}
