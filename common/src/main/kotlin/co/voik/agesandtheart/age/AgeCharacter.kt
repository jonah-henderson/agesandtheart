package co.voik.agesandtheart.age

import co.voik.agesandtheart.age.slot.Share
import co.voik.agesandtheart.age.slot.Slot
import co.voik.agesandtheart.worldgen.biome.BiomeScale
import co.voik.agesandtheart.worldgen.field.RegionMap
import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.server.MinecraftServer
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
 * and is not here yet, because nothing reads it until the medium slot becomes set-valued too.
 */
data class AgeCharacter(
    val seam: Seam,
    /**
     * How far the territory maps of different slots agree. **One property for all of them**, not one per
     * pairing — with several positional slots the pairings would multiply out of hand, and "this Age is
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
     * The territories [slot] divides itself into, one per preset it holds, each covering the ground its
     * [Share] asks for.
     *
     * Where the maps of two slots sit relative to each other is [alignment]'s business: the same
     * territories for everything, the same shape shifted so the ground changes a little before its
     * dressing does, or maps that share nothing at all.
     */
    fun mapFor(slot: Slot, shares: List<Share>, seed: Long): RegionMap {
        if (shares.size <= 1) return RegionMap.whole()
        val stride = slot.ordinal
        return RegionMap(
            members = shares.size,
            shares = shares.map { it.weight },
            scale = regionBlocks.toDouble(),
            blend = seam.blendBlocks(regionBlocks),
            originX = if (alignment == Alignment.OFFSET) stride * regionBlocks / OFFSET_SHARE else 0,
            originZ = if (alignment == Alignment.OFFSET) stride * regionBlocks / (OFFSET_SHARE + 1) else 0,
            seed = if (alignment == Alignment.INDEPENDENT) seed + stride * SLOT_STRIDE else seed,
        )
    }

    companion object {
        // Far enough that the two boundaries are plainly not the same line, near enough that they still
        // read as related. A whole territory apart would just be independence with extra steps.
        private const val OFFSET_SHARE = 3

        // Arbitrary, and only ever needs to be big enough that two slots' claims share no structure.
        private const val SLOT_STRIDE = 0x5B1F_7A3L

        /** The character an Age written now, here, with this [seed] comes out with. */
        fun drawn(server: MinecraftServer, seed: Long): AgeCharacter {
            val random = XoroshiroRandomSource(seed xor CHARACTER_SALT)
            return AgeCharacter(
                seam = Seam.entries[random.nextInt(Seam.entries.size)],
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
 * How far the territory maps of different slots agree.
 *
 * Per §1 this owes the player a word, like everything else drawn per Age — *ordered* through to
 * *jumbled* — and is stored rather than re-derived from the seed for exactly that reason.
 */
enum class Alignment(val key: String) : StringRepresentable {
    /** One map for every slot. The ground, its dressing and its caves all change along one line. */
    SHARED("shared"),

    /** The same territories, shifted per slot, so one thing changes shortly after another. */
    OFFSET("offset"),

    /** Nothing in common. Four kinds of place from two landforms and two dressings. */
    INDEPENDENT("independent"),
    ;

    override fun getSerializedName(): String = key

    companion object {
        val CODEC: Codec<Alignment> = StringRepresentable.fromEnum(Alignment::values)
    }
}

/**
 * What happens where two territories meet, as a share of the region's own width.
 *
 * Proportional rather than absolute so the look survives Large Biomes: a seam that is a tenth of a
 * territory stays a tenth of a territory when territories are four times wider.
 *
 * [SHEARED] is the one to understand — at zero width the region is decided outright per column, so an
 * island straddling a boundary is cut off flat in mid-air with open sky beneath it. That is not a defect
 * to be softened away; it is the impossible geometry the whole feature exists to produce, and the wider
 * settings are the *concession*, not the other way round.
 */
enum class Seam(val key: String, val share: Double) : StringRepresentable {
    /** No transition at all. Two worlds pushed together, and the cut shows. */
    SHEARED("sheared", 0.0),

    /** A few columns of interlocking, so the cut reads as broken rather than sawn. */
    KEEN("keen", 0.04),

    /** A visible band where the two shapes contend. */
    SOFT("soft", 0.12),

    /** A wide dissolve; from the ground you would struggle to say where one ends. */
    BLURRED("blurred", 0.30),
    ;

    /** The transition width in blocks for a territory [regionBlocks] across. */
    fun blendBlocks(regionBlocks: Int): Int = (regionBlocks * share).toInt()

    override fun getSerializedName(): String = key

    companion object {
        val CODEC: Codec<Seam> = StringRepresentable.fromEnum(Seam::values)
    }
}
