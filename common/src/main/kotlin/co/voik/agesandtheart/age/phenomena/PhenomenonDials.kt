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

        fun of(spending: Spending, written: Double = UNSAID) = MeteorDials(
            often = spending.reach(Manifestation.METEORS, Manifestation.STORMS_OFTEN),
            long = spending.reach(Manifestation.METEORS, Manifestation.STORMS_LONG),
            power = harderBy(spending.reach(Manifestation.METEORS, Manifestation.STORMS_POWER), written),
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

        fun of(spending: Spending, written: Double = UNSAID) = TectonicsDials(
            size = harderBy(spending.reach(Manifestation.TECTONICS, Manifestation.CAVE_IN_SIZE), written),
            speed = harderBy(spending.reach(Manifestation.TECTONICS, Manifestation.CAVE_IN_SPEED), written),
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

        fun of(spending: Spending, written: Double = UNSAID) = SandfallDials(
            often = spending.reach(Manifestation.SANDFALL, Manifestation.COLUMNS_OFTEN),
            long = spending.reach(Manifestation.SANDFALL, Manifestation.COLUMNS_LONG),
            size = harderBy(spending.reach(Manifestation.SANDFALL, Manifestation.COLUMNS_SIZE), written),
            depth = harderBy(spending.reach(Manifestation.SANDFALL, Manifestation.COLUMNS_DEPTH), written),
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

        fun of(spending: Spending, written: Double = UNSAID) = BlizzardDials(
            often = spending.reach(Manifestation.BLIZZARD, Manifestation.SNOWSTORMS_OFTEN),
            long = spending.reach(Manifestation.BLIZZARD, Manifestation.SNOWSTORMS_LONG),
            visibility = harderBy(spending.reach(Manifestation.BLIZZARD, Manifestation.SNOWSTORMS_VISIBILITY), written),
            frostbite = harderBy(spending.reach(Manifestation.BLIZZARD, Manifestation.SNOWSTORMS_FROSTBITE), written),
        )
    }
}

/** A tempest's: [often] how often bolts come, [blast] how big a crater one digs, [fire] how much it sets alight. */
data class TempestDials(val often: Double, val blast: Double, val fire: Double) {
    companion object {
        val NONE = TempestDials(often = 0.0, blast = 0.0, fire = 0.0)

        fun of(spending: Spending, written: Double = UNSAID) = TempestDials(
            often = spending.reach(Manifestation.TEMPEST, Manifestation.BOLTS_OFTEN),
            blast = harderBy(spending.reach(Manifestation.TEMPEST, Manifestation.BOLTS_BLAST), written),
            fire = harderBy(spending.reach(Manifestation.TEMPEST, Manifestation.BOLTS_FIRE), written),
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

        fun of(spending: Spending, written: Double = UNSAID) = InfernoDials(
            burnDamage = harderBy(spending.reach(Manifestation.INFERNO, Manifestation.BURN_DAMAGE), written),
            lightIntensity = harderBy(spending.reach(Manifestation.INFERNO, Manifestation.LIGHT_INTENSITY), written),
        )
    }
}

/** A fog's: [often] how much of the time a bank lies over the Age, [thickness] how close one closes in. */
data class FogDials(val often: Double, val thickness: Double) {
    companion object {
        val NONE = FogDials(often = 0.0, thickness = 0.0)

        fun of(spending: Spending, written: Double = UNSAID) = FogDials(
            often = spending.reach(Manifestation.FOG, Manifestation.BANKS_OFTEN),
            thickness = harderBy(spending.reach(Manifestation.FOG, Manifestation.BANKS_THICKNESS), written),
        )
    }
}

/**
 * A harsh dial with how hard the book wrote the phenomenon laid on it: [written] from -1 to 1 moves it up to
 * half way on toward its fury, or as far below ordinary — a reach under nought is a gentler one than an Age
 * that bought nothing has, which every reader of a harsh dial takes.
 */
internal fun harderBy(reach: Double, written: Double): Double =
    (reach + written * WRITTEN_SHARE_OF_A_DIAL).coerceIn(GENTLEST_REACH, WHOLE_REACH)

private const val WRITTEN_SHARE_OF_A_DIAL = 0.5
private const val GENTLEST_REACH = -0.5
private const val WHOLE_REACH = 1.0

/** What a dial carries where the book said nothing of how hard. */
private const val UNSAID = 0.0

/**
 * **What a dial bought in full is worth: what `teeming` is to a written claim.** One figure for every dial
 * that scales a rate, so an Age that has spent everything on how often storms come has storms as often as
 * a book asking for teeming ones, and the two compound where both are true.
 */
internal fun asIfTeeming(density: Double, reach: Double): Double = density * (1.0 + reach * (TEEMING - 1.0))

/** `teeming`'s amount — `art/grammar/teeming.json`'s rung. */
private const val TEEMING = 4.0
