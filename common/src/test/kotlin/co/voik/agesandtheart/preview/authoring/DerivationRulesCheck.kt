package co.voik.agesandtheart.preview.authoring

import co.voik.agesandtheart.NEEDS_REGISTRIES
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * The derivation rules, asked what they catch.
 *
 * The word forge shows this as a read-only lens because the file cannot: `"#minecraft:is_forest":
 * {"wooded": 1.0}` states the rule perfectly and says nothing whatever about its consequence, and the
 * fault the tag pass keeps finding is a rule that was *nearly* the fact it stood for.
 *
 * Only the fact-keyed half can be asserted offline. A rule reading a registry tag matches nothing here
 * because nothing has bound one, which is the harness rather than the rule.
 */
@Tags(NEEDS_REGISTRIES)
class DerivationRulesCheck : FunSpec({

    val corpus by lazy { Corpus.load() }

    test("every rule reads either a tag or a stated fact, and grants something") {
        val rules = DerivationRules.of(corpus)
        check(rules.isNotEmpty()) { "the pack ships no derivation rules at all" }
        val silent = rules.filter { it.grants.isEmpty() }
        check(silent.isEmpty()) { "${silent.size} rules grant nothing: ${silent.map { it.id }}" }
    }

    /**
     * **A rule keyed on a fact nothing states is dead**, and dead quietly: the derivation never fires,
     * and the tag it would have granted is simply one that nothing carries. Knowable offline, because a
     * stated fact is what survives without a server.
     */
    test("every rule that reads a stated fact catches something") {
        val dead = DerivationRules.of(corpus)
            .filterNot { it.byTag }
            .filter { DerivationRules.catches(it).isEmpty() }
        check(dead.isEmpty()) {
            "${dead.size} fact rules catch nothing:\n" + dead.joinToString("\n") { "  ${it.id} -> ${it.says}" }
        }
    }

    /** Every tag a rule grants should be one the language can ask for, or the rule is writing to nobody. */
    test("a rule grants tags the corpus knows") {
        val carried = corpus.vocabulary.carriedTags
        val asked = corpus.vocabulary.words.flatMap { it.wanted + it.unwanted + it.offeredTags }.toSet()
        val stray = DerivationRules.of(corpus)
            .flatMap { rule -> rule.grants.keys.map { rule.id to it } }
            .filterNot { (_, tag) -> tag in carried || tag in asked }
        check(stray.isEmpty()) {
            "${stray.size} rules grant a tag nothing carries and no word asks for:\n" +
                stray.joinToString("\n") { (rule, tag) -> "  $rule grants '$tag'" }
        }
    }
})
