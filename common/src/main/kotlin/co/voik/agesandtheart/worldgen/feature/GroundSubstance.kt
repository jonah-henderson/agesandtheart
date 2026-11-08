package co.voik.agesandtheart.worldgen.feature

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.registries.Registries
import net.minecraft.tags.TagKey
import net.minecraft.util.ExtraCodecs
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.block.state.BlockState

/** What a [Formation] is made of where it stands in one of [biomes] — see [Formation.byGround]. */
data class GroundSubstance(val biomes: TagKey<Biome>, val substances: List<BlockState>) {
    companion object {
        val CODEC: Codec<GroundSubstance> = RecordCodecBuilder.create { instance ->
            instance.group(
                TagKey.hashedCodec(Registries.BIOME).fieldOf("biomes").forGetter(GroundSubstance::biomes),
                ExtraCodecs.nonEmptyList(ExtraCodecs.compactListCodec(BlockState.CODEC)).fieldOf("substance")
                    .forGetter(GroundSubstance::substances),
            ).apply(instance, ::GroundSubstance)
        }
    }
}
