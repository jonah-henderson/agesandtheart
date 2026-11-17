package co.voik.agesandtheart.imaging

import net.neoforged.neoforge.common.ModConfigSpec

/** The companion's client settings: how a projector's picture is drawn on this client, and how much it keeps on disk. */
object ImagingClientLook {

    /** How a purpose-built light throws its picture: as light, or as a decal. */
    enum class PerfectThrow { LIGHT, DECAL }

    val perfectThrow: ModConfigSpec.EnumValue<PerfectThrow>

    /** How many megabytes of pictures this client keeps on disk ([co.voik.agesandtheart.imaging.client.PictureTextures]). */
    val cacheMegabytes: ModConfigSpec.IntValue

    val SPEC: ModConfigSpec

    init {
        val builder = ModConfigSpec.Builder()
        builder.comment("Projectors").translation(translationOf(PROJECTORS)).push(PROJECTORS)
        perfectThrow = builder
            .comment(
                "How a projector lit by a lantern, a copper bulb or a redstone lamp throws its picture. LIGHT adds it",
                "to the light already on the wall, as a real projector does: it shows best in a dark room on a pale",
                "wall, and washes out in daylight. DECAL lays it on the wall as it is, clear however the room is lit.",
                "Every other light throws as light either way.",
            )
            .translation(translationOf("perfect_throw"))
            .defineEnum("perfectThrow", PerfectThrow.LIGHT)
        builder.pop()
        builder.comment("Pictures").translation(translationOf(PICTURES)).push(PICTURES)
        cacheMegabytes = builder
            .comment(
                "Megabytes of pictures this game keeps on disk, from every server, so a picture seen before need not",
                "be sent again. Past it, the pictures seen least lately are let go, and sent again if they are wanted.",
                "0 keeps every picture.",
            )
            .translation(translationOf("cache_megabytes"))
            .defineInRange("cacheMegabytes", DEFAULT_MEGABYTES, 0, MOST_MEGABYTES)
        builder.pop()
        SPEC = builder.build()
    }

    private fun translationOf(option: String): String = "config.${Imaging.MOD_ID}.$option"

    private const val PROJECTORS = "projectors"
    private const val PICTURES = "pictures"
    private const val DEFAULT_MEGABYTES = 256
    private const val MOST_MEGABYTES = 1_000_000
}
