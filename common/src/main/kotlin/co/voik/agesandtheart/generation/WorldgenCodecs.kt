package co.voik.agesandtheart.generation

import co.voik.agesandtheart.location
import co.voik.agesandtheart.worldgen.biome.AgeBiomeSource
import co.voik.agesandtheart.worldgen.field.NearTheSurface
import co.voik.agesandtheart.worldgen.field.RegionRule
import com.mojang.serialization.MapCodec
import net.minecraft.resources.Identifier
import net.minecraft.world.level.biome.BiomeSource
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.SurfaceRules

/**
 * The generation kinds that have to be nameable, because an Age is rebuilt from its recipe every time it
 * is opened and what generates it is written down with the level.
 *
 * **Apart from [co.voik.agesandtheart.content.AgeContent] because none of these touches an `Item` or a
 * `Block`**, which is the whole of
 * what a check needs: that object builds every item eagerly and so cannot be loaded once the registries
 * have frozen, and `CodecCheck` therefore kept a hand-written copy of this list rather than iterating it.
 * `NearTheSurface` was missing from the copy, which is the drift a duplicate invites.
 *
 * Here rather than in `worldgen`: the field toolkit is the foundation and these name the composition built
 * on top of it, so filing them under `worldgen` would point the foundation upwards.
 */
object WorldgenCodecs {
    /** Chunk-generator codecs — a level's generator is serialised when it is saved, so it needs one. */
    val chunkGeneratorCodecs: List<Pair<Identifier, MapCodec<out ChunkGenerator>>> = listOf(
        // Renamed from `field` with the class: the generator reaches past field terrain now. Save formats
        // are still free to move (CLAUDE.md), so this is a rename rather than an alias.
        "age".location() to AgeChunkGenerator.CODEC,
    )

    /**
     * Biome-source codecs. Like the generators, an Age's biome source is persisted with it, so the kind
     * has to be nameable — `BiomeSource.CODEC` dispatches over this registry.
     */
    val biomeSourceCodecs: List<Pair<Identifier, MapCodec<out BiomeSource>>> = listOf(
        "age_biomes".location() to AgeBiomeSource.CODEC,
    )

    /**
     * Surface-rule kinds. Ours is persisted with the Age like the generator, so the kind has to be
     * nameable — `RuleSource.CODEC` dispatches over this registry.
     */
    val surfaceRuleCodecs: List<Pair<Identifier, MapCodec<out SurfaceRules.RuleSource>>> = listOf(
        "region".location() to RegionRule.CODEC,
    )

    /**
     * Surface-*condition* kinds, which is the same story one level down: the palette naming this condition
     * is persisted, so `ConditionSource.CODEC` has to be able to dispatch to it.
     */
    val surfaceConditionCodecs: List<Pair<Identifier, MapCodec<out SurfaceRules.ConditionSource>>> = listOf(
        NearTheSurface.ID to NearTheSurface.CODEC.codec(),
    )
}
