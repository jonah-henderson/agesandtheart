package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.worldgen.Chambers
import co.voik.agesandtheart.worldgen.GreatHalls
import co.voik.agesandtheart.worldgen.VerticalWindow
import co.voik.agesandtheart.worldgen.field.Caved
import co.voik.agesandtheart.worldgen.field.Subtract
import co.voik.agesandtheart.worldgen.field.TerrainField

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

    /**
     * This underground cut into [uncut], between [floor] and [ceiling].
     *
     * [ceiling] is non-null because only a landform with room under it carries an underground at all, and
     * the caller has already established that.
     */
    fun carve(
        uncut: TerrainField,
        floor: Int,
        ceiling: Int,
        window: VerticalWindow,
        options: Options,
        salt: Long,
    ): Terrain.Ground = when (this) {
        NOISE_CAVES -> Terrain.Ground(
            Caved.of(uncut, CAVE_SEED xor salt, floor, window.topY),
            // A carved cave meets the water table on its way out of the rock, so it answers to one.
            hollows = uncut,
        )
        GREAT_HALLS -> {
            // A ceiling has to be named here, unlike [NOISE_CAVES] where the band is the whole world —
            // `Caved` only ever walks rock the base actually has and its own entrance rule keeps the cut
            // away from the surface, so naming a ceiling there would be a second, worse copy of a decision
            // the node already makes better. A slab of halls has no such rule and would happily open onto
            // a hillside.
            val halls = GreatHalls.voidBetween(floor, ceiling, HALL_SEED xor salt)
            Terrain.Ground(Subtract(uncut, halls), dry = halls)
        }
        // **Dry *and* wet**, which is not a contradiction: the vaults are kept out of the Age's own
        // flat fill outright, and the lake standing in each is put back by the field that knows where
        // its own water line is. Handing them to a water table instead would stand a flooded bay
        // against a dry one with nothing between, which is what `GreatHallsWaterCheck` records.
        CHAMBERED -> {
            val size = options.steer(Terrain.SIZE, salt)
            val vaults = Chambers.voidBetween(floor, ceiling, size, CHAMBER_SEED xor salt)
            Terrain.Ground(
                Subtract(uncut, vaults),
                dry = vaults,
                wet = Chambers.lakesIn(floor, ceiling, size, CHAMBER_SEED xor salt),
            )
        }
        NONE -> Terrain.Ground(uncut)
    }

    /**
     * The band this underground is **indoors** in, or null where it claims none — see
     * [co.voik.agesandtheart.worldgen.biome.BiomeBand].
     *
     * Only [GREAT_HALLS] claims one. Noise caves are not indoors in this sense: they are open to the
     * surface by design, they belong to the country they were cut into, and vanilla's own cave biomes
     * describe them exactly.
     *
     * **[CHAMBERED] is enclosed and still does not claim one**, which is a decision rather than an omission
     * (Jonah, 2026-09-08). A band here overrides the climate table with one fixed biome, and what a vault
     * wants is the opposite: the cave biomes are what carry the lush growth the algae rides on, and pinning
     * every chamber to a hall's biome would take its features and its mob list with it. A hall is
     * somebody's architecture and reads as one room however far it runs; a chamber is a place, and places
     * are what biomes are for.
     */
    fun indoorBand(floor: Int, ceiling: Int): IntRange? = if (this == GREAT_HALLS) floor..ceiling else null

    override fun getSerializedName(): String = key

    companion object {
        // So an Age's caves are its own, and decorrelated from the rock they are cut into.
        private const val CAVE_SEED = 0xCA_7E5L

        // And its halls likewise, decorrelated from both.
        private const val HALL_SEED = 0x4A_115L

        // And its chambers, so a world's vaults are not laid where its caves were.
        private const val CHAMBER_SEED = 0x0C_4A_9BEL
    }
}
