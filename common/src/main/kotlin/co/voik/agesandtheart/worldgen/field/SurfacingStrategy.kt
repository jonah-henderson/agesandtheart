package co.voik.agesandtheart.worldgen.field

import co.voik.agesandtheart.location
import net.minecraft.core.registries.Registries
import net.minecraft.data.worldgen.SurfaceRuleData
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.SurfaceRules
import net.minecraft.world.level.levelgen.VerticalAnchor
import net.minecraft.world.level.levelgen.placement.CaveSurface
import net.minecraft.world.level.levelgen.synth.NormalNoise

/**
 * **Which surface rule an Age wears** — three of them, and nothing else here is public.
 *
 * A `SurfaceRules.RuleSource` says *how* to surface a column; this says *which* rule does it, and
 * [co.voik.agesandtheart.age.aspect.Surface] is the aspect that decides. The three are the whole of what
 * an Age can be: the biome's own rule ([delegatedToBiomes]), one or more materials laid over the lot
 * ([laidOn]), or none at all ([SUPPRESSED]), which lets the [TerrainFill] beneath show through.
 *
 * The private half is how those three are written rather than a language anyone else speaks.
 *
 * **The one substantive difference from vanilla is `abovePreliminarySurface`**, which is unsound for our
 * terrain: it compares against a heightmap interpolated across a 16-block cell, which our shapes outrun in
 * both directions. [NearTheSurface] is the same rule asked of the column itself. Every other condition
 * vanilla offers reads noise the `RandomState` makes for itself, or the biome, and is sound as it stands.
 *
 * A rule is **data**, which is what lets one travel in a recipe and rebuild the same world on every open.
 */
object SurfacingStrategy {

    // --- Structure ---

    /** Try each layer in turn, keeping the first that matches — the backbone of every palette. */
    private fun layers(vararg layers: SurfaceRules.RuleSource): SurfaceRules.RuleSource = SurfaceRules.sequence(*layers)

    /** Always this block. Goes last in [layers], as the fallback. */
    private fun solid(block: BlockState): SurfaceRules.RuleSource = SurfaceRules.state(block)

    /** [block] wherever [condition] holds. */
    private fun where(condition: SurfaceRules.ConditionSource, block: BlockState): SurfaceRules.RuleSource =
        SurfaceRules.ifTrue(condition, solid(block))

    /** [block] where *both* conditions hold — an `and`, which the language spells as nesting. */
    private fun where(first: SurfaceRules.ConditionSource, second: SurfaceRules.ConditionSource, block: BlockState): SurfaceRules.RuleSource =
        SurfaceRules.ifTrue(first, where(second, block))

    // --- Conditions ---

    /** Within [blocks] of the surface: the soil layer beneath the skin. */
    private fun withinDepth(blocks: Int): SurfaceRules.ConditionSource =
        SurfaceRules.stoneDepthCheck(blocks, false, CaveSurface.FLOOR)

    /**
     * A *soft* stratum boundary: certainly true at [solidBelowY] and below, certainly false at
     * [absentAboveY] and above, dissolving randomly in between. This is what makes deepslate fade into
     * stone instead of stopping at a flat seam, and it is the most useful condition here by some way.
     * [name] seeds the randomness, so two bands with different names interleave independently.
     */
    private fun fadingBelowY(name: String, solidBelowY: Int, absentAboveY: Int): SurfaceRules.ConditionSource =
        SurfaceRules.verticalGradient(name, VerticalAnchor.absolute(solidBelowY), VerticalAnchor.absolute(absentAboveY))

    /** Scatter driven by a registered noise, for mottling one material through another. */
    private fun mottled(
        noise: ResourceKey<NormalNoise.NoiseParameters>,
        min: Double,
        max: Double,
    ): SurfaceRules.ConditionSource = SurfaceRules.noiseCondition(noise, min, max)

    private fun not(condition: SurfaceRules.ConditionSource): SurfaceRules.ConditionSource = SurfaceRules.not(condition)

    /**
     * The floor of the world — bedrock, fading out just above the bottom, as vanilla closes its own.
     *
     * **Goes first in every palette, so nothing can paint over it**: a world boundary is not the palette's
     * to decide, and a material least of all. Relative anchors rather than our own min-Y, so this stays
     * correct if an Age's height band moves.
     */
    private fun worldFloor(): SurfaceRules.RuleSource = SurfaceRules.ifTrue(
        SurfaceRules.verticalGradient(
            "bedrock_floor",
            VerticalAnchor.bottom(),
            VerticalAnchor.aboveBottom(BEDROCK_FADE),
        ),
        solid(Blocks.BEDROCK.defaultBlockState()),
    )

    // --- The three an Age can wear ---

