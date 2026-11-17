package co.voik.agesandtheart.imaging

import net.neoforged.neoforge.common.ModConfigSpec

/** The companion's server settings, a spec each loader hands to its own config system as Ages and the Art's is. */
object ImagingConfig {

    /**
     * Whether a picture's taken photograph is shown to everyone, or only to its photographer. Off, everyone else sees
     * the server's own sketch of the same view ([Sketches]), which no client had a hand in.
     */
    val sharesPictures: ModConfigSpec.BooleanValue

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
        builder.pop()
        SPEC = builder.build()
    }

    private fun translationOf(option: String): String = "config.${Imaging.MOD_ID}.$option"

    private const val PICTURES = "pictures"
}
