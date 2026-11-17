package co.voik.agesandtheart.imaging

import net.neoforged.neoforge.common.ModConfigSpec

/** The companion's client settings: how a projector's picture is drawn on this client. */
object ImagingClientLook {

    /** How a purpose-built light throws its picture: as light, or as a decal. */
    enum class PerfectThrow { LIGHT, DECAL }

    val perfectThrow: ModConfigSpec.EnumValue<PerfectThrow>

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
        SPEC = builder.build()
    }

    private fun translationOf(option: String): String = "config.${Imaging.MOD_ID}.$option"

    private const val PROJECTORS = "projectors"
}
