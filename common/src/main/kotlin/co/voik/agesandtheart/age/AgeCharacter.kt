package co.voik.agesandtheart.age

import co.voik.agesandtheart.age.aspect.Share
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.worldgen.biome.BiomeScale
import co.voik.agesandtheart.worldgen.field.RegionMap
import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.server.MinecraftServer
import net.minecraft.util.RandomSource
import net.minecraft.util.StringRepresentable
import net.minecraft.world.level.levelgen.XoroshiroRandomSource

/**
 * What an Age is like, as opposed to what it is made of.
 *
 * These are drawn **per Age from its seed when it is written** and then frozen, so an Age has a
 * character before anyone has looked at what is in it, and two Ages composed from identical words are
 * still worth visiting twice (`notes/the-art-design.md` §3.4).
 *
 * **Every one of these owes the player a word.** Per §1, randomness is a default and never an endpoint:
 * a decision the generator makes that no word can ever override is a degree of freedom nobody can buy
 * back, which caps the progression axis somewhere nobody chose. They are stored rather than re-derived
 * from the seed precisely because a writer will one day name them instead.
 *
 * **Expect this to grow.** One more is already designed — whether an Age's sea is one substance or two —
 * and is not here yet, because nothing reads it until the sea aspect becomes set-valued too.
 */
data class AgeCharacter(
    val seam: Seam,
    /**
     * How far the territory maps of different aspects agree. **One property for all of them**, not one per
     * pairing — with several positional aspects the pairings would multiply out of hand, and "this Age is
     * jumbled" is one fact about it rather than three.
     */
    val alignment: Alignment,
    /**
     * How wide a territory runs, in blocks, resolved from the world this Age was written in
     * ([BiomeScale]) and then **frozen**. Never re-read on open: a datapack that retuned climate would
     * otherwise silently redraw the territories of every Age already written.
     */
    val regionBlocks: Int,
) {
    /**
     * The territories [aspect] divides itself into, one per preset it holds, each covering the ground its
     * [Share] asks for.
     *
     * Where the maps of two aspects sit relative to each other is [alignment]'s business: the same
     * territories for everything, the same shape shifted so the ground changes a little before its
     * dressing does, or maps that share nothing at all.
     */
    fun mapFor(aspect: Aspect, shares: List<Share>, seed: Long): RegionMap {
        if (shares.size <= 1) return RegionMap.whole()
        val stride = aspect.ordinal
        return RegionMap(
            members = shares.size,
            shares = shares.map { it.weight },
            scale = regionBlocks.toDouble(),
            blend = seam.blendBlocks(regionBlocks),
            originX = if (alignment == Alignment.OFFSET) stride * regionBlocks / OFFSET_SHARE else 0,
            originZ = if (alignment == Alignment.OFFSET) stride * regionBlocks / (OFFSET_SHARE + 1) else 0,
            seed = if (alignment == Alignment.INDEPENDENT) seed + stride * ASPECT_STRIDE else seed,
        )
    }

    companion object {
        // Far enough that the two boundaries are plainly not the same line, near enough that they still
        // read as related. A whole territory apart would just be independence with extra steps.
        private const val OFFSET_SHARE = 3

        // Arbitrary, and only ever needs to be big enough that two aspects' claims share no structure.
        private const val ASPECT_STRIDE = 0x5B1F_7A3L

        /** The character an Age written now, here, with this [seed] comes out with. */
        fun drawn(server: MinecraftServer, seed: Long): AgeCharacter {
            val random = XoroshiroRandomSource(seed xor CHARACTER_SALT)
            return AgeCharacter(
                seam = Seam.drawn(random),
                alignment = Alignment.entries[random.nextInt(Alignment.entries.size)],
                regionBlocks = BiomeScale.regionBlocks(server),
            )
        }

        /** What an Age written before character existed had: a knife edge, at the default region size. */
        val LEGACY = AgeCharacter(Seam.SHEARED, Alignment.SHARED, BiomeScale.DEFAULT_REGION_BLOCKS)

        // So the character is decorrelated from everything else the seed drives.
        private const val CHARACTER_SALT = 0x0C7A_5AC7L

        val MAP_CODEC: MapCodec<AgeCharacter> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Seam.CODEC.optionalFieldOf("seam", Seam.SHEARED).forGetter(AgeCharacter::seam),
                Alignment.CODEC.optionalFieldOf("alignment", Alignment.SHARED).forGetter(AgeCharacter::alignment),
                Codec.INT.optionalFieldOf("region_blocks", BiomeScale.DEFAULT_REGION_BLOCKS)
                    .forGetter(AgeCharacter::regionBlocks),
            ).apply(instance, ::AgeCharacter)
        }
    }
}

