package co.voik.agesandtheart

import co.voik.ephemeris.sky.Aurora
import net.neoforged.neoforge.common.ModConfigSpec

/**
 * What a *player* may change about how their own machine draws an Age.
 *
 * **A second spec, and `CLIENT` rather than `SERVER`, because it answers a different question.**
 * [AgeConfig] is what a server decides for everybody in a world and is synced as such; nothing here leaves
 * the machine it is set on, and nothing here changes what an Age *is* — two players standing in the same
 * Age may see it drawn differently and neither is wrong.
 *
 * **The decision is ours, not Ephemeris'.** The library draws exactly the curtains it is told to, and if it
 * is told six that is because this mod said six. It cannot know what that costs on somebody's machine, and
 * it has no business guessing — where *we* said "occasionally you find worlds with aurorae", so we own both
 * how that is drawn and the frame rate it asks for. Offering the lever is part of making the promise
 * (Jonah, 2026-08-30).
 */
object AgeClientLook {

    /**
     * How many curtains of an aurora this machine will draw at once.
     *
     * Each is a pass over a sheet whose every fragment samples several fields of noise, so this is the one
     * number in the mod with a frame rate attached to it. **Nought is a real answer** — an aurora is worth
     * looking at and nothing depends on seeing one, so a machine that would rather spend the frames
     * elsewhere should be able to say so outright rather than being left to endure it.
     *
     * The comment is worded for somebody reading a settings screen who has read none of this.
     */
    val auroraCurtains: ModConfigSpec.IntValue

    /** The spec each loader hands to its own config system. */
    val SPEC: ModConfigSpec

    init {
        val builder = ModConfigSpec.Builder()
        builder.comment("How much this machine draws").push(DRAWING)
        auroraCurtains = builder
            .comment(
                "How many curtains of light an aurora may hang at once. Each one costs frames, so turn",
                "this down on a machine that struggles — or to 0 to leave aurorae undrawn entirely.",
                "Affects only what you see; other players in the same Age are unchanged.",
            )
            .translation(translationOf("aurora_curtains"))
            .defineInRange("auroraCurtains", Aurora.MOST_CURTAINS, NONE, Aurora.MOST_CURTAINS)
        builder.pop()
        SPEC = builder.build()
    }

    /** Where a screen looks for an option's name, the generated screens reading these rather than the key. */
    private fun translationOf(option: String): String = "config.${Constants.MOD_ID}.$option"

    private const val DRAWING = "drawing"

    private const val NONE = 0
}
