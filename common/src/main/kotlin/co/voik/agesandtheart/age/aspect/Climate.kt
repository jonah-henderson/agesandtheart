package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.worldgen.biome.ClimateAxis
import co.voik.agesandtheart.worldgen.biome.ClimateBias

/**
 * The coordinates an Age's biomes are looked up at — how hot it is, how wet, and how odd (design §3.1).
 *
 * One preset, with the axes doing the work through [Parameter.Kind.RANGED] parameters and the numbers
 * living on the words that set them. A preset per combination of axes was tried three ways and always
 * exploded, since a preset must name a value on *every* axis — see `notes/decisions.md`.
 */
enum class Climate(override val key: String) : AspectPreset {
    /**
     * Vanilla's own climate, before anything narrows it. The only preset, and it always seats — not a
     * placeholder for a richer list, since the spans already express what such a list would.
     */
    NATURAL("natural"),
    ;

    override val aspect = Aspect.CLIMATE

    /** One ranged knob per axis a writer's vocabulary can reach — see [ClimateAxis]. */
    override val parameters: List<Parameter> get() = ClimateAxis.entries.map { it.parameter }

    override fun getSerializedName(): String = key

    /**
     * What this Age's climate was narrowed to, read off one fragment's options. An axis nobody spoke about
     * keeps [Span.NATURAL] and is dropped, so a recipe records only what was said.
     */
    fun biasIn(options: Options): ClimateBias = ClimateBias(
        ClimateAxis.entries
            .associateWith { axis -> Span.read(options.of(axis.parameter)) ?: Span.NATURAL }
            .filterValues { it != Span.NATURAL },
    )
}
