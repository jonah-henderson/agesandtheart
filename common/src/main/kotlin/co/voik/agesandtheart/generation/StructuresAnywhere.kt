package co.voik.agesandtheart.generation

import com.mojang.serialization.MapCodec
import net.minecraft.core.Holder
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.BiomeResolver
import net.minecraft.world.level.biome.BiomeSource
import net.minecraft.world.level.biome.Climate
import net.minecraft.world.level.levelgen.structure.StructureSet
import java.util.stream.Stream

/**
 * [real] as an `incongruous structures` Age's structure state sees it: **every biome any of [sets] accepts counts
 * as one it can grow**, so no set is dropped for want of its biomes before a structure is ever tried.
 *
 * Vanilla filters twice before the biome test [IncongruousStructures] lifts: building the state keeps only the
 * sets some structure of which accepts a biome the level can grow, and laying out positions only those structures.
 * An Age with no ocean therefore never even tried a monument. Only the state is built over this; where a biome
 * actually is still comes from [real].
 */
class StructuresAnywhere(private val real: BiomeSource, sets: List<Holder<StructureSet>>) : BiomeSource() {

    private val accepted: Set<Holder<Biome>> = sets
        .flatMap { set -> set.value().structures() }
        .flatMap { entry -> entry.structure().value().biomes().toList() }
        .toSet()

    override fun codec(): MapCodec<out BiomeSource> = MapCodec.unit(this)

    override fun collectPossibleBiomes(): Stream<Holder<Biome>> = (real.possibleBiomes() + accepted).stream()

    override fun createResolver(sampler: Climate.Sampler): BiomeResolver = real.createResolver(sampler)
}
