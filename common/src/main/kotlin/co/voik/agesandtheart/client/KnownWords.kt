package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.word.LearnedWordsPayload
import co.voik.agesandtheart.age.word.LexiconPayload
import co.voik.agesandtheart.age.word.Script
import co.voik.agesandtheart.age.word.WordNames
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FontDescription
import net.minecraft.network.chat.Style
import net.minecraft.resources.Identifier

/**
 * What this client has been told about words: the script, and which ones the player knows.
 *
 * Server-owned, like [KnownSkies][co.voik.agesandtheart.sky.KnownSkies] — a pack may ship its own
 * script, so none of this can be read locally.
 */
object KnownWords {
    private var known: Script = Script.NONE
    private val learned = LinkedHashSet<Identifier>()

    val script: Script get() = known

    val words: Set<Identifier> get() = learned

    fun knows(word: Identifier): Boolean = word in learned

    fun remember(payload: LexiconPayload) {
        known = payload.script
    }

    fun remember(payload: LearnedWordsPayload) {
        if (payload.replacing) {
            learned.clear()
            learned += payload.words
        } else {
            payload.words.filter(learned::add).forEach(::announce)
        }
    }

    /** These mean nothing on the next server: a pack there may spell the same word differently. */
    fun forgetAll() {
        known = Script.NONE
        learned.clear()
    }

    /**
     * [word] as the script writes it.
     *
     * Falls back to the ordinary typeface when the script names one this client does not have, which
     * leaves a readable romanisation rather than a row of missing glyphs — and is what makes the
     * typeface a file anyone can delete.
     */
    fun scriptText(word: Identifier): Component =
        Component.literal(known.spell(word.path)).setStyle(scriptStyle())

    /** A whole sentence as the script writes it, for setting as running text. */
    fun scriptText(words: List<Identifier>): Component =
        Component.literal(words.joinToString(" ") { known.spell(it.path) }).setStyle(scriptStyle())

    /**
     * A line of prose as the script writes it — a reading, particles and all.
     *
     * Spelled here rather than where the line was composed, so a pack that retunes its transliteration
     * retunes every book already written rather than only the ones bound afterwards.
     */
    fun scriptLine(line: String): Component =
        Component.literal(known.spellEachWord(line)).setStyle(scriptStyle())

    /**
     * [word] as the script writes it, one part per line.
     *
     * Somewhere to break matters because a derived word is a block id — `polished_deepslate` is three times
     * the length of `sea`, and scaling the long ones to fit a page is what makes them unreadable.
     */
    fun scriptLines(word: Identifier): List<Component> = scriptParts(word.path)

    /**
     * [text] as the script writes it, **broken into its parts**.
     *
     * Spelled whole and *then* split, never the other way about: an authored spelling is keyed on a whole
     * word, so `packed_ice` has to be looked up before anything is allowed to cut it in two. The
     * transliteration rules turn `_` into a space, so the parts are there to be found afterwards.
     */
    fun scriptParts(text: String): List<Component> {
        val style = scriptStyle()
        return known.spellEachWord(text)
            .split(PART_SEPARATOR)
            .filter { it.isNotBlank() }
            .map { Component.literal(it).setStyle(style) }
    }

    private val PART_SEPARATOR = Regex("[\\s_]+")

    private fun scriptStyle(): Style {
        val font = known.font ?: return Style.EMPTY
        val asset = known.fontAsset ?: return Style.EMPTY
        if (Minecraft.getInstance().resourceManager.getResource(asset).isEmpty) return Style.EMPTY
        return Style.EMPTY.withFont(FontDescription.Resource(font))
    }

    /** One toast that cycles, not one per word — emptying a notebook can teach a dozen at once. */
    private fun announce(word: Identifier) {
        WordToast.show(Minecraft.getInstance().toastManager, word)
    }
}
