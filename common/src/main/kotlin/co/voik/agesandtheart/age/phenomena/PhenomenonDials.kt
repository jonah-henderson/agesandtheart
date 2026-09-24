package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.age.Manifestation
import co.voik.agesandtheart.age.Spending

/**
 * How far an Age's instability reached into each of a meteor storm's dials, each from nothing to all of it
 * (design §5.2). Read off the [Spending] rather than passed as one fury, so each dial moves only what it
 * names: [often] how often a storm gathers, [long] how long it lasts, [power] how hard its bodies land.
 */
data class MeteorDials(val often: Double, val long: Double, val power: Double) {
    companion object {
        /** What an Age that bought nothing inflicts — a written storm as written. */
        val NONE = MeteorDials(often = 0.0, long = 0.0, power = 0.0)

        fun of(spending: Spending) = MeteorDials(
            often = spending.reach(Manifestation.METEORS, Manifestation.STORMS_OFTEN),
            long = spending.reach(Manifestation.METEORS, Manifestation.STORMS_LONG),
            power = spending.reach(Manifestation.METEORS, Manifestation.STORMS_POWER),
        )
    }
}

/**
 * The same for a cave-in: [size] is how much ground one takes, [speed] how fast it goes once the warning
 * is over. Neither touches the warning itself.
 */
data class TectonicsDials(val size: Double, val speed: Double) {
    companion object {
        val NONE = TectonicsDials(size = 0.0, speed = 0.0)

        fun of(spending: Spending) = TectonicsDials(
            size = spending.reach(Manifestation.TECTONICS, Manifestation.CAVE_IN_SIZE),
            speed = spending.reach(Manifestation.TECTONICS, Manifestation.CAVE_IN_SPEED),
        )
    }
}

/**
 * A sandfall's: [often] how often a column comes and how many may stand at once, [long] how long one
 * lives, [size] how wide it grows, [depth] how deep a pass buries.
 */
data class SandfallDials(val often: Double, val long: Double, val size: Double, val depth: Double) {
    companion object {
        val NONE = SandfallDials(often = 0.0, long = 0.0, size = 0.0, depth = 0.0)

        fun of(spending: Spending) = SandfallDials(
            often = spending.reach(Manifestation.SANDFALL, Manifestation.COLUMNS_OFTEN),
            long = spending.reach(Manifestation.SANDFALL, Manifestation.COLUMNS_LONG),
            size = spending.reach(Manifestation.SANDFALL, Manifestation.COLUMNS_SIZE),
            depth = spending.reach(Manifestation.SANDFALL, Manifestation.COLUMNS_DEPTH),
        )
    }
}

/**
 * A blizzard's: [often] how often a storm comes, [long] how long each lasts, [visibility] how close the
 * whiteout closes in and how hard the snow drifts, [frostbite] how fast the cold gets into you.
 */
data class BlizzardDials(val often: Double, val long: Double, val visibility: Double, val frostbite: Double) {
    companion object {
        val NONE = BlizzardDials(often = 0.0, long = 0.0, visibility = 0.0, frostbite = 0.0)

        fun of(spending: Spending) = BlizzardDials(
            often = spending.reach(Manifestation.BLIZZARD, Manifestation.SNOWSTORMS_OFTEN),
            long = spending.reach(Manifestation.BLIZZARD, Manifestation.SNOWSTORMS_LONG),
            visibility = spending.reach(Manifestation.BLIZZARD, Manifestation.SNOWSTORMS_VISIBILITY),
            frostbite = spending.reach(Manifestation.BLIZZARD, Manifestation.SNOWSTORMS_FROSTBITE),
        )
    }
}

/** A tempest's: [often] how often bolts come, [blast] how big a crater one digs, [fire] how much it sets alight. */
data class TempestDials(val often: Double, val blast: Double, val fire: Double) {
    companion object {
        val NONE = TempestDials(often = 0.0, blast = 0.0, fire = 0.0)

        fun of(spending: Spending) = TempestDials(
            often = spending.reach(Manifestation.TEMPEST, Manifestation.BOLTS_OFTEN),
            blast = spending.reach(Manifestation.TEMPEST, Manifestation.BOLTS_BLAST),
            fire = spending.reach(Manifestation.TEMPEST, Manifestation.BOLTS_FIRE),
        )
    }
}

/**
 * An inferno's: [burnDamage] how much it hurts to stand out under open light, [lightIntensity] how dim that
 * light may get before the burning stops.
 */
data class InfernoDials(val burnDamage: Double, val lightIntensity: Double) {
    companion object {
        val NONE = InfernoDials(burnDamage = 0.0, lightIntensity = 0.0)

        fun of(spending: Spending) = InfernoDials(
            burnDamage = spending.reach(Manifestation.INFERNO, Manifestation.BURN_DAMAGE),
            lightIntensity = spending.reach(Manifestation.INFERNO, Manifestation.LIGHT_INTENSITY),
        )
    }
}

/**
 * **What a dial bought in full is worth: what `teeming` is to a written claim.** One figure for every dial
 * that scales a rate, so an Age that has spent everything on how often storms come has storms as often as
 * a book asking for teeming ones, and the two compound where both are true.
 */
internal fun asIfTeeming(density: Double, reach: Double): Double = density * (1.0 + reach * (TEEMING - 1.0))

/** `teeming`'s amount — `art/grammar/teeming.json`'s rung. */
private const val TEEMING = 4.0
