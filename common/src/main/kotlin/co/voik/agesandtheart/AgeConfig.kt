package co.voik.agesandtheart

import net.neoforged.neoforge.common.ModConfigSpec

/**
 * What a server may change about the mod, as a spec the game's own config screens can read.
 *
 * **NeoForge's `ModConfigSpec`, and on Fabric the same classes from Forge Config API Port**
 * (`notes/decisions.md`, "Config"). Config screens work one of two ways — they introspect a spec they already
 * know, or they host a screen the mod writes — and only the first is what compatibility means. This spec
 * is shown by Cloth Config's screen where it is installed ([co.voik.agesandtheart.client.settings.ClothSettings]) and by
 * NeoForge's own `ConfigurationScreen` where it is not — the Mods page's Config button on NeoForge, Mod Menu's on
 * Fabric — each showing whatever is defined here with no further code, as any other tool that reads a spec can.
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
     * Whether the server, as it starts, scans the whole save for books and deletes the Ages none can reach (design §9,
     * "Losing the books"). **Not read yet**: the sweep on boot is unbuilt (`notes/remaining-work.md`), so it does
     * nothing either way until it is.
     */
    val cleansUpAgesOnBoot: ModConfigSpec.BooleanValue

    /**
     * Whether a villager will work a writer's desk (the plan's "can be done any time" list).
     *
     * Read **live** by both of the profession's job-site predicates rather than gating registration,
     * which is not a thing a frozen registry allows: the profession exists on every server, and this
     * decides whether anyone may hold it. That is also what makes turning it off take effect at once —
     * `heldJobSite` stops matching, so writers already at a desk give it up.
     */
    val writerProfession: ModConfigSpec.BooleanValue

    /**
     * Whether linking hunts outward from an Age's origin for ground above the waterline.
     *
     * The search is the most expensive thing a first link does — every candidate column is a full run of
     * the generator's density functions — and a bound book's panel now shows where it puts you, so a
     * server can decide that arriving where the Age happens to put you is the visitor's problem to prepare
     * for.
     */
    val searchForSolidGround: ModConfigSpec.BooleanValue

    /**
     * When an Age's terrain is made ready, which decides who waits for it.
     *
     * The first look at an Age generates its ring from nothing and costs tens of seconds; every look after
     * that loads the same chunks from disk in a tenth of a second. So the question is never how fast that
     * is, only who is sitting through it.
     */
    val agePreload: ModConfigSpec.EnumValue<WarmAgesWhen>

    /** What a bound book's panel shows: the Age live, a map of where it would put you, or nothing. */
    val panelMode: ModConfigSpec.EnumValue<PanelView>

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
     * The four that change the balance most, off unless a server turns them on (Jonah): an end portal frame, so an End
     * gateway anywhere, ancient debris, ice and quartz.
     */
    val compoundsEndPortalFrame: ModConfigSpec.BooleanValue
    val compoundsAncientDebris: ModConfigSpec.BooleanValue
    val compoundsIce: ModConfigSpec.BooleanValue
    val compoundsQuartz: ModConfigSpec.BooleanValue

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

    /**
     * How many phenomena may be running at once, and over what — see
     * [co.voik.agesandtheart.age.phenomena.PhenomenaCeiling]. Read live.
     */
    val maxPhenomena: ModConfigSpec.IntValue
    val phenomenaLimitScope: ModConfigSpec.EnumValue<CeilingCovers>

    /** How hard a gale or a blizzard pushes whatever is out in it, as a share of the most it may — see `Gale.pushStrength`. */
    val windStrength: ModConfigSpec.DoubleValue
    val tornadoesDestroyBlocks: ModConfigSpec.BooleanValue

    /** Whether a player holding a coconut half in each hand walks to the sound of hooves. */
    val coconutHoofbeats: ModConfigSpec.BooleanValue

    /** The spec each loader hands to its own config system. */
    val SPEC: ModConfigSpec

    init {
        val builder = ModConfigSpec.Builder()
        builder.comment("Age save data").translation(translationOf(AGE_SAVE_DATA)).push(AGE_SAVE_DATA)
        cleansUpAgesOnBoot = builder
            .comment(
                "When the server starts, scan the whole save for linking books and delete the Ages none can",
                "reach any more. POTENTIAL DATA LOSS: what was built in a deleted Age is gone for good,",
                "though a book for it that turns up later rebuilds the Age itself, empty.",
            )
            .translation(translationOf("cleans_up_ages_on_boot"))
            .define("cleansUpAgesOnBoot", false)
        builder.pop()
        builder.comment("Villagers").translation(translationOf(VILLAGERS)).push(VILLAGERS)
        writerProfession = builder
            .comment(
                "Let a villager take up a writer's desk as a job site, and trade pages, papers, inks and",
                "written Descriptive Books. Turn this off for a world where the Art is found rather than",
                "bought. TAKES EFFECT AT ONCE: villagers already working a desk lose the job and go back",
                "to being unemployed, keeping neither their trades nor their level.",
            )
            .translation(translationOf("writer_profession"))
            .define("writerProfession", true)
        builder.pop()
        builder.comment("Linking").translation(translationOf(LINKING)).push(LINKING)
        searchForSolidGround = builder
            .comment(
                "Look outward from an Age's origin for dry land to arrive on, instead of arriving at the",
                "origin itself. The search is the slowest part of opening an Age for the first time, and a",
                "bound book's panel shows you where you would land either way. Turn this off for a faster",
                "first link, at the cost of arriving in whatever is at the origin — water, a cave, or the",
                "open air.",
            )
            .translation(translationOf("search_for_solid_ground"))
            .define("searchForSolidGround", true)
        agePreload = builder
            .comment(
                "When to generate the terrain a book's panel shows. BOUND does it the moment the book's",
                "Age is decided — as it is bound at a desk, or as a found one writes itself — so the panel",
                "is ready long before anybody opens it. HELD waits until the book is in hand. OPENED does",
                "not prepare anything, and the first person to open each book waits out the whole of its",
                "Age being made.",
                "Preparing early means an Age exists, and takes up room, from the moment its book does.",
            )
            .translation(translationOf("age_preload"))
            .defineEnum("agePreload", WarmAgesWhen.ON_CREATE)
        panelMode = builder
            .comment(
                "What a bound book's panel shows. LIVE is the Age itself, moving, streamed to the player",
                "who opens it. MAP is a map of the ground around where the book would put you, read once",
                "from the Age and sent as colours, which spares the player's machine a second world and",
                "the server the chunks it would send. OFF shows the panel misted over.",
                "The Age is still made for a MAP panel; agePreload still decides when.",
            )
            .translation(translationOf("panel_mode"))
            .defineEnum("panelMode", PanelView.PREVIEW)
        builder.pop()
        builder.comment("Fusion compounder").translation(translationOf(FUSION_COMPOUNDER)).push(FUSION_COMPOUNDER)
        compoundsBedrock = builder
            .comment(
                "Let the fusion compounder press 64 blocks of netherite into bedrock. A player with",
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
        compoundsEndPortalFrame = builder
            .comment(
                "Let the compounder press 64 end stone into an end portal frame, which nothing else can make.",
                "A player with frames can open a way to the End anywhere, and a frame cannot be broken.",
            )
            .translation(translationOf("compounds_end_portal_frame"))
            .define("compoundsEndPortalFrame", false)
        compoundsAncientDebris = builder
            .comment("Let the compounder press 64 gilded blackstone into ancient debris.")
            .translation(translationOf("compounds_ancient_debris"))
            .define("compoundsAncientDebris", false)
        compoundsIce = builder
            .comment("Let the compounder press 64 snow blocks into ice, which packs into packed and blue ice as ever.")
            .translation(translationOf("compounds_ice"))
            .define("compoundsIce", false)
        compoundsQuartz = builder
            .comment("Let the compounder press 64 sandstone into a block of quartz.")
            .translation(translationOf("compounds_quartz"))
            .define("compoundsQuartz", false)
        builder.pop()
        builder.comment("Drying rack").translation(translationOf(DRYING_RACK)).push(DRYING_RACK)
        driesLeather = builder
            .comment(
                "Let the drying rack turn rotten flesh into leather. An early, endless supply of leather",
                "changes what leather is worth from the first night on.",
            )
            .translation(translationOf("dries_leather"))
            .define("driesLeather", true)
        builder.pop()
        builder.comment("Plasma").translation(translationOf(PLASMA)).push(PLASMA)
        plasmaAnnihilates = builder
            .comment(
                "Let plasma let loose from a broken container destroy the blocks it erupts through and bursts",
                "beside. Off, it still burns what it touches and its bursts still hurt, but every block stands.",
            )
            .translation(translationOf("plasma_annihilates"))
            .define("plasmaAnnihilates", true)
        builder.pop()
        builder.comment("Phenomena").translation(translationOf(PHENOMENA)).push(PHENOMENA)
        maxPhenomena = builder
            .comment(
                "How many phenomena may be running at once: sandfalls, cave-ins, meteor storms, and the",
                "storms a blizzard, deluge or tempest blows in. One that would begin past this waits its",
                "turn and begins when another ends, so nothing is lost, only delayed. 0 for no limit.",
            )
            .translation(translationOf("max_phenomena"))
            .defineInRange("maxPhenomena", DEFAULT_PHENOMENA_AT_ONCE, 0, MOST_PHENOMENA_AT_ONCE)
        phenomenaLimitScope = builder
            .comment(
                "Whether that limit is for each Age on its own (EACH_AGE), or shared by every Age on the",
                "server (WHOLE_SERVER) — the safer choice when many players are each in an Age of their own.",
            )
            .translation(translationOf("phenomena_limit_scope"))
            .defineEnum("phenomenaLimitScope", CeilingCovers.PER_AGE)
        windStrength = builder
            .comment(
                "How hard a gale or a blizzard's wind pushes players, creatures, dropped items and what is thrown,",
                "from 0 (not at all) to 1 (the most it may). An ordinary one leans; a fierce one shoves. At 1 the",
                "fiercest still lets a player sprint into it, though a jump or the edge of a bridge is another matter.",
            )
            .translation(translationOf("wind_strength"))
            .defineInRange("windStrength", DEFAULT_GALE_WIND, 0.0, 1.0)
        tornadoesDestroyBlocks = builder
            .comment(
                "Whether a tornado tears up and throws the blocks under it, built or not. Off, it moves none, but",
                "still spends its strength as though it had, so it lasts as long and goes as far.",
            )
            .translation(translationOf("tornadoes_destroy_blocks"))
            .define("tornadoesDestroyBlocks", true)
        builder.pop()
        builder.comment("Easter eggs").translation(translationOf(EASTER_EGGS)).push(EASTER_EGGS)
        coconutHoofbeats = builder
            .comment("Walking with half a coconut in each hand sounds like a horse.")
            .translation(translationOf("coconut_hoofbeats"))
            .define("coconutHoofbeats", true)
        builder.pop()
        SPEC = builder.build()
    }

    /** Where a screen looks for an option's name, the generated screens reading these rather than the key. */
    private fun translationOf(option: String): String = "config.${Constants.MOD_ID}.$option"

    private const val AGE_SAVE_DATA = "age_save_data"

    private const val VILLAGERS = "villagers"

    private const val LINKING = "linking"

    private const val FUSION_COMPOUNDER = "fusion_compounder"
    private const val DRYING_RACK = "drying_rack"

    private const val PLASMA = "plasma"

    private const val PHENOMENA = "phenomena"
    private const val DEFAULT_PHENOMENA_AT_ONCE = 2
    private const val MOST_PHENOMENA_AT_ONCE = 64
    private const val DEFAULT_GALE_WIND = 0.4

    private const val EASTER_EGGS = "easter_eggs"
}

/** What one ceiling on running phenomena is shared by. */
enum class CeilingCovers {
    PER_AGE,
    WHOLE_SERVER,
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
    ON_CREATE,

    /** Only once a bound book is in hand. */
    ON_HOLD,

    /** Not until the panel asks, which is where the whole cost lands on the person who opened it. */
    ON_OPEN,
}

/** What a bound book's panel shows. */
enum class PanelView {
    /** The Age itself, moving, as a second world on the client. */
    PREVIEW,

    /** A map of the ground around the arrival, read off the Age's chunks on the server. */
    MAP,

    /** Nothing but the mist. */
    OFF,
}
