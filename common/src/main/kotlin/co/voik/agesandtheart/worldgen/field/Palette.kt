package co.voik.agesandtheart.worldgen.field

import net.minecraft.data.worldgen.SurfaceRuleData
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.SurfaceRules
import net.minecraft.world.level.levelgen.VerticalAnchor
import net.minecraft.world.level.levelgen.placement.CaveSurface
import net.minecraft.world.level.levelgen.synth.NormalNoise

/**
 * What an Age's terrain is *made of*, as opposed to what shape it is. Shape comes from a
 * [TerrainField], which lays a single placeholder block; this repaints it afterwards, so the two
 * concerns never touch (see `notes/terrain-architecture.md`).
 *
 * Palettes are written in vanilla's own [SurfaceRules] language — reused rather than reinvented, and
 * serialisable, so a palette can travel in an Age recipe like everything else.
 *
 * **Why this wrapper exists.** Vanilla evaluates surface rules through machinery reachable only via a
 * noise pipeline, which a field Age does not have: it runs on `NoiseGeneratorSettings.dummy()`, whose
 * router is inert. Exactly one of vanilla's conditions reads that router — `abovePreliminarySurface`,
 * which asks `NoiseChunk.preliminarySurfaceLevel`; against an inert router that returns
 * `Integer.MAX_VALUE`, so the condition is false everywhere and anything beneath it is **silently**
 * dead. Rather than trusting ourselves to remember that, it is simply not reachable from our code.
 *
 * It is the *only* one. `hole` and `bandlands` look like they should be in the same boat and are not:
 * both read noise the `RandomState` instantiates for itself (`Noises.SURFACE` via
 * `SurfaceSystem.getSurfaceDepth`, and the clay-bands noise) rather than anything from the router. So is
 * `temperature`, which asks the *biome* whether it is `coldEnoughToSnow`. All three are sound here.
 *
 * Note the vocabulary is closed to us: `SurfaceRules.Context` is protected and `Condition` is
 * package-private, so we can *compose* vanilla's conditions but never write a new kind. Hence `and` is
 * expressed by nesting rather than by a combinator of our own.
 */
object Palette {

    // --- Structure ---

    /** Try each layer in turn, keeping the first that matches — the backbone of every palette. */
    fun layers(vararg layers: SurfaceRules.RuleSource): SurfaceRules.RuleSource = SurfaceRules.sequence(*layers)

    /** Always this block. Goes last in [layers], as the fallback. */
    fun solid(block: BlockState): SurfaceRules.RuleSource = SurfaceRules.state(block)

    /** [block] wherever [condition] holds. */
    fun where(condition: SurfaceRules.ConditionSource, block: BlockState): SurfaceRules.RuleSource =
        SurfaceRules.ifTrue(condition, solid(block))

    /** [block] where *both* conditions hold — an `and`, which the language spells as nesting. */
    fun where(first: SurfaceRules.ConditionSource, second: SurfaceRules.ConditionSource, block: BlockState): SurfaceRules.RuleSource =
        SurfaceRules.ifTrue(first, where(second, block))

    // --- Conditions: everything but `abovePreliminarySurface` ---

    /** True only in the named biomes. Meaningless until an Age has more than one — see `AgeBiomeSource`. */
    fun inBiomes(vararg biomes: ResourceKey<Biome>): SurfaceRules.ConditionSource =
        SurfaceRules.isBiome(*biomes)

    /**
     * True where the biome at this position is cold enough for snow rather than rain — vanilla's own
     * test, and the reason snowy slopes get powder snow while their neighbours get grass. Reads the
     * biome, not the noise router, so it is sound for us.
     */
    fun coldEnoughToSnow(): SurfaceRules.ConditionSource = SurfaceRules.temperature()

    /**
     * True where the soil runs out — vanilla's `hole`, which is how ground that should be grassy comes
     * out as bare stone in patches. Reads the surface-depth noise, which is real for us.
     */
    fun soilless(): SurfaceRules.ConditionSource = SurfaceRules.hole()

    /** The exposed skin of the terrain — the topmost solid block of a column. */
    fun atSurface(): SurfaceRules.ConditionSource = withinDepth(0)

    /** Within [blocks] of the surface: the soil layer beneath the skin. */
    fun withinDepth(blocks: Int): SurfaceRules.ConditionSource =
        SurfaceRules.stoneDepthCheck(blocks, false, CaveSurface.FLOOR)

    /** Below a fixed height — a hard stratum boundary. */
    fun belowY(y: Int): SurfaceRules.ConditionSource =
        SurfaceRules.not(SurfaceRules.yBlockCheck(VerticalAnchor.absolute(y), 0))

