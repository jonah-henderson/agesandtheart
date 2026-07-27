package co.voik.agesandtheart.age.slot

import com.mojang.datafixers.util.Either
import com.mojang.serialization.Codec

/**
 * The enumerated choices a writer made about one preset — `arrangement=rings`, `depth=deep`.
 *
 * Stored by name rather than by position so a preset can gain a parameter without invalidating recipes
 * already written: anything unnamed falls back to the parameter's default, which is how an Age written
 * before a knob existed keeps behaving as it always did.
 *
 * Unrecognised names are *kept*, not dropped. A recipe is the only record of an Age, and silently
 * discarding part of one on load is how a save quietly becomes a different save — see
 * [co.voik.agesandtheart.age.AgeRecipe]. They are ignored when the world is built, and reported by
 * `/age list`, so a typo is visible rather than merely ineffective.
 */
@JvmInline
value class Options(val chosen: Map<String, List<String>> = emptyMap()) {

    /**
     * The option chosen for [parameter], or its default — **the first**, where several were named.
     *
     * What every enumerated parameter wants, since "sparse and crowded" is not a thing a preset can be.
     * Only a [Parameter.material] currently reads more than one, through [allOf].
     */
    fun of(parameter: Parameter): String = allOf(parameter).firstOrNull() ?: parameter.default

    /**
     * Every option chosen for [parameter], which for a material means **mingled** rather than divided
     * (design §3.2): blackstone *and* tuff through the same ground, not one region each.
     *
     * Empty rather than the default where nothing valid was named, so a consumer can tell "they said
     * nothing" from "they said the default" — which for a material is the difference between the preset's
     * own layered rock and a deliberate single substance.
     */
    fun allOf(parameter: Parameter): List<String> =
        chosen[parameter.name].orEmpty().filter(parameter::accepts)

    /** Names this preset does not understand — a typo, or a knob some later version removed. */
    fun unknownTo(preset: SlotPreset): Set<String> =
        chosen.keys - preset.parameters.map(Parameter::name).toSet()

    /** How a writer would have said it: `arrangement=rings stone=blackstone,tuff`, or nothing at all. */
    override fun toString(): String = chosen.entries.sortedBy { it.key }
        .joinToString(" ") { (parameter, options) -> "$parameter=${options.joinToString(",")}" }

    companion object {
        val NONE = Options()

        /**
         * A value is a list, and **a single one still reads and writes as a bare string** — so every recipe
         * written before parameters could hold several is byte-identical under this codec, and no generator
         * version had to move for the feature.
         *
         * The same either-or trick `AgeComposition` uses for a set-valued slot, and for the same reason:
         * the common case should be spelled the way it always was.
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
