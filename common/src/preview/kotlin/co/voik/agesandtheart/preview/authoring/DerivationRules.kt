package co.voik.agesandtheart.preview.authoring

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.word.Derivation
import co.voik.agesandtheart.age.word.DerivedTags

/**
 * The rules that read the world's own tags and facts into ours, and **what each one actually catches.**
 *
 * Separate from the screen that shows them because this is the part worth checking: a rule keyed on a
 * fact nothing states is dead, and dead quietly — the derivation simply never fires and the tag it would
 * have granted is one nothing carries.
 */
object DerivationRules {

    /** One line of one `art/derivation/<aspect>.json`. */
    data class Rule(
        val aspect: Aspect,
        val key: String,
        val grants: Map<String, Double>,
        /** Whether it keys on a registry tag the member carries, rather than on a fact it states. */
        val byTag: Boolean,
    ) {
        val id: String get() = "${aspect.page}/${if (byTag) "tag" else "kind"}/$key"

        val says: String
            get() = grants.entries.joinToString(" ") { (tag, weight) -> "$tag %.1f".format(weight) }
    }

    fun of(corpus: Corpus): List<Rule> =
        corpus.vocabulary.derivation.entries.sortedBy { it.key.ordinal }.flatMap { (aspect, derivation) ->
            derivation.byTag.map { (key, grants) -> Rule(aspect, key, grants, byTag = true) } +
                derivation.byKind.map { (key, grants) -> Rule(aspect, key, grants, byTag = false) }
        }

    /**
     * Exactly what [rule] tags, **by running the derivation with nothing else in it.**
     *
     * The real code rather than a reimplementation of it, which matters more here than anywhere: two
     * rules granting one tag at one weight cannot be told apart by reading the merged answer, so a lens
     * that guessed would be wrong precisely where rules overlap — which is where a reader is looking.
     *
     * **Offline this answers for the fact-keyed half only.** Registry tags are bound by a running game,
     * so a `by_tag` rule matches nothing here; that is the harness rather than the rule
     * (`notes/the-tag-layer.md` §4).
     */
    fun catches(rule: Rule): List<String> {
        val alone = if (rule.byTag) {
            Derivation(byTag = mapOf(rule.key to rule.grants))
        } else {
            Derivation(byKind = mapOf(rule.key to rule.grants))
        }
        return DerivedTags.read(MinecraftRegistries.worldgen, mapOf(rule.aspect to alone), mutableListOf())
            .getOrElse(rule.aspect) { emptyMap() }
            .keys.sorted()
    }
}
