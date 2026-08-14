package co.voik.agesandtheart.age.aspect

/**
 * **What an Age's parts answer**, for the readers that assemble one thing out of several of them — a look is
 * the water's clarity and the air's fog and the vault's colour, and a sky is the suns, the moons and the
 * stars.
 *
 * A named interface rather than the two or three function types it replaced. `Atmosphere.lookIn` took an
 * `(Aspect) -> Options` and `Sky.specFor` took an `(Aspect, Int) -> Options` *and* an `(Aspect) -> Int`,
 * called as `specFor(composition::optionsFor, seed, composition::membersIn)` — one object taken apart into
 * three method references, so the signature no longer said what it read and a caller who forgot the third
 * argument got a one-sun sky rather than a compile error.
 *
 * [co.voik.agesandtheart.age.AgeComposition] is the implementation; there is only ever meant to be one, and
 * this exists so that the aspect package can be read from without depending on it.
 */
interface AgeParts {
    /** How the [member]th of [aspect] is steered — its own options, not the aspect's as a whole. */
    fun optionsFor(aspect: Aspect, member: Int = 0): Options

    /** How many members [aspect] holds: its territories, or the bodies a book described into being. */
    fun membersIn(aspect: Aspect): Int

    companion object {
        /**
         * An Age with no parts to ask about — what a **bespoke** world is, being a whole generator with a
         * name rather than an assembly.
         *
         * Every reader then falls back to its own default, which is what those Ages already did through a
         * `?: Options.NONE` at the one call site that knew they existed.
         */
        val NONE: AgeParts = object : AgeParts {
            override fun optionsFor(aspect: Aspect, member: Int): Options = Options.NONE
            override fun membersIn(aspect: Aspect): Int = 0
        }
    }
}