    /**
     * **Vanilla's own overworld surface**, biome for biome, standing on the [terrain] it is dressing.
     *
     * One substitution, and it is the reason this function exists: `aboveGround = false` drops vanilla's
     * `abovePreliminarySurface` gate and [NearTheSurface] takes its place. That gate is the one thing
     * standing between a cave floor and a lawn, and vanilla's version interpolates a heightmap across a
     * 16-block cell, which our terrain outruns in both directions — see [NearTheSurface].
     *
     * Its **bedrock is vanilla's own**: `overworldLike` puts the floor first in its sequence, ahead of the
     * surface tree, which is exactly where a world's floor has to go. Taken from `net.minecraft.data.worldgen`
     * rather than the registry, since only the builder lets those flags be chosen at all.
     */
    fun delegatedToBiomes(terrain: TerrainField): SurfaceRules.RuleSource =
        SurfaceRules.ifTrue(
            NearTheSurface(terrain),
            SurfaceRuleData.overworldLike(
                /* aboveGround = */ false,
                /* bedrockRoof = */ false,
                /* bedrockFloor = */ true,
            ),
        )

    /**
     * A material laid over the ground instead of the biome's own skin — `Surface`'s answer when a writer
     * named one.
     *
     * [delegatedToBiomes]'s shape with the biome tree taken out: the world's floor first so nothing can
     * paint over it, the material gated on the surface *and* on depth so it is a skin rather than a
     * column, and the deepslate gradient beneath so depth still reads as depth on a cave wall.
     *
     * Several materials mingle at the one scale [MINGLE_NOISE] carries. A writer cannot ask for a coarser
     * speckle: `noiseCondition` names a *registered* noise, so a per-Age number would need a condition
     * source of our own, the way [NearTheSurface] carries a terrain field.
     */
    fun laidOn(terrain: TerrainField, blocks: List<BlockState>): SurfaceRules.RuleSource = layers(
        worldFloor(),
        SurfaceRules.ifTrue(NearTheSurface(terrain), SurfaceRules.ifTrue(withinDepth(SKIN_DEPTH), mingled(blocks))),
        deepslateFloor(),
    )

    /**
     * The same skin over **vanilla's** rock, which knows where its own surface is.
     *
     * [NearTheSurface] reads a `TerrainField`, and an Age wearing vanilla's terrain has none — so the face
     * is vanilla's `ON_FLOOR` instead, which is the condition its own rules are written against.
     */
    fun laidOnVanilla(blocks: List<BlockState>): SurfaceRules.RuleSource = layers(
        worldFloor(),
        SurfaceRules.ifTrue(SurfaceRules.ON_FLOOR, SurfaceRules.ifTrue(withinDepth(SKIN_DEPTH), mingled(blocks))),
        deepslateFloor(),
    )

    /** How far a named skin reaches below the face — thin, since below it is what the Age is made of. */
    private const val SKIN_DEPTH = 2

    /**
     * A rule that never matches, so whatever follows it decides — how "named no material" is spelled.
     *
     * Written as *below the bottom of the world*, which no block is. An empty [layers] is not available:
     * vanilla's `sequence` rejects an empty list at class-initialisation time, so it fails far from here.
     */
    val SUPPRESSED: SurfaceRules.RuleSource = SurfaceRules.ifTrue(
        SurfaceRules.not(SurfaceRules.yBlockCheck(VerticalAnchor.bottom(), 0)),
        solid(Blocks.AIR.defaultBlockState()),
    )

    /**
     * Several blocks mottled through one another, the last standing as the ground the rest scatter over.
     *
     * Bands of **one** noise rather than a noise each, so the proportions are exact and no two materials
     * can want the same block — nested `mottled` conditions would leave the second material's share
     * depending on where the first fell. Divided evenly, which is what an unqualified list should mean.
     */
    private fun mingled(blocks: List<BlockState>): SurfaceRules.RuleSource {
        // Nothing named is nothing said: decline, and the fill the Age was made of stands.
        val ground = blocks.lastOrNull() ?: return SUPPRESSED
        val scattered = blocks.dropLast(1)
        if (scattered.isEmpty()) return solid(ground)
        val bandWidth = (MOTTLE_RANGE.second - MOTTLE_RANGE.first) / blocks.size
        return layers(
            *scattered.mapIndexed { band, block ->
                val from = MOTTLE_RANGE.first + band * bandWidth
                where(mottled(MINGLE_NOISE, from, from + bandWidth), block)
            }.toTypedArray(),
            solid(ground),
        )
    }

    /**
     * Our own noise, at a deliberately tiny scale — two blocks or so, which is as close to evenly
     * intermixed as surface rules get. Registered as datapack content, so a pack can retune the scale.
     */
    private val MINGLE_NOISE: ResourceKey<NormalNoise.NoiseParameters> =
        ResourceKey.create(Registries.NOISE, "mingle".location())

    /** The full span of [MINGLE_NOISE]'s output, divided into one band per material. */
    private val MOTTLE_RANGE = -1.0 to 1.0

    private fun deepslateFloor(): SurfaceRules.RuleSource =
        where(
            fadingBelowY("deepslate", DEEPSLATE_SOLID_BELOW, DEEPSLATE_ABSENT_ABOVE),
            Blocks.DEEPSLATE.defaultBlockState(),
        )

    /** How far the bedrock floor dissolves upward, matching vanilla's own five-block fade. */
    private const val BEDROCK_FADE = 5

    private const val DEEPSLATE_SOLID_BELOW = -8
    private const val DEEPSLATE_ABSENT_ABOVE = 8
}
