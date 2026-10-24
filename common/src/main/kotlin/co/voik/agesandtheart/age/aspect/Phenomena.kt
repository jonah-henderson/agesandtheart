package co.voik.agesandtheart.age.aspect

import kotlin.math.pow

/**
 * What *happens* here — storms, fire, a column of sand walking the world (design §5.2).
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
    val HAPPENS = Pool(
        "happens",
        // `happens=nothing` is a quiet Age: nothing befalls it, whatever its instability would otherwise bring.
        emptiedBy = Pool.NOTHING,
        startsEmpty = true,
        named = Phenomenon.entries.map { it.key },
            help = "What happens here: a tempest, an inferno, an aurora.",
        )

    /**
     * How often each phenomenon comes — `frequency`, the name the curtain, the bow and the rain share, so
     * `frequent` reaches all of them. Said of members, so `frequent tempest` is the tempest's alone.
     */
    val FREQUENCY = Parameter.ranged(
        "frequency",
        help = "How often it comes, against its own ordinary rate.",
        landmarks = listOf(
            Parameter.Landmark(-1.0, "a sixth as often"),
            Parameter.Landmark(0.0, "as often as it would", isVanilla = true),
            Parameter.Landmark(1.0, "never stopping"),
        ),
    ).perMember()

    /**
     * How hard each phenomenon is while it lasts — `strength`, the name the creatures hold too, so `strong` and
     * `weak` reach both. It moves the dials instability buys, toward their fury and below ordinary.
     */
    val STRENGTH = Parameter.ranged(
        "strength",
        help = "How hard it is while it lasts, against an ordinary one.",
        landmarks = listOf(
            Parameter.Landmark(-1.0, "gentler than ordinary"),
            Parameter.Landmark(0.0, "an ordinary one", isVanilla = true),
            Parameter.Landmark(1.0, "half way to its fury"),
        ),
    ).perMember()

    /**
     * How fast a phenomenon that grows does it — `pace`, the name the rain, the creatures and the bodies share, so
     * `slow` and `swift` reach it. Only the overgrowth grows.
     */
    val PACE = Parameter.ranged(
        "pace",
        help = "How fast it grows, against its own ordinary pace.",
        landmarks = listOf(
            Parameter.Landmark(-1.0, "half as fast"),
            Parameter.Landmark(0.0, "its own pace", isVanilla = true),
            Parameter.Landmark(1.0, "twice as fast"),
        ),
    ).perMember()

    /** How fast [phenomenon] was written to grow, as a multiple of its own pace: half as fast to twice. */
    fun growthOf(options: Options, phenomenon: Phenomenon, seed: Long): Double =
        FASTEST_GROWTH.pow(options.steer(PACE, seed, member = phenomenon.key) ?: UNSAID_PACE)

    private const val UNSAID_PACE = 0.0
    private const val FASTEST_GROWTH = 2.0

    /** How hard [phenomenon] was written in these options, from -1 to 1, nought where nothing was said. */
    fun strengthOf(options: Options, phenomenon: Phenomenon, seed: Long): Double =
        options.steer(STRENGTH, seed, member = phenomenon.key) ?: UNSAID_STRENGTH

    private const val UNSAID_STRENGTH = 0.0

    /** How often [phenomenon] was written to come in these options, salted by the Age's [seed]. */
    fun recurrenceOf(options: Options, phenomenon: Phenomenon, seed: Long): Recurrence =
        Recurrence.of(options.steer(FREQUENCY, seed, member = phenomenon.key))

    /**
     * What these options say befalls the Age — empty where they say nothing.
     *
     * Here rather than in a reader, because two of them now ask: what happens every tick
     * ([co.voik.agesandtheart.age.phenomena.Happenings]) and what the sky is *shown* as a result ([Sky]).
     * Two copies of a `Skew` over one parameter is exactly the shape a rule kept in two places takes.
     */
    fun claimsIn(options: Options): List<Claim> =
        options.skewOn(HAPPENS).wanted
            // **The one pool that starts empty**, so a description here cannot mean "more of what is
            // already happening" — nothing is. One asking for more than ordinary brings its phenomenon
            // about, which is how `beautiful` earns its aurora; one asking for less asks for less of
            // nothing, and used to light an inferno in a beautiful Age at a fifth strength.
            .filterNot(Claim::bringsNothingAbout)

    /** The claim by which [phenomenon] befalls these options, or null where it does not. */
    fun claimFor(options: Options, phenomenon: Phenomenon): Claim? =
        claimsIn(options).firstOrNull { it.value == phenomenon.key }
}
