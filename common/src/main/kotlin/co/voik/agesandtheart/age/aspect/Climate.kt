package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.worldgen.biome.ClimateAxis
import co.voik.agesandtheart.worldgen.biome.ClimateBias

/**
 * The coordinates an Age's biomes are looked up at — how hot it is, how wet, and how odd (design §3.1).
 *
 * It rode on the dressing until now, honoured by exactly one preset out of four, which is the tell that it was
 * never the dressing's business. `arid` and `hot` describe a *climate*; that vanilla's overworld dressing was
 * the only thing able to read them was an implementation accident.
 *
 * **One preset, and the axes do the work.** This is the aspect where the presets genuinely are degenerate, and
 * three attempts at making them meaningful all failed the same way (see
 * `notes/the-art-implementation-plan.md` → Phase 4.5 step 3b): a preset must name a value on *every* axis, so an
 * unstated axis needs an explicit "natural" band and the count explodes as the other axes return. Climate is up
 * to six axes internally even where their vocabulary is dispersed — weirdness reads as a *biome* idea and
 * continentalness and erosion as *terrain* modifiers (Jonah) — so a preset per combination was never going to
 * survive.
 *
 * So the writing happens in [Parameter.Kind.RANGED] parameters, one per axis, and the numbers live on the words
 * that set them. Two consequences worth knowing:
 *
 * - **A word carrying spans is the correlation.** `beautiful` names temperature *and* humidity together, so
 *   nothing draws the axes independently, and nothing can hand a writer a desert when they asked for beauty.
 * - **Two words broaden; two that disagree fracture** into regions with a climate each, which is 3a's mechanism
 *   and the antonym table between them. See [Parameter.Kind.RANGED].
 */
enum class Climate(override val key: String) : AspectPreset {
    /**
     * Vanilla's own climate, before anything narrows it — every axis at its full natural range.
     *
     * The only preset, and it always seats. It is not a placeholder for a richer list: what a richer list would
     * have expressed is exactly what the spans express, without the combinatorics.
     */
    NATURAL("natural"),
    ;

    override val aspect = Aspect.CLIMATE

    /** One ranged knob per axis a writer's vocabulary can reach — see [ClimateAxis]. */
    override val parameters: List<Parameter> get() = ClimateAxis.entries.map { it.parameter }

    override fun getSerializedName(): String = key

    /**
     * What this Age's climate was narrowed to, read off one fragment's options.
     *
     * An axis nobody spoke about keeps [Span.NATURAL] and is dropped from the bias, so a recipe records only
     * what was actually said and an Age that named no climate word carries no climate data at all.
     */
    fun biasIn(options: Options): ClimateBias = ClimateBias(
        ClimateAxis.entries
            .associateWith { axis -> Span.read(options.of(axis.parameter)) ?: Span.NATURAL }
            .filterValues { it != Span.NATURAL },
    )
}
