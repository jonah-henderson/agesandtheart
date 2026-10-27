package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder

/**
 * The slow drivers an Age's land is laid out by, as vanilla lays its biomes out by climate: how far the ground is
 * pushed up, how old and worn it is, how wet, and how warm. Each runs from about −1 to 1 and changes over a
 * kilometre or more.
 *
 * Every landform has a home among the first three ([Home]), and a territory goes to the landform whose home is
 * nearest the drivers there, so neighbours are plausible: mountains give way to foothills, dry tables to canyons.
 * The dials read them too — the drift follows uplift and the wear follows age — and a grounded Age's biomes take
 * their temperature and humidity from the last two, so dry canyon country grows dry biomes.
 */
data class TerrainTable(val seed: Long) {
    private val uplift = fieldNoise(seed xor UPLIFT_SALT, UPLIFT_OCTAVE, UPLIFT_AMPLITUDES)
    private val age = fieldNoise(seed xor AGE_SALT, AGE_OCTAVE, AGE_AMPLITUDES)
    private val wetness = fieldNoise(seed xor WETNESS_SALT, WETNESS_OCTAVE, WETNESS_AMPLITUDES)
    private val showers = fieldNoise(seed xor SHOWERS_SALT, SHOWERS_OCTAVE, SHOWERS_AMPLITUDES)
    private val temperature = fieldNoise(seed xor TEMPERATURE_SALT, TEMPERATURE_OCTAVE, TEMPERATURE_AMPLITUDES)

    /** How far the ground is pushed up: sea and basins low, plateaus and ranges high. */
    fun upliftAt(worldX: Double, worldZ: Double): Double = uplift.get(worldX, 0.0, worldZ).toDouble()

    /** How old the land is: young and sharp low, old and worn high. */
    fun ageAt(worldX: Double, worldZ: Double): Double = age.get(worldX, 0.0, worldZ).toDouble()

    /** How wet the country is: arid low, wet high. */
    fun wetnessAt(worldX: Double, worldZ: Double): Double = wetness.get(worldX, 0.0, worldZ).toDouble()

    /**
     * How wet the biomes here are, on vanilla's humidity axis: the country's wetness, with vanilla's own finer
     * variation over it so a forest still gives way to a plain within wet country.
     */
    fun humidityAt(worldX: Double, worldZ: Double): Double =
        wetnessAt(worldX, worldZ) * COUNTRY_SHARE + showers.get(worldX, 0.0, worldZ).toDouble() * (1.0 - COUNTRY_SHARE)

    /** How warm it is, on vanilla's temperature axis; only the biomes read it. */
    fun temperatureAt(worldX: Double, worldZ: Double): Double = temperature.get(worldX, 0.0, worldZ).toDouble()

    /** Where the three drivers a landform lives by stand here. */
    fun placeAt(worldX: Double, worldZ: Double) = Home(upliftAt(worldX, worldZ), ageAt(worldX, worldZ), wetnessAt(worldX, worldZ))

    /** A landform's home in the table, or the drivers at a place: the same three numbers. */
    data class Home(val uplift: Double, val age: Double, val wetness: Double) {
        fun distanceSquaredTo(other: Home): Double {
            val acrossUplift = uplift - other.uplift
            val acrossAge = age - other.age
            val acrossWetness = wetness - other.wetness
            return acrossUplift * acrossUplift + acrossAge * acrossAge + acrossWetness * acrossWetness
        }

        companion object {
            val CODEC: Codec<Home> = RecordCodecBuilder.create { instance ->
                instance.group(
                    Codec.DOUBLE.fieldOf("uplift").forGetter(Home::uplift),
                    Codec.DOUBLE.fieldOf("age").forGetter(Home::age),
                    Codec.DOUBLE.fieldOf("wetness").forGetter(Home::wetness),
                ).apply(instance, ::Home)
            }
        }
    }

    companion object {
        // Uplift and age about two kilometres to a swing, with half and a quarter of that on top.
        private const val UPLIFT_OCTAVE = -11
        private val UPLIFT_AMPLITUDES = listOf(1.0, 0.5, 0.25)
        private const val AGE_OCTAVE = -11
        private val AGE_AMPLITUDES = listOf(1.0, 0.5)

        // Vanilla's own temperature noise, so biomes keep the grain they have in the overworld. Wetness lays out land,
        // so it runs a kilometre or two to a swing; the biomes add vanilla's own vegetation noise over it.
        private const val TEMPERATURE_OCTAVE = -10
        private val TEMPERATURE_AMPLITUDES = listOf(1.5, 0.0, 1.0, 0.0, 0.0, 0.0)
        private const val WETNESS_OCTAVE = -11
        private val WETNESS_AMPLITUDES = listOf(1.0, 0.5)
        private const val SHOWERS_OCTAVE = -8
        private val SHOWERS_AMPLITUDES = listOf(1.0, 1.0, 0.0, 0.0, 0.0, 0.0)

        /** How much of a biome's humidity is the country's wetness rather than vanilla's own variation. */
        private const val COUNTRY_SHARE = 0.6
        private const val SHOWERS_SALT = 0x05A0_3E25L

        private const val UPLIFT_SALT = 0x0B11_F7L
        private const val AGE_SALT = 0x0A6E_D0L
        private const val WETNESS_SALT = 0x0E7_4E55L
        private const val TEMPERATURE_SALT = 0x07E_3A7EL

        val CODEC: Codec<TerrainTable> = Codec.LONG.xmap(::TerrainTable, TerrainTable::seed)
    }
}
