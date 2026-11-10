package co.voik.agesandtheart.content

import net.minecraft.util.StringRepresentable

/** A wood something of ours is built of, as its recipe's planks and slab were; what takes it is drawn in that wood. */
enum class WoodKind(private val serializedName: String) : StringRepresentable {
    OAK("oak"),
    SPRUCE("spruce"),
    BIRCH("birch"),
    JUNGLE("jungle"),
    ACACIA("acacia"),
    DARK_OAK("dark_oak"),
    MANGROVE("mangrove"),
    CHERRY("cherry"),
    PALE_OAK("pale_oak"),
    BAMBOO("bamboo"),
    CRIMSON("crimson"),
    WARPED("warped"),
    POPLAR("poplar"),
    PALM("palm"),
    ;

    override fun getSerializedName(): String = serializedName
}
