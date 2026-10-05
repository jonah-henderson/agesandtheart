package co.voik.agesandtheart.age

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.worldgen.biome.BiomeScale
import co.voik.agesandtheart.worldgen.field.RegionMap
import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.server.MinecraftServer
import net.minecraft.util.StringRepresentable
import net.minecraft.world.level.levelgen.XoroshiroRandomSource

/**
 * What an Age is like, as opposed to what it is made of — drawn per Age from its seed when it is
 * written, then frozen (design §3.4).
 *
 * How wide its territories run and how far its aspects agree about where they are: everything that is true
 * of the Age however many of its properties divide. What each division *is* belongs to the division — see
 * [Spread].
 */
data class AgeCharacter(
    val alignment: Alignment,
    /** How wide a territory runs, in blocks. Frozen at writing; never re-read from [BiomeScale] on open. */
    val regionBlocks: Int,
) {
    /** The territories [aspect] divides into, each covering the ground its [spread] gives it. */
    fun mapFor(aspect: Aspect, spread: Spread, seed: Long, torn: Double = Seam.UNTORN): RegionMap {
        if (spread.shares.size <= 1) return RegionMap.whole()
        val stride = aspect.ordinal
        return RegionMap(
            members = spread.shares.size,
            shares = spread.shares,
            scale = regionBlocks.toDouble(),
            blend = spread.seam.blendBlocks(regionBlocks, torn),
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
                alignment = Alignment.entries[random.nextInt(Alignment.entries.size)],
                regionBlocks = BiomeScale.regionBlocks(server),
            )
        }

        /** The default region size, every map together: what an Age has when nothing drew it a character. */
        val PLAIN = AgeCharacter(Alignment.SHARED, BiomeScale.DEFAULT_REGION_BLOCKS)

        // So the character is decorrelated from everything else the seed drives.
        private const val CHARACTER_SALT = 0x0C7A_5AC7L

        val MAP_CODEC: MapCodec<AgeCharacter> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
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
