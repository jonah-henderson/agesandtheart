package co.voik.agesandtheart.preview

/**
 * **A SPIKE — do not build on this.** It exists to answer one question with numbers instead of taste:
 * if an aspect's *value* were typed by what it actually is, rather than forced through `AspectPreset`,
 * how much of `Aspect.kt` would survive?
 *
 * The suspicion it tests: the resolver was written to be generic over aspects, and that genericity is
 * nominal — `Aspect.kt` carries **five separate `when (this)` blocks** (`open`, `authored`, `presetFor`,
 * `positional`, `appetiteForCompany`), so the core already knows every aspect concretely. It pays the
 * abstraction's cost without buying "add an aspect without touching the core".
 *
 * The finding, printed by [main] and summarised at the bottom of this file: four kinds cover all ten
 * aspects (the seven built and the three named-but-unbuilt), **five of the ten are the same kind**, and
 * what remains common to all four is exactly two capabilities — both of them language concerns rather
 * than worldgen ones. Which is the line worth drawing.
 *
 * Deliberately standalone: it imports nothing from `age.aspect`, so it states the model rather than
 * depending on the one it is arguing about, and cannot rot when that one changes.
 */
fun main() {
    reportAspects()
    reportKindCensus()
    reportCapabilities()
    reportCommonInterface()
    reportRecipeShape()
    reportWhenBlockAudit()
}

/**
 * The same Age, spelled both ways.
 *
 * A real one: `agesandtheart:mashar`, the canyon that came out with no floor because the Spire's sky
 * carried a y-0 vertical band with it. Its recipe is a fair sample — nothing about it is unusual.
 *
 * An aspect is spelled only where the sentence *departed* from vanilla, so a value that could not have
 * been anything else stops being recorded. What goes is not abbreviation: it is three tokens announcing
 * choices that were never available.
 */
private fun reportRecipeShape() {
    heading("WHAT A RECIPE STOPS SAYING")
    val today = listOf(
        "landmass=canyon" to true,
        "terrain.stone=minecraft:packed_ice" to true,
        "sea=minecraft:water" to true,
        "carvers=caves" to true,
        "biomes=vanilla" to false,
        "sky=spire" to true,
        "structures=vanilla" to false,
        "climate=natural" to false,
        "climate.humidity=-0.3..0.3" to true,
        "climate.temperature=-0.95..-0.45" to true,
    )
    println("  today:")
    println("      ${today.joinToString(" ") { it.first }}")
    println()
    println("  departures only:")
    println("      ${today.filter { it.second }.joinToString(" ") { it.first }}")
    println()
    for ((token, kept) in today.filterNot { it.second }) {
        val reason = when (token.substringBefore('=')) {
            "climate" -> "DIALS: there is one climate and never was a choice"
            else -> "the baseline, departed from by naming something, not by naming the baseline"
        }
        println("  dropped  %-20s %s%s".format(token, reason, if (kept) "" else ""))
    }
    println()
    println("  ${today.count { !it.second }} of ${today.size} tokens were ceremony.")
}

/**
 * What an aspect's answer **is**, which is the thing the current model has no word for.
 *
 * Not invented here: `Parameter.Kind` already carries three of these — `PREDICATIVE`, `POPULATIVE`,
 * `RANGED` — one level down, on parameters. That is the tell. An aspect whose nature is populative or
 * ranged wears a vestigial single-member preset *in order to reach a parameter that can express it*.
 */
private enum class Kind(val key: String, val explanation: String) {
    /**
     * A named, curated bundle of tuning, plus dials. Earns its name by hiding what a writer could not
     * assemble: a field tree of co-varying constants, tuned by hand until the landscape reads right.
     */
    PRESET("preset", "one of a curated few, plus dials"),

    /**
     * A registry object named outright, plus dials. The pool a *vague* word may draw from is curated
     * separately (§8.2); an exact word points at one and arrives with its answer in hand.
     */
    REFERENT("referent", "a registry id, plus dials"),

