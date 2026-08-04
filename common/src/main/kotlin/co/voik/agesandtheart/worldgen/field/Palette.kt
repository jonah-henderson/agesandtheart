package co.voik.agesandtheart.worldgen.field

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.age.aspect.Parameter
import co.voik.agesandtheart.location
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.data.worldgen.SurfaceRuleData
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.Identifier
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Noises
import net.minecraft.world.level.levelgen.SurfaceRules
import net.minecraft.world.level.levelgen.VerticalAnchor
import net.minecraft.world.level.levelgen.placement.CaveSurface
import net.minecraft.world.level.levelgen.synth.NormalNoise

/**
 * What an Age's terrain is *made of*, as opposed to what shape it is — written in vanilla's own
 * [SurfaceRules] language, so a palette is serialisable and travels in a recipe.
 *
 * **Why this wrapper exists: `abovePreliminarySurface` is unsound here** and only re-exporting the safe
 * subset makes it unreachable. It compares against a heightmap interpolated across a 16-block cell, which
 * our terrain can outrun in either direction; [NearTheSurface] is the same rule asked of the column
 * itself. It is the *only* unsound one — `hole`, `bandlands` and `temperature` read noise the
 * `RandomState` makes for itself, or the biome.
 *
 * The vocabulary is otherwise vanilla's: conditions compose, so `and` is nesting, and [NearTheSurface] is
 * the one kind we write ourselves.
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

    /** True where the column is dry — i.e. not beneath the sea. */
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

    /**
     * The floor of the world — bedrock, fading out just above the bottom, as vanilla closes its own.
     *
     * **Goes first in every palette, so nothing can paint over it**: a world boundary is not the palette's
     * to decide, and a material least of all. Relative anchors rather than our own min-Y, so this stays
     * correct if an Age's height band moves.
     */
    fun worldFloor(): SurfaceRules.RuleSource = SurfaceRules.ifTrue(
        SurfaceRules.verticalGradient(
            "bedrock_floor",
            VerticalAnchor.bottom(),
            VerticalAnchor.aboveBottom(BEDROCK_FADE),
        ),
        solid(Blocks.BEDROCK.defaultBlockState()),
    )

    // --- Ready-made palettes ---

    /**
     * **Vanilla's own overworld palette**, biome for biome, standing on the [terrain] it is dressing.
     *
     * This is `overworldLike`'s own three-part shape with one substitution: bedrock first so nothing can
     * paint over the world's floor, then the biome tree **gated on the surface**, then the deepslate
     * gradient ungated, so depth still reads as depth on a cave wall. Vanilla spells that gate
     * `abovePreliminarySurface` and it is the one thing standing between a cave floor and a lawn — see
     * [NearTheSurface] for why ours has to ask the column rather than an interpolated heightmap.
     *
     * `aboveGround = false` therefore drops vanilla's version rather than disabling the idea, and
     * `bedrockFloor = false` drops its bedrock in favour of [worldFloor], which is the same gradient in the
     * position every palette here puts it. Still taken from `net.minecraft.data.worldgen` rather than the
     * registry, since only the builder lets those flags be chosen at all.
     */
    fun vanillaOverworldOn(terrain: TerrainField): SurfaceRules.RuleSource = layers(
        worldFloor(),
        SurfaceRules.ifTrue(
            NearTheSurface(terrain),
            SurfaceRuleData.overworldLike(
                /* aboveGround = */ false,
                /* bedrockRoof = */ false,
                /* bedrockFloor = */ false,
            ),
        ),
        deepslateFloor(),
    )

    /**
     * A material laid over the ground instead of the biome's own skin — `Surface`'s answer when a writer
     * named one.
     *
     * [vanillaOverworldOn]'s shape with the biome tree taken out: the world's floor first so nothing can
     * paint over it, the material gated on the surface *and* on depth so it is a skin rather than a
     * column, and the deepslate gradient beneath so depth still reads as depth on a cave wall. Several
     * materials mingle at the scale [mingled] uses; a knob for that scale would have nothing to turn,
     * since the noise it bands is registered pack content.
     */
    fun skinOf(terrain: TerrainField, blocks: List<BlockState>): SurfaceRules.RuleSource = layers(
        worldFloor(),
        SurfaceRules.ifTrue(NearTheSurface(terrain), SurfaceRules.ifTrue(withinDepth(SKIN_DEPTH), mingled(blocks))),
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
    val NOTHING: SurfaceRules.RuleSource = SurfaceRules.ifTrue(
        SurfaceRules.not(SurfaceRules.yBlockCheck(VerticalAnchor.bottom(), 0)),
        solid(Blocks.AIR.defaultBlockState()),
    )

    /**
     * What a verdant dressing shows below the soil — the deepslate gradient, and then nothing.
     *
     * **No unconditional tail, deliberately.** A rule with no condition answers at *every* block, so it
     * swallows the column below the soil and a named material never shows. Declining lets the fill's block
     * stand, which is how vanilla gets stone into its own bulk: from `default_block`, not a rule.
     */
    val VERDANT_ROCK: SurfaceRules.RuleSource = deepslateFloor()

    /** Grass over dirt over stone, deepslate fading in at depth; bare gravel wherever the sea covers it. */
    val VERDANT: SurfaceRules.RuleSource = verdantOver(VERDANT_ROCK)

    /** The same soil, over whatever rock the Age was said to be made of — see [madeOf]. */
    fun verdantOver(stones: List<BlockState>): SurfaceRules.RuleSource = verdantOver(mingled(stones))

    /**
     * The same soil, over whatever [rock] the layers below settle on. Taking a rule rather than a block
     * list is what lets a terrain's material sit between the soil and the dressing's own rock.
     */
    fun verdantOver(rock: SurfaceRules.RuleSource): SurfaceRules.RuleSource =
        layers(worldFloor(), soil(), rock)

    /**
     * Soil, but only inside the named biomes. Only the soil *layers*, so whatever this is laid over
     * resumes a few blocks down — a cherry grove in a stone world is a patch of ground on rock rather than
     * a column of it. Goes before the rock in [layers], since the first matching rule wins.
     */
    fun soilIn(biomes: List<ResourceKey<Biome>>): SurfaceRules.RuleSource =
        SurfaceRules.ifTrue(inBiomes(*biomes.toTypedArray()), soil())

    /** Grass and dirt where it is dry, gravel where the sea covers it. */
    private fun soil(): SurfaceRules.RuleSource = layers(
        where(atSurface(), aboveWater(), Blocks.GRASS_BLOCK.defaultBlockState()),
        where(withinDepth(SOIL_DEPTH), aboveWater(), Blocks.DIRT.defaultBlockState()),
        // Only reachable when the layers above failed their dryness test, i.e. under the sea.
        where(withinDepth(SOIL_DEPTH), Blocks.GRAVEL.defaultBlockState()),
    )

    /**
     * A **crust**: andesite at the face, tuff for [CRUST_DEPTH] below it, the deepslate gradient far down —
     * and **no unconditional fallback**, so the rock the Age is made of shows through beneath it. The
     * layering falls out of `sequence` being first-non-null-wins: each rule declines once its depth
     * condition stops holding, and the next one, or finally the fill, answers.
     *
     * Every block here is carver-replaceable (`#minecraft:base_stone_overworld`). Cobble is the obvious
     * choice for the crust and is *not* in that tag, so caves would leave cobblestone shells in their
     * mouths; tuff reads the same and carves cleanly.
     *
     * **Declared above [BARE_ROCK], which reads it** — an `object`'s properties initialise in source order,
     * so the other way round leaves this null at startup.
     */
    val BARE_ROCK_LAYERS: SurfaceRules.RuleSource = layers(
        where(atSurface(), Blocks.ANDESITE.defaultBlockState()),
        where(withinDepth(CRUST_DEPTH), Blocks.TUFF.defaultBlockState()),
        deepslateFloor(),
    )

    /** Bare weathered rock, no soil at all — for monoliths and the shape sampler. */
    val BARE_ROCK: SurfaceRules.RuleSource = madeOf(BARE_ROCK_LAYERS)

    /**
     * Bare rock made of the named blocks, all the way down — a material (§3.2) applied to the palette.
     *
     * Not [BARE_ROCK] with its floor swapped: the andesite crust and deepslate floor are both statements
     * about what the rock is, so keeping them over a named stone would say "this world is blackstone"
     * while showing three other rocks. **Several stones mingle rather than divide**, mottled at block
     * scale — division is what naming two presets does.
     */
    fun madeOf(stones: List<BlockState>): SurfaceRules.RuleSource = madeOf(mingled(stones))

    /** The same, over whatever [rock] the layers below settle on — see [verdantOver] for why that matters. */
    fun madeOf(rock: SurfaceRules.RuleSource): SurfaceRules.RuleSource = layers(worldFloor(), rock)

    /**
     * The blocks these registry ids name. A block a mod has since removed is dropped with a complaint
     * rather than failing the Age: it must still open, and the rest of a mingling still reads.
     */
    fun materialsNamed(names: List<String>): List<BlockState> = names
        .filter { it != Parameter.UNCHANGED }
        .mapNotNull { named ->
            val id = Identifier.tryParse(named) ?: return@mapNotNull null
            // `orElseGet { null }` no longer compiles: Minecraft ships nullness annotations now, so Kotlin
            // holds `Optional`'s supplier to returning something. Reads better as a guard in any case.
            val block = BuiltInRegistries.BLOCK.getOptional(id).orElse(null)
            if (block == null) {
                Constants.LOG.warn("An Age names a block this pack does not have: {}", named)
                return@mapNotNull null
            }
            block.defaultBlockState()
        }

    /**
     * Several blocks mottled through one another, the last standing as the ground the rest scatter over.
     *
     * Bands of **one** noise rather than a noise each, so the proportions are exact and no two materials
     * can want the same block — nested `mottled` conditions would leave the second material's share
     * depending on where the first fell. Divided evenly, which is what an unqualified list should mean.
     */
    fun mingled(blocks: List<BlockState>): SurfaceRules.RuleSource {
        val ground = blocks.lastOrNull() ?: return PLAIN_STONE
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

    /** The fallback when an Age names no palette — what every field Age looked like before palettes. */
    val PLAIN_STONE: SurfaceRules.RuleSource =
        layers(worldFloor(), solid(Blocks.STONE.defaultBlockState()))

    private fun deepslateFloor(): SurfaceRules.RuleSource =
        where(
            fadingBelowY("deepslate", DEEPSLATE_SOLID_BELOW, DEEPSLATE_ABSENT_ABOVE),
            Blocks.DEEPSLATE.defaultBlockState(),
        )

    private const val SOIL_DEPTH = 3

    /**
     * How far a crust reaches below the face, in blocks: a biome's own soil wins the top few blocks, this
     * shows beneath it, and the Age's own material shows beneath that.
     *
     * **A placeholder.** Real strata want a *list* of layers rather than one depth — see the tooling
     * backlog.
     */
    private const val CRUST_DEPTH = 15
    /** How far the bedrock floor dissolves upward, matching vanilla's own five-block fade. */
    private const val BEDROCK_FADE = 5

    private const val DEEPSLATE_SOLID_BELOW = -8
    private const val DEEPSLATE_ABSENT_ABOVE = 8
}
