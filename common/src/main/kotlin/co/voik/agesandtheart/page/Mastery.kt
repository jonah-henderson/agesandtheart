package co.voik.agesandtheart.page

import co.voik.agesandtheart.advancement.LearnedBy
import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.word.Withheld
import co.voik.agesandtheart.age.word.Word
import co.voik.agesandtheart.age.word.learnedWords
import co.voik.agesandtheart.location
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer

/** The three summit things, and the word each is named by (design §7.1.2). */
enum class MasterySubject(val key: String, val referent: Identifier) {
    NARA("nara", "compounded_stone".location()),
    SCARAB("scarab", "scarab".location()),
    YEMA("yema", "paper_tree_log".location()),
    ;

    companion object {
        fun byKey(key: String): MasterySubject? = entries.firstOrNull { it.key == key }
    }
}

/**
 * The third learning channel's grant: the word for a thing a player has shown they can reproduce
 * (design §8.3). **[Withheld] is not asked**, because it is the fence that waits for exactly this.
 */
object Mastery {

    fun knows(player: ServerPlayer, subject: MasterySubject): Boolean {
        val word = wordFor(player, subject) ?: return false
        return player.learnedWords.knows(word.id)
    }

    /** Teaches [subject]'s word, answering it where it was learned now and null where it was not. */
    fun grant(player: ServerPlayer, subject: MasterySubject): Word? {
        val word = wordFor(player, subject) ?: return null
        if (player.learnedWords.knows(word.id)) return null
        return word.takeIf { PageLearning.teach(player, listOf(word.id), LearnedBy.MASTERY).isNotEmpty() }
    }

    private fun wordFor(player: ServerPlayer, subject: MasterySubject): Word? =
        Vocabulary.of(player.level().server).word(subject.referent.toString())
}
