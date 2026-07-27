package co.voik.agesandtheart.worldgen.biome

import co.voik.agesandtheart.worldgen.field.RegionMap
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.Holder
import net.minecraft.core.QuartPos
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.BiomeSource
import net.minecraft.world.level.biome.Climate
import java.util.stream.Stream

/**
 * Gives each territory the biomes of its own dressing.
 *
 * The counterpart to [co.voik.agesandtheart.worldgen.field.RegionRule], reading the same [RegionMap] so
 * the two agree to the column. They answer different questions and neither can do the other's job:
 * **the rule decides what the ground is made of, this decides what the place *is*** — its fog and sky
 * colour, its ambient sound and music, what spawns, and which structures may consider it.
 *
 * Splitting both ways is why a dressing needs no biome of its own to be distinguishable, and also why
 * having one still matters: two dressings that share a biome will paint differently but sound alike.
 */
class RegionBiomeSource(
    private val members: List<BiomeSource>,
    private val map: RegionMap,
) : BiomeSource() {

    override fun codec(): MapCodec<out BiomeSource> = CODEC

    /**
     * Everything any member could place. Vanilla reads this to decide which structures and features are
     * worth considering for the dimension at all, so under-reporting here silently removes content from
     * a territory rather than failing anywhere visible.
     */
    override fun collectPossibleBiomes(): Stream<Holder<Biome>> =
        members.flatMap { it.possibleBiomes() }.distinct().stream()

    /**
     * Quart coordinates in, so the territory is asked about in blocks — biome positions are quartered
     * (one per 4 blocks) and the map thinks in blocks, which is a conversion easy to forget and hard to
     * see the absence of: territories would come out a quarter the size they were asked for.
     */
    override fun getNoiseBiome(quartX: Int, quartY: Int, quartZ: Int, sampler: Climate.Sampler): Holder<Biome> {
        val member = map.memberAt(QuartPos.toBlock(quartX), QuartPos.toBlock(quartZ))
        return members[member.coerceIn(members.indices)].getNoiseBiome(quartX, quartY, quartZ, sampler)
    }

    companion object {
        val CODEC: MapCodec<RegionBiomeSource> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                BiomeSource.CODEC.listOf().fieldOf("members").forGetter { it.members },
                RegionMap.MAP_CODEC.forGetter { it.map },
            ).apply(instance, ::RegionBiomeSource)
        }

        /** [members] split by territory, or the single source itself when there is nothing to divide. */
        fun of(members: List<BiomeSource>, map: RegionMap): BiomeSource =
            members.singleOrNull() ?: RegionBiomeSource(members, map)
    }
}