/**
 * How far the territory maps of different aspects agree.
 *
 * Per §1 this owes the player a word, like everything else drawn per Age — *ordered* through to
 * *jumbled* — and is stored rather than re-derived from the seed for exactly that reason.
 */
enum class Alignment(val key: String) : StringRepresentable {
    /** One map for every aspect. The ground, its dressing and its caves all change along one line. */
    SHARED("shared"),

    /** The same territories, shifted per aspect, so one thing changes shortly after another. */
    OFFSET("offset"),

    /** Nothing in common. Four kinds of place from two terrains and two dressings. */
    INDEPENDENT("independent"),
    ;

    override fun getSerializedName(): String = key

    companion object {
        val CODEC: Codec<Alignment> = StringRepresentable.fromEnum(Alignment::values)
    }
}

/**
 * What happens where two territories meet — **which of the three forms a fault takes there**.
 *
 * ## This used to be a width, and moving it was Jonah's call (2026-07-29)
 *
 * It was four transition *widths*: sheared, keen, soft, blurred, drawn 85/9/4/2, with the softening an
 * innate property of any territory boundary and the fault a separate thing layered on top. That was
 * conceptually the wrong shape, and it showed up as a defect nobody predicted: a displacement pushed
 * through a frayed boundary throws the interlocking columns alternately up and down, so the seam came out
 * as a strip of one-block spikes as tall as the throw instead of as a cliff.
 *
 * *"The conceptually consistent thing to do is to move the softened border from an innate region/territory
 * mechanic and instead make it a rare form of fault."* — and the defect is then **structurally impossible**
 * rather than merely rare, because a seam is one form or another and never both. That is the argument for
 * this shape, and it is a better one than the tidiness: [FUZZED] and the two displacements are mutually
 * exclusive by construction, so no combination of draws can produce the picket fence.
 *
 * ## The distribution — Jonah, 2026-07-29, amended the same day
 *
 * **Rift 40 / scarp 40 / sheared 15 / fuzzed 5.** So a boundary is a chasm or a cliff four times in five,
 * simply a cut about one time in seven, and dissolves rarely.
 *
 * The first pass had no [SHEARED] in the draw at all — every seam was a fault, 47.5/47.5/5 — and putting it
 * back at 15% is the better shape: an ordinary meeting of two shapes is *already* impossible geometry (an
 * island straddling one is cut off flat in mid-air), so a divided Age that gets nothing further is not a
 * wasted draw. It also leaves the two dramatic forms room to *be* dramatic, which they are not if every
 * divided Age has one.
 *
 * The old reasoning for biasing *away* from softness still holds and is what keeps the fuzz at 5% rather
 * than a quarter: wide seams **compound** across divided aspects — one diffuse boundary is strange, four at
 * once reads as a world coming apart — and a single fuzzy Age read as too weird on its own with nothing else
 * unusual about it. What changed is that softness is no longer the *concession* to a knife edge; it is a
 * fourth outcome alongside three others.
 */
enum class Seam(val key: String, val share: Double, val frequency: Int) : StringRepresentable {
    /**
     * No transition and no displacement. Two worlds pushed together, and the cut shows.
     *
     * The only value that says *nothing happens here*, which is why it is also what a pre-character Age
     * carries and what `terrain.seam=sheared` pins. **Drawn at 15%** — see the class KDoc for why it went
     * from unreachable back into the draw.
     */
    SHEARED("sheared", 0.0, 15),

    /** One side thrown up against the other: a cliff, `Fault`'s business. */
    SCARP("scarp", 0.0, 40),

