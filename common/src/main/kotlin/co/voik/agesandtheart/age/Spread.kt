package co.voik.agesandtheart.age

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Share
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.util.RandomSource
import net.minecraft.util.StringRepresentable
import net.minecraft.world.level.levelgen.XoroshiroRandomSource
import java.util.Optional

/**
 * How a **spatial population** is laid across the map — its members held each somewhere rather than all of
 * them everywhere (`the-world-model.md` §2).
 *
 * The two things a spatial population needs that a plain one does not: how much ground each member covers,
 * and what the boundary looks like where they meet. They belong together because neither means anything
 * without the other — a share divides ground the seam then has to draw a line through.
 */
data class Spread(
    /**
     * How much ground each member covers, one per member — the sentence's claim on it as a fraction of its
     * strongest claim, so the widest is [Share.EVEN] and the rest measure against it. Empty means even.
     */
    val shares: List<Double> = emptyList(),
    /**
     * The form drawn for this boundary, or null where nothing has drawn one — read through [seam], which
     * answers for both.
     *
     * Nullable so that **asking for a shear is not the same as saying nothing**: `landmass.seam=sheared` on
     * a divided Age is a writer asking for no fault, and folding it into the default would let
     * [AgeComposition.seamed] draw a scarp over the top of the request.
     */
    val drawn: Seam? = null,
) {
    /** What this boundary does. Sheared where nothing drew a form: two shapes simply meet, and the cut shows. */
    val seam: Seam get() = drawn ?: Seam.SHEARED

    /** Whether this says anything a recipe has to keep — an even division with no form drawn does not. */
    val saysNothing: Boolean get() = drawn == null && shares.all(Share::isEven)

    /** This spread with [members] shares, whatever it was written with — never a shorter list. */
    fun over(members: Int): Spread =
        copy(shares = List(members) { member -> shares.getOrElse(member) { Share.EVEN } })

    companion object {
        /** How a recipe pins a boundary: `landmass.seam=rift`. Not a preset's parameter — see [Seam]. */
        const val SEAM = "seam"

        val CODEC: Codec<Spread> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.DOUBLE.listOf().optionalFieldOf("shares", emptyList()).forGetter(Spread::shares),
                Seam.CODEC.optionalFieldOf("seam").forGetter { Optional.ofNullable(it.drawn) },
            ).apply(instance) { shares, seam -> Spread(shares, seam.orElse(null)) }
        }
    }
}

/**
 * How each of an Age's spatial populations is laid out, kept per aspect beside [AspectOptions].
 *
 * An absent aspect is an even division with no form drawn, and so is one that [Spread.saysNothing] —
 * normalised away on the way in, so "undivided and unremarkable" has one spelling rather than several.
 */
data class Spreads(private val byAspect: Map<Aspect, Spread> = emptyMap()) {
    fun of(aspect: Aspect): Spread = byAspect[aspect] ?: Spread()

    fun with(aspect: Aspect, spread: Spread): Spreads =
        Spreads(if (spread.saysNothing) byAspect - aspect else byAspect + (aspect to spread))

    /** The same, keeping whatever form was already drawn for that boundary. */
    fun withShares(aspect: Aspect, shares: List<Double>): Spreads =
        with(aspect, of(aspect).copy(shares = shares))

    /** And the same for the form, keeping the ground each member covers. */
    fun withSeam(aspect: Aspect, seam: Seam): Spreads = with(aspect, of(aspect).copy(drawn = seam))

    companion object {
        val CODEC: Codec<Spreads> =
            Codec.unboundedMap(StringRepresentable.fromEnum(Aspect::values), Spread.CODEC)
                .xmap(::Spreads, Spreads::byAspect)
    }
}

/** What happens where two territories meet — which form a fault takes there. */
enum class Seam(val key: String, val share: Double, val frequency: Int) : StringRepresentable {
    /** No transition and no displacement. Two worlds pushed together, and the cut shows. */
    SHEARED("sheared", 0.0, 15),

    /** One side thrown up against the other: a cliff, `Fault`'s business. */
    SCARP("scarp", 0.0, 30),

