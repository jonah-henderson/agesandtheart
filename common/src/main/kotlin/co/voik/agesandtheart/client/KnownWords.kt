package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.word.LearnedWordsPayload
import co.voik.agesandtheart.age.word.LexiconPayload
import co.voik.agesandtheart.age.word.Script
import co.voik.agesandtheart.age.word.WordNames
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.toasts.SystemToast
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

    private val WORD_LEARNED = SystemToast.SystemToastId()

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

    private fun scriptStyle(): Style {
        val font = known.font ?: return Style.EMPTY
        val asset = known.fontAsset ?: return Style.EMPTY
        if (Minecraft.getInstance().resourceManager.getResource(asset).isEmpty) return Style.EMPTY
        return Style.EMPTY.withFont(FontDescription.Resource(font))
    }

    /** `add` rather than `addOrUpdate`: gathering a chest teaches several words, and each is news. */
    private fun announce(word: Identifier) {
        val minecraft = Minecraft.getInstance()
        SystemToast.add(
            minecraft.toastManager,
            WORD_LEARNED,
            Component.translatable("toast.agesandtheart.word_learned"),
            WordNames.readable(word),
        )
    }
}
