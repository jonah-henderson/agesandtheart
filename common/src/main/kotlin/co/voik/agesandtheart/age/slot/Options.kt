package co.voik.agesandtheart.age.slot

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
value class Options(val chosen: Map<String, String> = emptyMap()) {

    /** The option chosen for [parameter], or its default. */
    fun of(parameter: Parameter): String =
        chosen[parameter.name]?.takeIf(parameter::accepts) ?: parameter.default

    /** Names this preset does not understand — a typo, or a knob some later version removed. */
    fun unknownTo(preset: SlotPreset): Set<String> =
        chosen.keys - preset.parameters.map(Parameter::name).toSet()

    /** How a writer would have said it: `arrangement=rings depth=deep`, or nothing at all. */
    override fun toString(): String = chosen.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}=${it.value}" }

    companion object {
        val NONE = Options()

        val CODEC: Codec<Options> = Codec.unboundedMap(Codec.STRING, Codec.STRING).xmap(::Options, Options::chosen)
    }
}
