package co.voik.agesandtheart.preview.authoring

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.age.word.DerivationRules.Rule
import co.voik.agesandtheart.age.word.DerivationRules as Rules

/**
 * The rules that fill our sets from what the game already states, and **what each one actually catches.**
 *
 * The rules themselves and the running of one live in `main` ([Rules]), because a server answers the same
 * question through `/age rules` and two implementations could disagree precisely where a reader is
 * looking. What is here is the two things only the tool knows: which corpus, and what a snapshot
 * remembers of a server that could answer where this cannot.
 */
object DerivationRules {

    fun of(corpus: Corpus): List<Rule> = Rules.rulesIn(corpus.vocabulary.derivation)

    /**
     * What [rule] catches — **run here where that is knowable, remembered from a server where it is not.**
     *
     * A rule keyed on a registry tag matches nothing offline, tags being bound by a running game; 84 of
     * the 142 are. Those are what `--refresh` asks about and what a snapshot holds, so the answer is the
     * server's own rather than a shrug.
     */
    fun catches(rule: Rule, corpus: Corpus): Caught {
        val here = Rules.catches(MinecraftRegistries.worldgen, rule)
        if (here.isNotEmpty()) return Caught(here, fromAServer = false)
        val remembered = corpus.snapshot?.caught?.get(rule.id) ?: return Caught(emptyList(), fromAServer = false)
        return Caught(remembered, fromAServer = true)
    }

    /** What a rule caught, and whether this corpus worked it out or a server did. */
    data class Caught(val members: List<String>, val fromAServer: Boolean)
}
