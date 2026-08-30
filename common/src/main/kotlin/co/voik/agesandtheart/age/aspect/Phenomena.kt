package co.voik.agesandtheart.age.aspect

/**
 * What *happens* here — storms, meteors, radiation, an inexorable column of sand (design §3.1, §5.2).
 *
 * **Nothing yet names a phenomenon and nothing yet enacts one.** This is the aspect's shape and only its
 * shape: [HAPPENS] is a populative parameter with no values, so a sentence can already be aimed here and
 * a recipe can already carry the answer, while the processes themselves wait for §5.
 *
 * The shape is not a guess. §5.2 settles that phenomena are **processes rather than events** — inexorable,
 * legible, with a visible direction, so your answers are to adapt or to leave — and processes accumulate:
 * an Age may have meteors *and* a rising sea, `only` may pin it to one, `except` may strike one out, and a
 * rung says how hard it comes. That is [Claim] exactly as `Spawns` and `Features` already use it, which is
 * why this needs no machinery of its own.
 *
 * **The one aspect a sentence fills *and* consequences arrive at** (design §7.7). A meteor storm you wrote
 * is a hazard you prepared for; one you did not write is the Age telling you something is wrong. So this
 * will eventually be written to from two directions, and the recipe cannot tell them apart on its own —
 * §5 has to decide what records that a phenomenon was *meant*.
 *
 * **It does not divide, it is sited** — the way §5.1's ambient hostility is a gradient around a wound
 * rather than a territory with a boundary. There is no mechanism for that yet either.
 *
 * The design says outright that this is the aspect most likely to change shape once §5 is built. Treat
 * everything here as provisional and re-read §5.2 before adding to it.
 */
object Phenomena {

    /**
     * What happens here — populative, with `only`/`except` to narrow and a rung for how hard it comes.
     *
     * Named `happens` rather than `phenomena` to avoid `phenomena.phenomena`, exactly as `Spawns.LIVES` is
     * named for the same reason. **Its values are ours**, unlike every other population: a creature is an
     * entity type and a feature is a placed feature, where a phenomenon has nothing behind it in vanilla at
     * all, so the pool here is one §5 will have to write.
     *
     * An Age with none is the ordinary case, which is why nothing is kept by default.
     */
    val HAPPENS = Parameter.population(
        "happens",
        leastKept = NOTHING_AT_ALL,
        emptiedBy = NOTHING,
        named = Phenomenon.entries.map { it.key },
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
