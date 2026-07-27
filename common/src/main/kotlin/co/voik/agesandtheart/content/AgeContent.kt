package co.voik.agesandtheart.content

import co.voik.agesandtheart.location
import co.voik.agesandtheart.worldgen.FieldChunkGenerator
import co.voik.agesandtheart.worldgen.SpireChunkGenerator
import co.voik.agesandtheart.worldgen.biome.AgeBiomeSource
import co.voik.agesandtheart.worldgen.biome.RegionBiomeSource
import co.voik.agesandtheart.worldgen.field.RegionRule
import co.voik.agesandtheart.worldgen.carver.Porosity
import co.voik.agesandtheart.worldgen.carver.RuleCarver
import co.voik.agesandtheart.worldgen.carver.Weathering
import com.mojang.serialization.MapCodec
import net.minecraft.core.component.DataComponentType
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.Item
import net.minecraft.world.level.biome.BiomeSource
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.SurfaceRules
import net.minecraft.world.level.levelgen.carver.CarverConfiguration
import net.minecraft.world.level.levelgen.carver.WorldCarver

/**
 * The mod's registered content, defined loader-agnostically.
 *
 * Instances are built eagerly here (constructing them needs no registry); the *registration*
 * is driven per loader — Fabric registers directly during init, NeoForge via `RegisterEvent` —
 * with each loader iterating [components], [items], and [chunkGeneratorCodecs]. See each loader's
 * entrypoint.
 */
object AgeContent {
    /**
     * Data component stored on a Descriptive Book stack: the id of the Age it links to.
     * `persistent` = saved to disk; `networkSynchronized` = sent to the client.
     */
    val AGE_ID: DataComponentType<ResourceLocation> = DataComponentType.builder<ResourceLocation>()
        .persistent(ResourceLocation.CODEC)
        .networkSynchronized(ResourceLocation.STREAM_CODEC)
        .build()

    /** Unstackable so each book keeps its own [AGE_ID] identity. */
    val DESCRIPTIVE_BOOK: Item = DescriptiveBookItem(Item.Properties().stacksTo(1))

    val components: List<Pair<ResourceLocation, DataComponentType<*>>> = listOf(
        "age_id".location() to AGE_ID,
    )

    val items: List<Pair<ResourceLocation, Item>> = listOf(
        "descriptive_book".location() to DESCRIPTIVE_BOOK,
    )

    /** Chunk-generator codecs (Ages persist via Fantasy, so their generator must be serializable). */
    val chunkGeneratorCodecs: List<Pair<ResourceLocation, MapCodec<out ChunkGenerator>>> = listOf(
        "spire".location() to SpireChunkGenerator.CODEC,
        "field".location() to FieldChunkGenerator.CODEC,
    )

    /**
     * Biome-source codecs. Like the generators, an Age's biome source is persisted with it, so the kind
     * has to be nameable — `BiomeSource.CODEC` dispatches over this registry.
     */
    val biomeSourceCodecs: List<Pair<ResourceLocation, MapCodec<out BiomeSource>>> = listOf(
        "age_biomes".location() to AgeBiomeSource.CODEC,
        "region_biomes".location() to RegionBiomeSource.CODEC,
    )

    /**
     * Surface-rule kinds. Ours paints each territory with its own dressing's rules, and like the
     * generator it is persisted with the Age, so the kind has to be nameable — `RuleSource.CODEC`
     * dispatches over this registry.
     */
    val surfaceRuleCodecs: List<Pair<ResourceLocation, MapCodec<out SurfaceRules.RuleSource>>> = listOf(
        "region".location() to RegionRule.CODEC,
    )

    /**
     * Our own carvers. The configured instances that use them are datapack JSON under
     * `data/agesandtheart/worldgen/configured_carver/`; this registers the carver *kinds* those refer to.
     */
    val carvers: List<Pair<ResourceLocation, WorldCarver<*>>> = listOf(
        "erosion".location() to RuleCarver(CarverConfiguration.CODEC.codec(), Weathering.SPIRE),
        "porosity".location() to RuleCarver(CarverConfiguration.CODEC.codec(), Porosity.VUGS),
    )
}
