package co.voik.agesandtheart.age.aspect

/**
 * What is **built into the rock** beneath the surface — as against [Carvers], which is what has been cut
 * back out of it afterwards.
 *
 * The two are one stage of Minecraft's own generation each, and vanilla runs both. These are *fields*:
 * they are composed into the shape before a block is placed, which is what lets one of them declare
 * itself dry, claim a band that counts as indoors, and be part of the landform rather than an erosion of
 * it. A carver can do none of those — it subtracts a configured shape from rock that already exists.
 *
 * **Only a landform with vertical room carries one** ([Terrain.undergroundCeiling]). One that has none
 * ignores whatever was asked for, the way a preset ignores a material it cannot be made of.
 */
enum class Underground(override val key: String) : AuthoredPreset {

    /** Nothing cut into it: whatever the shape laid down stays there, all the way to the bedrock. */
    NONE("none"),

    /**
     * **Minecraft's own noise caves** — the cheese chambers, the spaghetti tunnels, the entrances that
     * open onto a hillside. Vanilla's density-function cave stack, transcribed in
     * [co.voik.agesandtheart.worldgen.field.Caved], and the ordinary answer for a world with rock under it.
     */
    NOISE_CAVES("noise_caves"),

    /**
     * Storey upon storey of pillared hall.
     *
     * A value here rather than a landform of its own, so that what stands *over* the halls can be any
     * world at all. Never wet, and the one underground that counts as indoors.
     */
    GREAT_HALLS("great_halls"),

    ;

    override val aspect = Aspect.UNDERGROUND

    override fun getSerializedName(): String = key
}
