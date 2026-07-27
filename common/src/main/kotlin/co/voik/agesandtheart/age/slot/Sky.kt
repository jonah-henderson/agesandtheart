package co.voik.agesandtheart.age.slot

import co.voik.agesandtheart.age.AgeGeneration
import net.minecraft.resources.ResourceLocation

/**
 * What is overhead.
 *
 * The thinnest slot, and deliberately shipped anyway: sky was the one thing already being *inferred*
 * from the landform (Spire worlds got the custom sky because they were Spire worlds), and inference is
 * exactly what slots exist to replace. Naming it makes "hills under a stormy Spire sky" writable, and
 * it is the slot most likely to grow parameters — moons, colour, stars are all §3.1 sky business.
 *
 * A sky is a dimension type, because that is where Minecraft keeps this: the client watches the type's
 * `effects` id and attaches our renderers when it matches.
 */
enum class Sky(override val key: String, val dimensionType: ResourceLocation) : SlotPreset {
    /** An ordinary sky — vanilla's own effects, sun and clouds. */
    PLAIN("plain", AgeGeneration.AGE_PLAIN_DIMENSION_TYPE),

    /** The custom Age sky: steel-grey haze, two roiling cloud decks, stars only above them. */
    STORM("storm", AgeGeneration.AGE_DIMENSION_TYPE),
    ;

    override val slot = Slot.SKY

    override fun getSerializedName(): String = key
}
