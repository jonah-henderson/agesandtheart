package co.voik.agesandtheart.age.aspect

import com.mojang.datafixers.util.Either
import com.mojang.serialization.Codec

/**
 * The enumerated choices a writer made about one preset — `arrangement=rings`, `depth=deep`.
 *
 * Stored by name rather than by position, so a preset can gain a parameter without invalidating recipes
 * already written. Unrecognised names are *kept*, not dropped: they are ignored when the world is built
 * and reported by `/age list`, so a typo is visible rather than merely ineffective.
 */
@JvmInline
value class Options(val chosen: Map<String, List<String>> = emptyMap()) {

    /** The option chosen for [parameter], or its default — the first, where several were named. */
    fun of(parameter: Parameter): String = allOf(parameter).firstOrNull() ?: parameter.default

    /**
     * Every option chosen for [parameter], which for a material means mingled rather than divided (§3.2).
     *
     * Empty rather than the default where nothing valid was named, so a consumer can tell "they said
     * nothing" from "they said the default".
     */
    fun allOf(parameter: Parameter): List<String> =
        chosen[parameter.name].orEmpty().filter(parameter::accepts)

    /**
     * Every value chosen for [parameter] with what the writer asked of it — what a populative parameter
     * reads instead of [allOf] (§4.3.1, [Claim]). The mark is stripped before the value is validated, or
     * a struck-out value would fail `Identifier.tryParse` and the exclusion would not happen.
     */
    fun claimsOn(parameter: Parameter): List<Claim> =
        chosen[parameter.name].orEmpty().map(Claim::read).filter { parameter.accepts(it.value) }

    /** Names this preset does not understand — a typo, or a knob some later version removed. */
    fun unknownTo(preset: AspectPreset): Set<String> =
        chosen.keys - preset.parameters.map(Parameter::name).toSet()

    /** How a writer would have said it: `arrangement=rings stone=blackstone,tuff`, or nothing at all. */
    override fun toString(): String = chosen.entries.sortedBy { it.key }
        .joinToString(" ") { (parameter, options) -> "$parameter=${options.joinToString(",")}" }

    companion object {
        val NONE = Options()

        /**
         * A value is a list, and a single one still reads and writes as a bare string — the same either-or
         * trick `AgeComposition` uses for a set-valued aspect, so the common case keeps one spelling.
         */
        val CODEC: Codec<Options> = Codec.unboundedMap(
            Codec.STRING,
            Codec.either(Codec.STRING.listOf(), Codec.STRING).xmap(
                { either -> either.map({ many -> many }, ::listOf) },
                { many -> if (many.size == 1) Either.right(many.first()) else Either.left(many) },
            ),
        ).xmap(::Options, Options::chosen)
    }
}
