package co.voik.agesandtheart.age.aspect

/**
 * What *happens* here — storms, fire, a column of sand walking the world (design §3.1, §5.2).
 *
 * §5.2 settles that phenomena are **processes rather than events** — inexorable, legible, with a visible
 * direction, so your answers are to adapt or to leave — and processes accumulate: an Age may have a tempest
 * *and* a sandfall, `only` may pin it to one, `except` may strike one out, and a rung says how hard it
 * comes. That is [Claim] exactly as `Spawns` and `Features` already use it, which is why this needs no
 * machinery of its own.
 *
 * **The one aspect a sentence fills *and* consequences arrive at** (design §7.7), and that is built: a
 * tempest you wrote is a hazard you prepared for, where a sandfall the Art sent is the Age telling you
 * something is wrong. **The recipe does not have to tell them apart, which is what settled the question
 * this doc used to leave open** — what was *written* is a claim in these options, and what was *inflicted*
 * is derived from the instability index ([Phenomenon.inflictedBy]). Both are functions of the recipe and
 * neither is stored. Where they meet they compound.
 *
 * **It does not divide, it is sited** — the way §5.1's ambient hostility is a gradient around a wound
 * rather than a territory with a boundary. [Phenomenon.AURORA] is that mechanism: a rule a phenomenon
 * carries about the ground under the viewer, rather than a territory the resolver cuts the world into.
 */
object Phenomena {

    /**
     * What happens here — populative, with `only`/`except` to narrow and a rung for how hard it comes.
     *
     * Named `happens` rather than `phenomena` to avoid `phenomena.phenomena`, exactly as `Spawns.LIVES` is
     * named for the same reason. **Its values are ours**, unlike every other population: a creature is an
     * entity type and a feature is a placed feature, where a phenomenon has nothing behind it in vanilla at
     * all, so the pool here is written rather than derived — see [Phenomenon].
     *
     * An Age with none is the ordinary case, which is why nothing is kept by default.
     */
    val HAPPENS = Parameter.population(
        "happens",
        leastKept = NOTHING_AT_ALL,
        emptiedBy = NOTHING,
        named = Phenomenon.entries.map { it.key },
            help = "What happens here: a tempest, an inferno, an aurora.",
        )

    /**
     * What these options say befalls the Age — empty where they say nothing.
     *
     * Here rather than in a reader, because two of them now ask: what happens every tick
     * ([co.voik.agesandtheart.age.phenomena.Happenings]) and what the sky is *shown* as a result ([Sky]).
     * Two copies of a `Skew` over one parameter is exactly the shape a rule kept in two places takes.
     */
    fun claimsIn(options: Options): List<Claim> =
        Skew.of(options.allSpelled(HAPPENS.name).map(Claim::read)).wanted

    /** The claim by which [phenomenon] befalls these options, or null where it does not. */
    fun claimFor(options: Options, phenomenon: Phenomenon): Claim? =
        claimsIn(options).firstOrNull { it.value == phenomenon.key }

    /** How an Age says it is quiet: nothing befalls it, whatever its instability would otherwise bring. */
    const val NOTHING = "nothing"

    private const val NOTHING_AT_ALL = 0.0
}
