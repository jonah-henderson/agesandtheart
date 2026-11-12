package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.NoiseCharacter
import co.voik.agesandtheart.worldgen.field.Noise3D
import co.voik.agesandtheart.worldgen.field.TerrainField
import co.voik.agesandtheart.worldgen.field.fieldNoise

/**
 * The land holed through above the ground — `porous landmass`: great round holes through hills, cliffs and ridges,
 * whatever landform stands there, from a few blocks over the sea up. Hollowed out of the land by the [Overlay], so a
 * landform of ours and vanilla's own rock are cut alike, and the height structures stand by is the holed land's.
 */
object PorousLand {

    /** The holes [porosity] asks for, as rock to take out, or null where it asks for none. */
    fun holes(porosity: Double, seed: Long): TerrainField? {
        if (porosity <= 0.0) return null
        val salted = seed xor HOLES_SALT
        val noise = fieldNoise(salted, FIRST_OCTAVE, AMPLITUDES)
        val threshold = Perforation.thresholdLeaving(porosity) { x, y, z -> noise.get(x, y, z).toDouble() }
        return Noise3D(salted, FIRST_OCTAVE, AMPLITUDES, SCALE, SCALE, SCALE, NoiseCharacter.PLAIN, threshold, HOLED_FROM, HOLED_TO)
    }

    /** The sea a landform stands at, and the holes start a little over it so none lets the sea in. */
    private const val SEA_LEVEL = 63
    private const val HOLED_FROM = SEA_LEVEL + 3
    private const val HOLED_TO = 255

    /** The scale that makes a hole about sixteen blocks across. */
    private const val SCALE = 20.0
    private const val FIRST_OCTAVE = 0
    private val AMPLITUDES = listOf(1.0)
    private const val HOLES_SALT = 0x4C41_4E44_484FL
}
