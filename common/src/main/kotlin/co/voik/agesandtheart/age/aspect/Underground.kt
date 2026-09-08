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

    /**
     * Great vaults with a lake in the bottom of each — the counterpart to [GREAT_HALLS], one being a thing
     * somebody made and this a thing that happened.
     *
     * **The only underground that carries its own water**, and it has to: every landform's underground
     * ceiling sits under its waterline, so a chamber filled by the Age's sea is a drowned one everywhere.
     * The lake is the chambers' own level rather than the world's, which is what lets this be written
     * beneath any shape with room for it. See [co.voik.agesandtheart.worldgen.Chambers].
     */
    CHAMBERED("chambered"),

    ;

    override val aspect = Aspect.UNDERGROUND

    /**
     * **A vault is a thing with a name**, so the page that means it is minted here the way a landform's is
     * — `chambered underground` rather than a hopeful pile of adjectives. The other three stay unnamed and
     * are reached by what they are like, there being nothing to a hollow rock but its quality.
     */
    override val writtenWordFor: String? get() = key.takeIf { this == CHAMBERED }

    /**
     * How big a chamber is — the axis every other size in the language is said on, so `colossal chambered
     * underground` is the vault a city fits in and nothing below it is.
     */
    override val parameters: List<Parameter>
        get() = listOfNotNull(Terrain.SIZE.takeIf { this == CHAMBERED })

    override fun getSerializedName(): String = key
}
