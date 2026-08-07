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
        SPEC = builder.build()
    }

    /** Where a screen looks for an option's name, the generated screens reading these rather than the key. */
    private fun translationOf(option: String): String = "config.${Constants.MOD_ID}.$option"

    private const val HOUSEKEEPING = "housekeeping"
}