    /**
     * A *soft* stratum boundary: certainly true at [solidBelowY] and below, certainly false at
     * [absentAboveY] and above, dissolving randomly in between. This is what makes deepslate fade into
     * stone instead of stopping at a flat seam, and it is the most useful condition here by some way.
     * [name] seeds the randomness, so two bands with different names interleave independently.
     */
    fun fadingBelowY(name: String, solidBelowY: Int, absentAboveY: Int): SurfaceRules.ConditionSource =
        SurfaceRules.verticalGradient(name, VerticalAnchor.absolute(solidBelowY), VerticalAnchor.absolute(absentAboveY))

    /** True where the column is dry — i.e. not beneath the ambient sea. */
    fun aboveWater(): SurfaceRules.ConditionSource = SurfaceRules.waterBlockCheck(-1, 0)

    /** True on sharply sloping ground: the seam where soil gives way to bare rock. */
    fun onSteepGround(): SurfaceRules.ConditionSource = SurfaceRules.steep()

    /** Scatter driven by a registered noise, for mottling one material through another. */
    fun mottled(
        noise: ResourceKey<NormalNoise.NoiseParameters>,
        min: Double,
        max: Double,
    ): SurfaceRules.ConditionSource = SurfaceRules.noiseCondition(noise, min, max)

    fun not(condition: SurfaceRules.ConditionSource): SurfaceRules.ConditionSource = SurfaceRules.not(condition)

    /** Vanilla's banded badlands clay, as a rule rather than a condition. Sound: its own noise. */
    fun clayBands(): SurfaceRules.RuleSource = SurfaceRules.bandlands()

    // --- Ready-made palettes ---

    /**
     * **Vanilla's own overworld palette**, biome for biome: grass and podzol and mycelium, red sand in
     * the badlands with their clay banding, gravel and magma under the oceans, powder snow on the peaks.
     * The natural partner to `AgeBiomeSource` — once an Age has real biomes, this dresses them the way a
     * player expects without us re-deriving several hundred lines of rules.
     *
     * Note the `aboveGround = false`: that is not a description of the world but the switch that drops
     * the `abovePreliminarySurface` wrapper vanilla otherwise puts around the whole tree, which would be
     * dead here for the reason given above. Everything inside it is sound. `bedrockFloor = true` closes
     * the bottom of the world at our own min-Y; `bedrockRoof = false` leaves the sky open.
     *
     * Taken from `net.minecraft.data.worldgen` deliberately rather than from the registry: the
     * datapack-loaded `minecraft:overworld` noise settings carry the `aboveGround = true` variant, which
     * is the one we cannot use.
     */
    val VANILLA_OVERWORLD: SurfaceRules.RuleSource =
        SurfaceRuleData.overworldLike(/* aboveGround = */ false, /* bedrockRoof = */ false, /* bedrockFloor = */ true)

    /** Grass over dirt over stone, deepslate fading in at depth; bare gravel wherever the sea covers it. */
    val VERDANT: SurfaceRules.RuleSource = layers(
        where(atSurface(), aboveWater(), Blocks.GRASS_BLOCK.defaultBlockState()),
        where(withinDepth(SOIL_DEPTH), aboveWater(), Blocks.DIRT.defaultBlockState()),
        // Only reachable when the layers above failed their dryness test, i.e. under the sea.
        where(withinDepth(SOIL_DEPTH), Blocks.GRAVEL.defaultBlockState()),
        deepslateFloor(),
        solid(Blocks.STONE.defaultBlockState()),
    )

    /**
     * Bare weathered rock, no soil at all — for monoliths and the shape sampler.
     *
     * Every block here is deliberately carver-replaceable (`#minecraft:base_stone_overworld`). Cobble
     * was the obvious choice for the crust and is *not* in that tag, so caves would have cut the rock
     * and left cobblestone shells hanging in their mouths; tuff reads the same and carves cleanly.
     */
    val BARE_ROCK: SurfaceRules.RuleSource = layers(
        where(atSurface(), Blocks.ANDESITE.defaultBlockState()),
        where(withinDepth(CRUST_DEPTH), Blocks.TUFF.defaultBlockState()),
        deepslateFloor(),
        solid(Blocks.STONE.defaultBlockState()),
    )

    /** The fallback when an Age names no palette — what every field Age looked like before palettes. */
    val PLAIN_STONE: SurfaceRules.RuleSource = solid(Blocks.STONE.defaultBlockState())

    private fun deepslateFloor(): SurfaceRules.RuleSource =
        where(
            fadingBelowY("deepslate", DEEPSLATE_SOLID_BELOW, DEEPSLATE_ABSENT_ABOVE),
            Blocks.DEEPSLATE.defaultBlockState(),
        )

    private const val SOIL_DEPTH = 3
    private const val CRUST_DEPTH = 2
    private const val DEEPSLATE_SOLID_BELOW = -8
    private const val DEEPSLATE_ABSENT_ABOVE = 8
}
