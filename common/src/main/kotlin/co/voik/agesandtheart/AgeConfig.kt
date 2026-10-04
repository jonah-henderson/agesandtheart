package co.voik.agesandtheart

import net.neoforged.neoforge.common.ModConfigSpec

/**
 * What a server may change about the mod, as a spec the game's own config screens can read.
 *
 * **NeoForge's `ModConfigSpec`, and on Fabric the same classes from Forge Config API Port**
 * (`notes/config-research.md`). Config screens work one of two ways — they introspect a spec they already
 * know, or they host a screen the mod writes — and only the first is what compatibility means. This spec
 * is rendered natively by NeoForge, turned into a screen by Configured on every loader, and indexed by Mod
 * Menu's option search, none of which costs a line of interface code here.
 *
 * Written once in `common` rather than twice behind [co.voik.agesandtheart.platform.Services]. The port
 * publishes its API under the original package names for exactly this, and there is nothing
 * platform-divergent about a boolean: restating each option per loader would be two files holding one
 * truth, which is the split the SPI exists to prevent elsewhere rather than to create.
 *
 * **`SERVER` is the type, and it is doing real work.** It is loaded on both sides, **overridable per
 * world**, and synced to clients — so a setting can differ between one save and the next without a player
 * carrying the last world's answer into this one. Anything visual would want `CLIENT` and its own spec.
 */
object AgeConfig {

    /**
     * Whether a background sweep may delete Ages nothing can reach any more (design §9, "Losing the
     * books").
     *
     * **Off, and it has to be.** Whether every book pointing at an Age is gone cannot be *observed* — no
     * event catches every way an item stops existing, and copies cannot be enumerated — so a sweep can only
     * ever be probably right. A server with the disk to spare should not spend correctness it does not
     * need, and one that is short of disk can decide that for itself.
     *
     * The comment is worded as what it is rather than as what it is for, because the screen shows it to
     * somebody who has not read any of this.
     */
    val collectsUnreachableAges: ModConfigSpec.BooleanValue

    /**
     * Whether a villager will work a writer's desk (the plan's "can be done any time" list).
     *
     * Read **live** by both of the profession's job-site predicates rather than gating registration,
     * which is not a thing a frozen registry allows: the profession exists on every server, and this
     * decides whether anyone may hold it. That is also what makes turning it off take effect at once —
     * `heldJobSite` stops matching, so writers already at a desk give it up.
     */
    val villagerWriters: ModConfigSpec.BooleanValue

    /**
     * Whether linking hunts outward from an Age's origin for ground above the waterline.
     *
     * The search is the most expensive thing a first link does — every candidate column is a full run of
     * the generator's density functions — and a bound book's panel now shows where it puts you, so a
     * server can decide that arriving where the Age happens to put you is the visitor's problem to prepare
     * for.
     */
    val searchesForFooting: ModConfigSpec.BooleanValue

    /**
     * When an Age's terrain is made ready, which decides who waits for it.
     *
     * The first look at an Age generates its ring from nothing and costs tens of seconds; every look after
     * that loads the same chunks from disk in a tenth of a second. So the question is never how fast that
     * is, only who is sitting through it.
     */
    val warmAgesWhen: ModConfigSpec.EnumValue<WarmAgesWhen>

    /**
     * Whether the fusion-compounder makes what nothing else in the game can — one switch a recipe, named by
     * the recipe's `allowed_by` (design §7.1.2). Read live by the recipe, so turning one off takes effect at
     * once. Bedrock most of all: a player who can place it can build what nobody else can break.
     */
    val compoundsBedrock: ModConfigSpec.BooleanValue
    val compoundsReinforcedDeepslate: ModConfigSpec.BooleanValue
    val compoundsBuddingAmethyst: ModConfigSpec.BooleanValue
    val compoundsHeavyCore: ModConfigSpec.BooleanValue

    /**
     * Whether the drying rack turns rotten flesh into leather. Read live by the recipe, so turning it off
     * takes effect at once, and the recipe is then missing from the rack's list too.
     */
    val driesLeather: ModConfigSpec.BooleanValue

    /**
     * Whether plasma let loose destroys the blocks it erupts through and bursts beside (design §7.1.2). Off,
     * it still erupts through the air, burns what it touches and hurts with its bursts, but leaves every
     * block standing: a released container is otherwise a block-deleter in a player's hands.
     */
    val plasmaAnnihilates: ModConfigSpec.BooleanValue

    /** The spec each loader hands to its own config system. */
    val SPEC: ModConfigSpec