    /**
     * Weighted claims that **accumulate** — said plainly, singled out with `only`, struck with `except`,
     * each carrying the rung it was quantified at. There is no draw: two claims are not rivals.
     */
    POPULATION("population", "claims that accumulate, each with a rung"),

    /**
     * Continuous axes bounded by spans, and nothing else. No value is chosen at all, because there is
     * nothing to choose between — the whole answer is where the dials were left.
     */
    DIALS("dials", "spans on continuous axes, and no choice at all"),
}

/**
 * The ten aspects: the seven built, and the three design §3.1 names and has never built.
 *
 * `contents` is spelled **features** here, that being what the thing actually is — ores, vegetation, and
 * everything else vanilla places that is not a structure.
 */
private enum class SpikeAspect(
    val key: String,
    val kind: Kind,
    /** Whether it can divide the world into territories — see the audit at the bottom. */
    val positional: Boolean,
    val built: Boolean,
    val records: String,
) {
    TERRAIN("terrain", Kind.PRESET, positional = true, built = true, records = "one of 17 landforms"),
    CARVERS("carvers", Kind.PRESET, positional = true, built = true, records = "one of 4 (carver set + water table)"),
    SKY("sky", Kind.PRESET, positional = false, built = true, records = "vanilla, or the Spire's own"),
    SEA("sea", Kind.REFERENT, positional = true, built = true, records = "a block id"),
    CLIMATE("climate", Kind.DIALS, positional = true, built = true, records = "a span per axis"),
    BIOMES("biomes", Kind.POPULATION, positional = false, built = true, records = "biomes grown"),
    STRUCTURES("structures", Kind.POPULATION, positional = false, built = true, records = "structure sets built"),

    // The three that design §3.1 names and nothing has ever implemented. Each is a population, which is
    // the whole of why this taxonomy is worth having: the shape recurs where "aspect-ness" never does.
    FEATURES("features", Kind.POPULATION, positional = false, built = false, records = "ores, vegetation, the rest"),
    SPAWNS("spawns", Kind.POPULATION, positional = false, built = false, records = "what lives here, and how thickly"),
    PHENOMENA("phenomena", Kind.POPULATION, positional = false, built = false, records = "weather, and worse"),
}

/**
 * One thing the resolver has to be able to do. The point of enumerating them is the intersection: what
 * every kind needs is what a common interface may hold, and everything else belongs to a kind.
 */
private enum class Capability(val key: String, val why: String) {
    SCORE("score a candidate", "how strongly does this word answer this preset's tags"),
    DRAW("draw one", "weighted pick among what survived the narrowing words"),
    SHARE("share ground", "two answers that cannot be reconciled each get territory"),
    ACCUMULATE("accumulate claims", "only / except / rungs — two claims are not rivals"),
    MERGE("merge spans", "two ranges broaden, or fracture where the antonyms disagree"),
    CHARGE("charge contradiction", "the instability registers, which are the same everywhere"),
    PRICE("price the ink", "tier cost per aspect constrained, which is the same everywhere"),
}

/** What each kind asks of the resolver. */
private val NEEDS: Map<Kind, List<Capability>> = mapOf(
    Kind.PRESET to listOf(
        Capability.SCORE, Capability.DRAW, Capability.SHARE, Capability.CHARGE, Capability.PRICE,
    ),
    Kind.REFERENT to listOf(
        Capability.SCORE, Capability.DRAW, Capability.SHARE, Capability.CHARGE, Capability.PRICE,
    ),
    Kind.POPULATION to listOf(Capability.ACCUMULATE, Capability.CHARGE, Capability.PRICE),
    Kind.DIALS to listOf(Capability.MERGE, Capability.SHARE, Capability.CHARGE, Capability.PRICE),
)

/** One of `Aspect.kt`'s five `when (this)` blocks, and what becomes of it under the kinds above. */
private class WhenBlock(val name: String, val survives: Boolean, val verdict: String)