    /** The ground pulled apart along the boundary and dropped: `Rift`'s business. */
    RIFT("rift", 0.0, 30),

    /**
     * Both sides left level and a jagged wall standing between them — the rift inverted, and `Ridge`'s
     * business.
     */
    WALL("wall", 0.0, 20),

    /**
     * The two shapes interlock through a band of stochastic columns, so one dissolves into the other.
     *
     * The only form that is a width rather than a displacement, which is why it cannot coexist with the
     * displacing three — see `FaultCheck.noSeamBothBlendsAndDisplaces`.
     */
    FUZZED("fuzzed", 0.04, 5),
    ;

    /**
     * Whether this form moves rock, as opposed to deciding where the boundary line falls.
     *
     * What decides where a form may be drawn at all ([drawnFor]): a displacement needs rock of its own to
     * displace, so only the terrain can show one.
     */
    val displaces: Boolean
        get() = when (this) {
            SCARP, RIFT, WALL -> true
            SHEARED, FUZZED -> false
        }

    /**
     * The transition width in blocks for a territory [regionBlocks] across. Zero for every displacement.
     *
     * Proportional to the territory and then capped: [share] holds the look steady as territories change
     * size, [WIDEST_FUZZ_BLOCKS] is absolute and wins where they disagree. At the default 400-block
     * territory both land on 16.
     */
    fun blendBlocks(regionBlocks: Int, torn: Double = UNTORN): Int {
        val widened = share * (1.0 + torn * FUZZ_TEARS_TO)
        return (regionBlocks * widened).toInt().coerceAtMost(widestFuzz(torn))
    }

    override fun getSerializedName(): String = key