    init {
        val builder = ModConfigSpec.Builder()
        builder.comment("Housekeeping").push(HOUSEKEEPING)
        collectsUnreachableAges = builder
            .comment(
                "Delete Ages that nothing can reach any more — every book pointing at one destroyed,",
                "and nobody inside. POTENTIAL DATA LOSS: whether every book is really gone cannot be",
                "known for certain, so an Age may be collected while a book for it survives somewhere.",
                "What is lost is whatever was built there; the Age itself is rebuilt from the book.",
            )
            .translation(translationOf("collects_unreachable_ages"))
            .define("collectsUnreachableAges", false)
        builder.pop()
        builder.comment("Villagers").push(VILLAGERS)
        villagerWriters = builder
            .comment(
                "Let a villager take up a writer's desk as a job site, and trade pages, papers, inks and",
                "written Descriptive Books. Turn this off for a world where the Art is found rather than",
                "bought. TAKES EFFECT AT ONCE: villagers already working a desk lose the job and go back",
                "to being unemployed, keeping neither their trades nor their level.",
            )
            .translation(translationOf("villager_writers"))
            .define("villagerWriters", true)
        builder.pop()
        builder.comment("Linking").push(LINKING)
        searchesForFooting = builder
            .comment(
                "Look outward from an Age's origin for dry land to arrive on, instead of arriving at the",
                "origin itself. The search is the slowest part of opening an Age for the first time, and a",
                "bound book's panel shows you where you would land either way. Turn this off for a faster",
                "first link, at the cost of arriving in whatever is at the origin — water, a cave, or the",
                "open air.",
            )
            .translation(translationOf("searches_for_footing"))
            .define("searchesForFooting", true)
        warmAgesWhen = builder
            .comment(
                "When to generate the terrain a book's panel shows. BOUND does it the moment the book's",
                "Age is decided — as it is bound at a desk, or as a found one writes itself — so the panel",
                "is ready long before anybody opens it. HELD waits until the book is in hand. OPENED does",
                "not prepare anything, and the first person to open each book waits out the whole of its",
                "Age being made.",
                "Preparing early means an Age exists, and takes up room, from the moment its book does.",
            )
            .translation(translationOf("warm_ages_when"))
            .defineEnum("warmAgesWhen", WarmAgesWhen.BOUND)
        builder.pop()
        builder.comment("Compounding").push(COMPOUNDING)
        compoundsBedrock = builder
            .comment(
                "Let the D'ni fusion compounder press 64 blocks of netherite into bedrock. A player with",
                "bedrock can build what no other player can break.",
            )
            .translation(translationOf("compounds_bedrock"))
            .define("compoundsBedrock", true)
        compoundsReinforcedDeepslate = builder
            .comment("Let the compounder make reinforced deepslate, which nothing else can.")
            .translation(translationOf("compounds_reinforced_deepslate"))
            .define("compoundsReinforcedDeepslate", true)
        compoundsBuddingAmethyst = builder
            .comment("Let the compounder make budding amethyst, which nothing else can.")
            .translation(translationOf("compounds_budding_amethyst"))
            .define("compoundsBuddingAmethyst", true)
        compoundsHeavyCore = builder
            .comment("Let the compounder make a heavy core, which is otherwise found only in ominous vaults.")
            .translation(translationOf("compounds_heavy_core"))
            .define("compoundsHeavyCore", true)
        builder.pop()
        builder.comment("Drying").push(DRYING)
        driesLeather = builder
            .comment(
                "Let the drying rack turn rotten flesh into leather. An early, endless supply of leather",
                "changes what leather is worth from the first night on.",
            )
            .translation(translationOf("dries_leather"))
            .define("driesLeather", true)
        builder.pop()
        builder.comment("Plasma").push(PLASMA)
        plasmaAnnihilates = builder
            .comment(
                "Let plasma let loose from a broken container destroy the blocks it erupts through and bursts",
                "beside. Off, it still burns what it touches and its bursts still hurt, but every block stands.",
            )
            .translation(translationOf("plasma_annihilates"))
            .define("plasmaAnnihilates", true)
        builder.pop()
        SPEC = builder.build()
    }

    /** Where a screen looks for an option's name, the generated screens reading these rather than the key. */
    private fun translationOf(option: String): String = "config.${Constants.MOD_ID}.$option"

    private const val HOUSEKEEPING = "housekeeping"

    private const val VILLAGERS = "villagers"

    private const val LINKING = "linking"

    private const val COMPOUNDING = "compounding"
    private const val DRYING = "drying"

    private const val PLASMA = "plasma"
}

/**
 * The moment an Age's terrain is generated, before anybody is waiting on it.
 *
 * Every value names a moment that has already happened by the time a panel is opened, except the last,
 * which names the panel itself.
 */
enum class WarmAgesWhen {
    /**
     * The moment the book's Age is decided: as it is bound at a desk, or as a found one writes itself.
     *
     * Earlier than anything else can be, because until a book is bound it describes no world at all.
     */
    BOUND,

    /** Only once a bound book is in hand. */
    HELD,

    /** Not until the panel asks, which is where the whole cost lands on the person who opened it. */
    OPENED,
}