private val AUDIT = listOf(
    WhenBlock("open", survives = false, verdict = "it only ever meant `kind == REFERENT`"),
    WhenBlock("authored", survives = false, verdict = "the pool is data belonging to PRESET, not a switch"),
    WhenBlock("presetFor", survives = false, verdict = "one parser per kind — an id, or a name — not a 7-way switch"),
    WhenBlock(
        "appetiteForCompany", survives = false,
        verdict = "a dial on PRESET/REFERENT (0.12–0.25); structurally zero for the rest, which accumulate or merge",
    ),
    WhenBlock(
        "positional", survives = true,
        verdict = "a real per-aspect fact (a world has one sky) — but a declared field, not a switch",
    ),
)

private fun reportAspects() {
    heading("THE TEN ASPECTS, TYPED BY WHAT THEIR ANSWER IS")
    println("  %-11s %-11s %-11s %s".format("aspect", "kind", "positional", "what the recipe records"))
    for (aspect in SpikeAspect.entries) {
        val mark = if (aspect.built) "" else "   (unbuilt)"
        println(
            "  %-11s %-11s %-11s %s%s".format(
                aspect.key, aspect.kind.key, if (aspect.positional) "yes" else "no", aspect.records, mark,
            ),
        )
    }
}

private fun reportKindCensus() {
    heading("KIND CENSUS")
    val byKind = SpikeAspect.entries.groupBy { it.kind }
    for (kind in Kind.entries.sortedByDescending { byKind[it].orEmpty().size }) {
        val members = byKind[kind].orEmpty()
        println("  %-11s %d aspect(s)   %s".format(kind.key, members.size, members.joinToString(" ") { it.key }))
        println("  %-11s %s".format("", kind.explanation))
    }
    val commonest = byKind.maxByOrNull { it.value.size }
    println()
    println("  The shape that recurs is ${commonest?.key?.key}, at ${commonest?.value?.size} of ${SpikeAspect.entries.size}.")
    println("  No two aspects share anything the code uses by virtue of both being aspects.")
}

private fun reportCapabilities() {
    heading("WHAT EACH KIND ASKS OF THE RESOLVER")
    for (kind in Kind.entries) {
        println("  ${kind.key}:")
        for (capability in NEEDS.getValue(kind)) println("      ${capability.key} — ${capability.why}")
    }
}

private fun reportCommonInterface() {
    heading("WHAT SURVIVES AS A COMMON INTERFACE")
    val shared = Capability.entries.filter { capability -> NEEDS.values.all { capability in it } }
    val owned = Capability.entries - shared.toSet()
    println("  Common to all four kinds (${shared.size}):")
    for (capability in shared) println("      ${capability.key} — ${capability.why}")
    println()
    println("  Belonging to one kind or two (${owned.size}):")
    for (capability in owned) {
        val kinds = Kind.entries.filter { capability in NEEDS.getValue(it) }.joinToString(", ") { it.key }
        println("      %-20s %s".format(capability.key, kinds))
    }
    println()
    println("  Both survivors are *language* concerns — what a contradiction costs, and what a word costs.")
    println("  Neither is about world generation. That is where the seam belongs.")
}

private fun reportWhenBlockAudit() {
    heading("THE MEASUREMENT — Aspect.kt's five `when (this)` blocks")
    for (block in AUDIT) {
        println("  %-20s %-10s %s".format(block.name, if (block.survives) "STAYS" else "COLLAPSES", block.verdict))
    }
    val collapsed = AUDIT.count { !it.survives }
    println()
    println("  $collapsed of ${AUDIT.size} collapse. The survivor stops being a switch and becomes a field,")
    println("  so the count of `when (this)` blocks in the aspect layer goes from ${AUDIT.size} to 0.")
    println()
    println("  Cost not measured here: AgeComposition is what persists, so its codec, its toString/parse")
    println("  round trip and RecipeCheck all become per-kind. CLAUDE.md's standing ruling is that")
    println("  changing codec shapes is free until first release — which is an argument about timing.")
}

private fun heading(title: String) {
    println()
    println(title)
    println("-".repeat(title.length))
}
