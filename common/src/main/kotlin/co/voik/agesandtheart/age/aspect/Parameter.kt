package co.voik.agesandtheart.age.aspect

import net.minecraft.resources.Identifier

/**
 * One choice a preset offers — "sparse / scattered / crowded", or a stretch of a continuous axis. The
 * first option is the default, so a preset named with no options still resolves.
 *
 * **Numbers are allowed here and always were.** §3.2 forbids them being exposed to the *player*, and a
 * [Holds.RANGE] parameter never is: a writer says a word, the word carries the span. Named steps were the
 * first reading of that rule and they do not scale — every new word that wants to sit on a different band
 * needs a new step, and steps must then be named on every axis at once.
 *
 * One kind is open-valued: a [material] takes a registry id rather than one of a list.
 */
data class Parameter(
    val name: String,
    val options: List<String>,
    val open: Boolean = false,
    /** What this property holds, which decides what a claim on it can mean — see [Holds]. */
    val holds: Holds = Holds.CATALOGUE,
    /**
     * Points along a [Holds.RANGE] axis and what they mean **in the game**.
     *
     * An axis runs −1 to 1 and the number says nothing on its own: a writer bounding a temperature has no
     * way to know whether `0.2` is a meadow or a desert. The corpus can say where other *words* sit,
     * which is useful and circular; this says what the *world* does there.
     *
     * Approximate on purpose, and here rather than in a document for the reason [help] is: one copy, in
     * the thing it describes, read by whatever wants to show it.
     */
    val landmarks: List<Landmark> = emptyList(),
    /**
     * Whether a claim on this may be sited in one biome — `in <biome>` (design §4.3.1). Set with [perBiome].
     *
     * **Asked per parameter rather than per aspect**, and the difference is not pedantry: the air's fog may
     * be sited and its temperature may not, because temperature is what *chooses* the biome and siting it
     * is circular. An aspect holding both would have no honest answer.
     */
    val confinable: Boolean = false,
    /**
     * Whether this parameter's value is a **block** — the palette's stone, a surface's skin, a minted
     * pattern's substance.
     *
     * A fact about the parameter rather than a name to compare against. `Grammar` asks whether a word is a
     * material and `Resolver` asks what substance it carries, and both did it by looking for `Terrain.STONE`
     * by name — one aspect's parameter standing in for "a block", which is true of the corpus today and is not
     * what either of them means.
     */
    val material: Boolean = false,
    /**
     * Whether what this holds has to be something a player can stand on — true of the rock and of nothing
     * else so far (see [Materials]).
     *
     * **Not every material parameter, and the surface is why.** A skin is one layer over rock that already
     * holds you up, so the worst a strange one costs is a block of fall onto solid ground — and a skin of
     * *air* is how a writer says the ground wears nothing at all, which the rock could never allow. The
     * fatal case is the fill: a world built of something you fall through is not a place at all.
     */
    val holdsYouUp: Boolean = false,
    /**
     * Whether the order the writer wrote these in is part of what they said.
     *
     * **This parameter's values are a sequence, where every other mingling parameter's are a set** — and that is the
     * whole of the distinction. `landmass.stone=granite and andesite` is two rocks in one wall and neither
     * is first; an aurora's colours are a ramp from its crown to its hem, and which is the crown is the one
     * thing the writer stated outright.
     *
     * It is read in exactly one place, [co.voik.agesandtheart.age.word.Resolver.contended], which otherwise
     * hands back a mingling in tier-then-seeded order. Stating it here rather than casing on the name there
     * is what keeps that function from having to know about individual parameters.
     *
     * Written order decides one other thing in the whole resolver — which template a book starts from — and
     * `the-art-design.md` §3.5 names both.
     */
    val keepsWrittenOrder: Boolean = false,
    /**
     * What this parameter is, in a sentence, for whoever is authoring a word against it.
     *
     * **Here rather than in a table the tool keeps**, because a second copy is a copy that goes stale:
     * `footing` means nothing out of context and `free` means less, and a writer meeting either needs to
     * be told at the moment they meet it. `ParameterHelpCheck` insists every parameter a word can reach has
     * one, so a parameter added without a sentence fails the build rather than turning up blank in the tool.
     */
    val help: String = "",
    /** The same for values that are not self-evident — `free`, `grounded`, `great_halls`. */
    val optionHelp: Map<String, String> = emptyMap(),
) {
    /** This parameter, sited-in-a-biome — see [confinable]. */
    fun perBiome(): Parameter = copy(confinable = true)

    val default: String get() = options.first()

    /**
     * Whether this parameter would understand [option]. An open one takes any well-formed id, including
     * one naming content this pack does not have (§3.1).
     */
    fun accepts(option: String): Boolean {
        val isOneOfTheNamedOptions = option in options
        val looksLikeARegistryId = namesARegistryEntry(option) && Identifier.tryParse(option) != null
        // Every form a word may ask a ranged axis for, not only a band — see [Setting].
        val looksLikeASpan = holds == Holds.RANGE && Setting.describes(option)
        if (isOneOfTheNamedOptions || looksLikeASpan) return true
        if (!open || !looksLikeARegistryId) return false
        // **Rock has to hold somebody up** ([Materials]). Asked here because this is the one gate every
        // reader already goes through: `Options.of` filters on it and `unreadableValues` reports what it
        // filtered, so a refused block falls back and is *said* rather than quietly becoming stone.
        return !holdsYouUp || Materials.makesAWorld(option)
    }

    /**
     * Whether a **word** may write [option] here: anything the world [accepts], and for a material a query
     * by tag (`#frozen`), which the resolver settles to one block before the world ever sees it.
     */
    fun acceptsFromAWord(option: String): Boolean = accepts(option) || (material && Materials.isQuery(option))

    /**
     * A point on an axis, and what the game does there.
     *
     * [isVanilla] marks the one that is **the game's own value** — where the axis sits for a world nobody
     * wrote. Not every axis has one (a temperature runs a whole world's worth of them) and it is not
     * always the middle, which is the whole reason it is stated rather than assumed: a writer reading
     * `-0.3333` needs to know it is where they started, not a number somebody liked.
     */
    data class Landmark(val at: Double, val said: String, val isVanilla: Boolean = false)

    companion object {
        /**
         * What a material parameter reads as when nobody named one: the preset's own substance. A named
         * default rather than an absent value, so every consumer asks the same question.
         */
        const val UNCHANGED = "unchanged"

        /** A block a preset is made of — the palette's stone, a terrain's spires, a structure's walls. */
        fun material(name: String, holdsYouUp: Boolean = false, help: String = "") =
            Parameter(name, listOf(UNCHANGED), open = true, material = true, holdsYouUp = holdsYouUp, help = help)

                /**
         * A continuous axis a word may bound — climate's temperature and humidity. Defaults to the whole
         * axis, so an Age told nothing keeps whatever vanilla's noise produced.
         */
        fun ranged(name: String, help: String = "", landmarks: List<Landmark> = emptyList()) =
            Parameter(
                name,
                listOf(Span.NATURAL.spelled()),
                holds = Holds.RANGE,
                help = help,
                landmarks = landmarks,
            )

        /**
         * A property that is simply true or false — **a catalogue of two, and no new shape** (world model
         * §2). A flag is one value drawn from a closed list like any other; what it is not is a scale, and
         * spelling it as one invites a value between the two that does not exist.
         *
         * [FALSE] first, so an unstated flag is off: [default] is the first option.
         */
        fun flag(name: String, help: String = "") = Parameter(name, listOf(FALSE, TRUE), help = help)

        /**
         * **How many of a population there are** — the one parameter whose value is the size of the roll rather
         * than an entry in it, and the only way a *word* can bring a body into being.
         *
         * A writer never turns it: they describe a sun and there is one, describe another and there are
         * two, and `the-world-model.md` §2 is emphatic that there are no numbers in the language. This is
         * for the corpus, where a word has to be able to say what an Age looks like when the writer said
         * nothing at all — an inferno's sky wants more than one thing burning in it, and no clause was
         * written to mint them.
         *
         * **Closed, and short.** Four is already a strange sky; leaving it open would let a word ask for
         * forty suns and the renderer would oblige. The first option is the default, so an Age nobody asked
         * has whatever its template gave it.
         *
         * `Resolver` reads this rather than steering it: the roll's size *is* the number of stored entries,
         * so a `cast` that landed in the options would be a second place recording the same fact.
         */
        fun cast() = Parameter(
            CAST,
            listOf("1", "2", "3", "4"),
            help = "How many of these the Age has, where the book described none of its own.",
        )

        /** The reserved name [cast] answers to, spelled once because three places compare against it. */
        const val CAST = "cast"

        const val TRUE = "true"
        const val FALSE = "false"

        /**
         * What an option reads as when a writer left it alone — whatever the world would have done.
         *
         * **First in the list wherever it appears, so it is the [default]**, and named for that rather
         * than for what it happens to mean in any one place.
         */
        const val DEFAULT = "default"
    }
}
