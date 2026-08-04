package co.voik.agesandtheart.age

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
 * What an Age is like, as opposed to what it is made of — drawn per Age from its seed when it is
 * written, then frozen (design §3.4).
 */
data class AgeCharacter(
    val seam: Seam,
    val alignment: Alignment,
    /** How wide a territory runs, in blocks. Frozen at writing; never re-read from [BiomeScale] on open. */
    val regionBlocks: Int,
) {
    /** The territories [aspect] divides into, one per preset it holds, each covering the ground its [Share] asks. */
    fun mapFor(aspect: Aspect, shares: List<Double>, seed: Long): RegionMap {
        if (shares.size <= 1) return RegionMap.whole()
        val stride = aspect.ordinal
        return RegionMap(
            members = shares.size,
            shares = shares,
            scale = regionBlocks.toDouble(),
            blend = seam.blendBlocks(regionBlocks),
            originX = if (alignment == Alignment.OFFSET) stride * regionBlocks / OFFSET_SHARE else 0,
            originZ = if (alignment == Alignment.OFFSET) stride * regionBlocks / (OFFSET_SHARE + 1) else 0,
            seed = if (alignment == Alignment.INDEPENDENT) seed + stride * ASPECT_STRIDE else seed,
        )
    }

    companion object {
        // Far enough that the two boundaries are plainly not the same line, near enough to read as related.
        private const val OFFSET_SHARE = 3

        // Arbitrary; only needs to be big enough that two aspects' claims share no structure.
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

/** How far the territory maps of different aspects agree. */
enum class Alignment(val key: String) : StringRepresentable {
    /** One map for every aspect. The ground, its dressing and its caves all change along one line. */
    SHARED("shared"),

    /** The same territories, shifted per aspect, so one thing changes shortly after another. */
    OFFSET("offset"),

    /** Nothing in common. */
    INDEPENDENT("independent"),
    ;

    override fun getSerializedName(): String = key

    companion object {
        val CODEC: Codec<Alignment> = StringRepresentable.fromEnum(Alignment::values)
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
     * displacing two — see `FaultCheck.noSeamBothBlendsAndDisplaces`.
     */
    FUZZED("fuzzed", 0.04, 5),
    ;

    /**
     * The transition width in blocks for a territory [regionBlocks] across. Zero for every displacement.
     *
     * Proportional to the territory and then capped: [share] holds the look steady as territories change
     * size, [WIDEST_FUZZ_BLOCKS] is absolute and wins where they disagree. At the default 400-block
     * territory both land on 16.
     */
    fun blendBlocks(regionBlocks: Int): Int =
        (regionBlocks * share).toInt().coerceAtMost(WIDEST_FUZZ_BLOCKS)

    override fun getSerializedName(): String = key

    companion object {
        val CODEC: Codec<Seam> = StringRepresentable.fromEnum(Seam::values)

        /** The widest band of dissolve any seam may have, in blocks. An absolute limit, not a proportion. */
        const val WIDEST_FUZZ_BLOCKS = 16

        /**
         * How far a scarp throws each side of a seam, in blocks — a 64-block cliff where two territories
         * are thrown opposite ways, against terrains standing between about y=63 and y=185.
         *
         * A guess, not a measurement. `./gradlew :common:preview --args=fault` draws it without a server.
         */
        const val SCARP_THROW = 32

        /**
         * The floor a rift cuts down to — about twenty blocks under the sea at 63, so a rift is swimmable
         * and divides an Age without partitioning it. Not the world's floor: a chasm to bedrock along
         * every seam would sever the territories outright.
         */
        const val RIFT_FLOOR = 40

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

        /** The seam this [key] names, or null where it names none — how a pinned `terrain.seam` is read. */
        fun named(key: String): Seam? = entries.firstOrNull { it.key == key }

        /** A seam drawn against its [frequency], out of [TOTAL_FREQUENCY]. */
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

        /** The forms chance can produce. A frequency of zero means unreachable rather than rare. */
        private val DRAWABLE = entries.filter { it.frequency > 0 }

        private val TOTAL_FREQUENCY = DRAWABLE.sumOf(Seam::frequency)
    }
}