    companion object {
        val CODEC: Codec<Seam> = StringRepresentable.fromEnum(Seam::values)

        /**
         * The widest band of dissolve a *coherent* Age may have, in blocks. An absolute limit, not a
         * proportion.
         */
        const val WIDEST_FUZZ_BLOCKS = 16

        /** An Age that bought no tearing, which is every coherent one (design §5.0). */
        const val UNTORN = 0.0

        /**
         * What tearing does to each magnitude, as a multiple added at full reach.
         *
         * **A fuzz widens hardest**, because widening is the whole of what it is: a 16-block dissolve is a
         * transition and a 64-block one is two worlds failing to decide which is which, which is the
         * difference between a seam and a tear. The displacing forms are more restrained — a rift that cut
         * to bedrock would sever the territories outright, which `RIFT_FLOOR` exists to prevent.
         */
        private const val FUZZ_TEARS_TO = 3.0
        private const val SCARP_TEARS_TO = 1.5
        private const val RIFT_TEARS_TO = 0.6
        private const val WALL_TEARS_TO = 1.0

        /** The dissolve band's ceiling at this much tearing. */
        fun widestFuzz(torn: Double): Int = (WIDEST_FUZZ_BLOCKS * (1.0 + torn * FUZZ_TEARS_TO)).toInt()

        /** How far a scarp throws, thrown further the more torn the Age. */
        fun scarpThrow(torn: Double): Int = (SCARP_THROW * (1.0 + torn * SCARP_TEARS_TO)).toInt()

        /**
         * How deep a rift cuts, cut deeper the more torn the Age — **never past [RIFT_DEEPEST]**, because
         * a chasm to bedrock along every seam severs the territories rather than dividing them.
         */
        fun riftFloor(torn: Double): Int =
            (RIFT_FLOOR - (RIFT_FLOOR - RIFT_DEEPEST) * torn * RIFT_TEARS_TO).toInt().coerceAtLeast(RIFT_DEEPEST)

        /** How high a wall stands, raised the more torn the Age. */
        fun wallCrest(torn: Double): Int = (WALL_CREST + (WALL_CREST - WALL_FOOTING) * torn * WALL_TEARS_TO).toInt()

        /**
         * The floor no rift cuts below, whatever the budget.
         *
         * **Bedrock in an Age is at −64**, not 0 — every dimension type of ours is `min_y: -64` and
         * `Terrain` floors the rock at `minY + BEDROCK_MARGIN`, so this leaves about thirty blocks of rock
         * under the deepest cut a torn Age can make. It must stay **below [RIFT_FLOOR]** or [riftFloor]'s
         * `coerceAtLeast` inverts the direction and a torn rift comes out shallower than an untorn one.
         */
        const val RIFT_DEEPEST = -30

        /**
         * How far a scarp throws each side of a seam, in blocks — a 64-block cliff where two territories
         * are thrown opposite ways, against terrains standing between about y=63 and y=185.
         *
         * A guess, not a measurement. `./gradlew :common:preview --args=fault` draws it without a server.
         */
        const val SCARP_THROW = 32

        /**
         * The floor a rift cuts down to, and **the only lever on how deep one reads**.
         *
         * [RIFT_RIM] sets where the cut stops at the band's edge, which is the *flare of the walls* rather
         * than the depth at the seam — so deepening a rift means spending this, and the sever guard with
         * it. Against [RIFT_RIM] it gives 78 blocks of relief, which is the wall's own
         * (`WALL_CREST` over `WALL_FOOTING`), across a band the same 48 wide: the two seam forms are the
         * same size now, and that proportion is the thing to keep if either moves.
         *
         * The walls are as steep as that relief over [co.voik.agesandtheart.worldgen.field.Rift]'s
         * half-width makes them, so **widening a rift flattens it** unless this follows.
         */
        const val RIFT_FLOOR = -6

        /**
         * Where a rift stops cutting. **Above the waterline on purpose**: a rift no longer floods by
         * construction, so a sea reaches one only where it actually cuts a coast.
         */
        const val RIFT_RIM = 72

        /**
         * Deep enough to be under any ground the wall crosses. Founded at the surface it floats over
         * every dip, and the gap is only visible in profile.
         */
        const val WALL_FOOTING = 30
        const val WALL_CREST = 108

        /** The seam this [key] names, or null where it names none — how a pinned `landmass.seam` is read. */
        fun named(key: String): Seam? = entries.firstOrNull { it.key == key }

        /**
         * The form drawn for a boundary in [aspect] — the frequencies below, save that **a displacement
         * lands as a plain shear anywhere but the terrain**.
         *
         * Only the terrain has rock of its own to move: a sea and a climate divide over ground some other
         * aspect shaped, so a scarp there would be a form nothing could show. Reduced rather than redrawn,
         * which is what keeps the fuzz at its 5% everywhere instead of making it a quarter of the two
         * outcomes left (design §3.4).
         */
        fun drawnFor(aspect: Aspect, random: RandomSource): Seam {
            val form = drawn(random)
            val hasRockToMove = aspect == Aspect.TERRAIN
            return if (hasRockToMove || !form.displaces) form else SHEARED
        }

        /** A form drawn against its [frequency], out of [TOTAL_FREQUENCY]. */
        fun drawn(random: RandomSource): Seam {
            // Spend the roll down through the bands in declaration order; whichever takes it past zero owns
            // it. Same shape as `Choose.pickWeighted`.
            var remaining = random.nextInt(TOTAL_FREQUENCY)
            for (seam in DRAWABLE) {
                remaining -= seam.frequency
                if (remaining < 0) return seam
            }
            // Unreachable while the roll is bounded by the total; a definite answer rather than a throw.
            return DRAWABLE.last()
        }

        /**
         * The source a boundary in [aspect] draws its form from — one per aspect, so an Age divided in two
         * of them does not get the same form twice by construction.
         *
         * Strides on the ordinal like `AgeCharacter.mapFor`, which is the other half of why an aspect is
         * appended and never inserted.
         */
        fun sourceFor(aspect: Aspect, seed: Long): RandomSource =
            XoroshiroRandomSource(seed xor SEAM_SALT xor (aspect.ordinal * ASPECT_STRIDE))

        // So a form is decorrelated from the territories it is drawn across, and from aspect to aspect.
        private const val SEAM_SALT = 0x5EA_1BEEFL
        private const val ASPECT_STRIDE = 0x9E37_79B9L

        /** The forms chance can produce. A frequency of zero means unreachable rather than rare. */
        private val DRAWABLE = entries.filter { it.frequency > 0 }

        private val TOTAL_FREQUENCY = DRAWABLE.sumOf(Seam::frequency)
    }
}
