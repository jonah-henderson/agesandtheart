package co.voik.agesandtheart.imaging

import net.neoforged.neoforge.common.ModConfigSpec

/** The companion's server settings, a spec each loader hands to its own config system as Ages and the Art's is. */
object ImagingConfig {

    /**
     * Whether a picture's taken photograph is shown to everyone, or only to its photographer. Off, everyone else sees
     * the server's own sketch of the same view ([Sketches]), which no client had a hand in.
     */
    val sharesPictures: ModConfigSpec.BooleanValue

    /** How many megabytes a world's pictures may take before the least lately seen shrink ([PictureSweep]); nought is no limit. */
    val picturesMayTakeMegabytes: ModConfigSpec.IntValue

    val SPEC: ModConfigSpec

    init {
        val builder = ModConfigSpec.Builder()
        builder.comment("Pictures").translation(translationOf(PICTURES)).push(PICTURES)
        sharesPictures = builder
            .comment(
                "Show the pictures players take to everyone. A picture is the photographer's own screenshot, sent",
                "up by their game, so a modified game could send anything at all. Turn this off and only the",
                "photographer sees their photograph; everyone else sees the server's own map-like sketch of the",
                "same view, which no player's game had a hand in.",
            )
            .translation(translationOf("shares_pictures"))
            .define("sharesPictures", true)
        picturesMayTakeMegabytes = builder
            .comment(
                "Megabytes this world's pictures may take on disk. Past it, the pictures seen least lately keep only",
                "a thumbnail, about a twentieth of their size, until they fit again; none seen in the last day is",
                "touched. Seen means fetched by anyone, hung on a wall or set in a carousel that loads, or carried by",
                "a player. 0 keeps every picture whole however much they take.",
            )
            .translation(translationOf("pictures_may_take_megabytes"))
            .defineInRange("picturesMayTakeMegabytes", DEFAULT_MEGABYTES, 0, MOST_MEGABYTES)
        builder.pop()
        SPEC = builder.build()
    }

    private fun translationOf(option: String): String = "config.${Imaging.MOD_ID}.$option"

    private const val PICTURES = "pictures"
    private const val DEFAULT_MEGABYTES = 256
    private const val MOST_MEGABYTES = 1_000_000
}
