package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * Every parameter a word can reach says what it is.
 *
 * `footing` means nothing out of context and `free` means less, so the authoring tool shows the sentence
 * beside the parameter — and it reads it off [Parameter.help] rather than keeping a table of its own. **This is
 * what makes that safe**: one copy, and a parameter added without a sentence fails the build instead of
 * turning up blank in the tool for somebody to puzzle over.
 */
@Tags(NEEDS_REGISTRIES)
class ParameterHelpCheck : FunSpec({

    /** Every parameter reachable from an aspect: its own parameters, and its presets'. */
    fun everyParameter(): List<Pair<Aspect, Parameter>> = Aspect.entries.flatMap { aspect ->
        (aspect.parameters + aspect.authored.flatMap { it.parameters }).map { aspect to it }
    }

    test("every parameter says what it is") {
        MinecraftRegistries.ensureStoodUp()
        val silent = everyParameter().filter { (_, parameter) -> parameter.help.isBlank() }
            .map { (aspect, parameter) -> "${aspect.page}.${parameter.name}" }
            .distinct()
            .sorted()
        check(silent.isEmpty()) {
            "no help for ${silent.joinToString(", ")} — add `help = \"…\"` where the Parameter is declared"
        }
    }

    /**
     * A parameter's help is a sentence, not a label. The bar is deliberately low — it catches `help = "size"`,
     * which is the shape a rushed one takes and says nothing the name did not.
     */
    test("a parameter's help says more than its name") {
        MinecraftRegistries.ensureStoodUp()
        val thin = everyParameter()
            .filter { (_, parameter) -> parameter.help.isNotBlank() && parameter.help.length < SENTENCE }
            .map { (aspect, parameter) -> "${aspect.page}.${parameter.name}: '${parameter.help}'" }
            .distinct()
        check(thin.isEmpty()) { "these read as labels rather than sentences: ${thin.joinToString("; ")}" }
    }

    /** Where a value is explained, it has to be a value the parameter actually takes. */
    test("value help names values that exist") {
        MinecraftRegistries.ensureStoodUp()
        val stray = everyParameter().flatMap { (aspect, parameter) ->
            (parameter.optionHelp.keys - parameter.options.toSet())
                .map { "${aspect.page}.${parameter.name} explains '$it', which it does not take" }
        }.distinct()
        check(stray.isEmpty()) { stray.joinToString("; ") }
    }
}) {
    private companion object {
        const val SENTENCE = 20
    }
}