    /** The ground pulled apart along the boundary and dropped, usually into water: `Rift`'s business. */
    RIFT("rift", 0.0, 40),

    /**
     * The two shapes interlock through a band of stochastic columns, so one dissolves into the other.
     *
     * **The only form that is a width rather than a displacement**, which is exactly why it cannot coexist
     * with the other two.
     *
     * **0.04 — the narrowest width the old four ever had, and narrow on purpose** (Jonah, amending the same
     * day it was first set to 0.12): *"the wide fuzziness can be absolutely overwhelming to the point of
     * incomprehensibility in game, which isn't fun. No more than 16 blocks of transition is probably about
     * the sweet spot."* A dissolve you cannot see the far side of does not read as a boundary at all; it
     * reads as the world having stopped making sense. Sixteen blocks is a band you can stand in and still
     * see both sides of.
     */
    FUZZED("fuzzed", 0.04, 5),
    ;

    /**
     * The transition width in blocks for a territory [regionBlocks] across. Zero for every displacement.
     *
     * Proportional to the territory **and then capped**, which is two rules rather than one because they
     * answer different questions. [share] keeps a seam the same *fraction* of a territory as territories
     * change size, so the look survives a datapack that shrinks biomes. [WIDEST_FUZZ_BLOCKS] answers the
     * question a fraction cannot: how much dissolve a person can actually stand in and still understand
     * where they are. That one is absolute — a player in Large Biomes does not find a 64-block fray any more
     * comprehensible for its being a fortieth of a territory — so the cap wins wherever the two disagree.
     *
     * At the default 400-block territory the two land in exactly the same place, at 16.
     */
    fun blendBlocks(regionBlocks: Int): Int =
        (regionBlocks * share).toInt().coerceAtMost(WIDEST_FUZZ_BLOCKS)

    override fun getSerializedName(): String = key

    companion object {
        val CODEC: Codec<Seam> = StringRepresentable.fromEnum(Seam::values)

        /**
         * The widest band of dissolve any seam may have, in blocks — **an absolute limit, not a proportion**.
         *
         * Jonah's number, and his reason is about a person rather than about a territory: past about this
         * much, a fray stops reading as a boundary and starts reading as the world having stopped making
         * sense. See [blendBlocks] for why it sits alongside [share] rather than replacing it.
         */
        const val WIDEST_FUZZ_BLOCKS = 16

        /** The seam this [key] names, or null where it names none — how a pinned `terrain.seam` is read. */
        fun named(key: String): Seam? = entries.firstOrNull { it.key == key }

        /**
         * A seam drawn against its [frequency] — **a chasm or a cliff four times in five, a plain cut about
         * one time in seven, a dissolve rarely.**
         *
         * Frequencies are out of [TOTAL_FREQUENCY] so they read as the percentages they are.
         *
         * This stays the right shape for instability to take over (design §5), but what it will take over
         * changed with the forms: a drawn form is now *which* drama a seam carries, and instability is meant
         * to decide **how much** — the throw of a scarp, the depth of a rift, the width of a fuzz. So the
         * draw here is not a floor waiting to be pushed up; it is a choice that stays a choice, and the
         * magnitudes beside it are the floors. Phase 6 step 0.
         */
        fun drawn(random: RandomSource): Seam {
            // Spend the roll down through the bands in declaration order; whichever one takes it past zero
            // owns it. Same shape as `Choose.pickWeighted`. Over the *drawable* entries, which is currently
            // all of them — the filter is what makes a frequency of zero mean genuinely unreachable rather
            // than reachable at one exact roll, so a pin-only form stays expressible without a trap.
            var remaining = random.nextInt(TOTAL_FREQUENCY)
            for (seam in DRAWABLE) {
                remaining -= seam.frequency
                if (remaining < 0) return seam
            }
            // Unreachable while the roll is bounded by the total, and a definite answer rather than a throw
            // if that ever stops being true.
            return DRAWABLE.last()
        }

        /** The forms chance can produce — everything with a frequency, which is everything but [SHEARED]. */
        private val DRAWABLE = entries.filter { it.frequency > 0 }

        private val TOTAL_FREQUENCY = DRAWABLE.sumOf(Seam::frequency)
    }
}
