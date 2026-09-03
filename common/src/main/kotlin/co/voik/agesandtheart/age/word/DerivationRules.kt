package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.age.aspect.Aspect
import net.minecraft.core.HolderLookup

/**
 * The derivation read back **one rule at a time**, and what each one alone puts into a set.
 *
 * A rule's own line already says the whole rule; what a file cannot say is its consequence, and the fault
 * the tag pass keeps finding is a rule that was *nearly* the fact it stood for — visible only in the list
 * of what it caught (`notes/the-tag-layer.md` §4).
 *
 * **In `main` rather than in the tool** because two things ask: the word forge, offline, where every rule
 * keyed on a registry tag matches nothing; and `/age rules`, on a server, where they all do. One
 * implementation, so what a snapshot remembers and what the tool computes cannot be different answers.
 */
object DerivationRules {

    /** One line of one `art/derivation/<aspect>.json`. */
    data class Rule(
        val aspect: Aspect,
        val key: String,
        val fills: Map<String, Double>,
        /** Whether it keys on a registry tag rather than on something the member states about itself. */
        val byTag: Boolean,
    ) {
        /**
         * What names this rule across a restart and across the wire.
         *
         * The aspect and the key together are unique — a file cannot say the same thing twice — and the
         * middle word keeps a tag rule apart from a fact rule of the same name.
         */
        val id: String get() = "${aspect.page}/${if (byTag) TAG else KIND}/$key"
    }

    /** Every rule in [derivation], in the order the files state them. */
    fun rulesIn(derivation: Map<Aspect, Derivation>): List<Rule> =
        derivation.entries.sortedBy { it.key.ordinal }.flatMap { (aspect, rules) ->
            rules.byTag.map { (key, fills) -> Rule(aspect, key, fills, byTag = true) } +
                rules.byKind.map { (key, fills) -> Rule(aspect, key, fills, byTag = false) }
        }

    /**
     * Exactly what [rule] puts into a set, **by running the derivation with nothing else in it.**
     *
     * The real code rather than a reimplementation of it, which matters more here than anywhere: two
     * rules filling one set at one weight cannot be told apart by reading the merged answer, so a lens
     * that guessed would be wrong precisely where rules overlap — which is where a reader is looking.
     */
    fun catches(registries: HolderLookup.Provider, rule: Rule): List<String> {
        val alone = if (rule.byTag) {
            Derivation(byTag = mapOf(rule.key to rule.fills))
        } else {
            Derivation(byKind = mapOf(rule.key to rule.fills))
        }
        return DerivedTags.read(registries, mapOf(rule.aspect to alone), mutableListOf())
            .getOrElse(rule.aspect) { emptyMap() }
            .keys.sorted()
    }

    private const val TAG = "tag"
    private const val KIND = "kind"
}
