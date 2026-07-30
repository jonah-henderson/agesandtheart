package co.voik.agesandtheart.worldgen.field

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.age.aspect.Parameter
import co.voik.agesandtheart.location
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.data.worldgen.SurfaceRuleData
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Noises
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
     * The floor of the world — bedrock, fading out just above the bottom, exactly as vanilla closes its own.
     *
     * **Goes first in every palette, so nothing can paint over it.** Jonah's rule, 2026-07-27: a world
     * boundary is not the palette's to decide, and a *material* least of all — "a world of blackstone"
     * should mean the rock is blackstone, not that the world has no bottom. Removing the floor deliberately
     * is a thing the language should eventually be able to say ("no bedrock"), and that is a very different
     * act from a material quietly dissolving it.
     *
     * Relative anchors rather than our own min-Y, so this stays correct if an Age's height band ever moves.
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

    /**
     * A rule that never matches, so whatever follows it decides.
     *
     * How "this terrain named no material" is spelled: a [RegionRule] member that declines leaves the column
     * to the rule beneath it, which is the dressing's own rock.
     *
     * Spelled as *below the bottom of the world*, which no block ever is. The obvious spelling — an empty
     * [layers] — is not available: vanilla's `sequence` rejects an empty list outright ("Need at least 1 rule
     * for a sequence"), and it does so at class-initialisation time, so it fails a long way from here. A
     * `RuleSource` of our own would need registering to serialise, which is a great deal of ceremony for a
     * rule whose entire job is to do nothing.
     */
    val NOTHING: SurfaceRules.RuleSource = SurfaceRules.ifTrue(
        SurfaceRules.not(SurfaceRules.yBlockCheck(VerticalAnchor.bottom(), 0)),
        solid(Blocks.AIR.defaultBlockState()),
    )

    /**
     * What a verdant dressing shows below the soil — the deepslate gradient, and then nothing.
     *
     * **The unconditional `solid(STONE)` that used to end this is gone, and its absence is the point** (step 4).
     * A rule with no condition answers at *every* block, so it swallowed the whole column below the soil and the
     * material a writer named could never show. Declining instead lets the fill's block stand, which is exactly
     * how vanilla gets stone into its own bulk: from `default_block`, not from a rule.
     *
     * The deepslate gradient stays, and stays *conditioned*, because that is what vanilla does — see [Substance]
     * for the check.
     */
    val VERDANT_ROCK: SurfaceRules.RuleSource = deepslateFloor()

    /** Grass over dirt over stone, deepslate fading in at depth; bare gravel wherever the sea covers it. */
    val VERDANT: SurfaceRules.RuleSource = verdantOver(VERDANT_ROCK)

    /** The same soil, over whatever rock the Age was said to be made of — see [madeOf]. */
    fun verdantOver(stones: List<BlockState>): SurfaceRules.RuleSource = verdantOver(mingled(stones))

    /**
     * The same soil, over whatever [rock] the layers below settle on.
     *
     * Taking a rule rather than a block list is what lets a **terrain**'s material sit between the soil and
     * the dressing's own rock: copper spires keep their grass, because the cover is decided above the
     * substance and always was — this only makes the substance something more than one thing can answer for.
     */
    fun verdantOver(rock: SurfaceRules.RuleSource): SurfaceRules.RuleSource =
        layers(worldFloor(), soil(), rock)

    /**
     * Soil, but only inside the named biomes — how a barren dressing gives a named biome somewhere to grow.
     *
     * Deliberately *only the soil layers*: whatever this is laid over resumes a few blocks down, so a
     * cherry grove in a stone world is a patch of ground on rock rather than a column of it. Goes before
     * the rock in [layers], since the first matching rule wins.
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
     * The rock a bare dressing shows when nothing named a material: an andesite skin over a tuff crust,
     * then deepslate at depth.
     *
     * Every block here is deliberately carver-replaceable (`#minecraft:base_stone_overworld`). Cobble was
     * the obvious choice for the crust and is *not* in that tag, so caves would have cut the rock and left
     * cobblestone shells hanging in their mouths; tuff reads the same and carves cleanly.
     *
     * **Declared above [BARE_ROCK], which reads it.** An `object`'s properties initialise in source order,
     * so the other way round leaves this null at startup — the exact failure `CodecCheck` exists to
     * catch, and one that surfaces as an unexplained crash a long way from here.
     */
    /**
     * A **crust**, and since step 4 that is all it is: andesite at the face, tuff for [CRUST_DEPTH] below it,
     * the deepslate gradient far down — and **no unconditional fallback**, so beneath the crust the rock the Age
     * was made of shows through (Jonah's design, 2026-07-29).
     *
     * The tail used to be `solid(STONE)`, which answered at every block and swallowed the column, so a material
     * was invisible under any dressing that painted rock. Removing it is what turns a whole-column repaint into a
     * crust — and the layering falls out of `sequence` being first-non-null-wins alternation: each rule declines
     * once its depth condition stops holding, and the next one, or finally the fill, answers.
     *
     * The same shape works under a biome's own rules: they are more specific and come first, so they take the
     * top few blocks and this shows below them.
     */
    val BARE_ROCK_LAYERS: SurfaceRules.RuleSource = layers(
        where(atSurface(), Blocks.ANDESITE.defaultBlockState()),
        where(withinDepth(CRUST_DEPTH), Blocks.TUFF.defaultBlockState()),
        deepslateFloor(),
    )

    /** Bare weathered rock, no soil at all — for monoliths and the shape sampler. */
    val BARE_ROCK: SurfaceRules.RuleSource = madeOf(BARE_ROCK_LAYERS)

    /**
     * Bare rock made of the named blocks, all the way down — a **material** (design §3.2) applied to the
     * palette, and the first consumer of that hook.
     *
     * Deliberately *not* [BARE_ROCK] with its floor swapped: the andesite crust and the deepslate floor are
     * both statements about what the rock is, and keeping them over a named stone would say "this world is
     * blackstone" while showing three other rocks. A writer who named a substance meant it, so the whole
     * column is that substance. The layering is what you get when you *do not* name one.
     *
     * **Several stones mingle rather than divide** (§3.2): they are mottled through one another at block
     * scale, not given a region each. Division is what naming two *dressings* does, so reading a list as
     * territories would give one piece of geography two spellings and leave mingling with none.
     */
    fun madeOf(stones: List<BlockState>): SurfaceRules.RuleSource = madeOf(mingled(stones))

    /** The same, over whatever [rock] the layers below settle on — see [verdantOver] for why that matters. */
    fun madeOf(rock: SurfaceRules.RuleSource): SurfaceRules.RuleSource = layers(worldFloor(), rock)

    /**
     * The blocks these registry ids name, dropping any this pack does not have.
     *
     * Shared by every aspect that wears a material (design §3.2), so a terrain and a dressing resolve one the
     * same way. A block a mod has since removed is dropped with a complaint rather than failing the Age:
     * an Age must still open, and the rest of a mingling still reads.
     */
    fun materialsNamed(names: List<String>): List<BlockState> = names
        .filter { it != Parameter.UNCHANGED }
        .mapNotNull { named ->
            val id = ResourceLocation.tryParse(named) ?: return@mapNotNull null
            BuiltInRegistries.BLOCK.getOptional(id).map { block -> block.defaultBlockState() }.orElseGet {
                Constants.LOG.warn("An Age names a block this pack does not have: {}", named)
                null
            }
        }

    /**
     * Several blocks mottled through one another, the last standing as the ground everything else is
     * scattered over.
     *
     * Bands of one noise rather than a noise each, so the proportions are exact and no two materials can
     * ever want the same block — nested `mottled` conditions would leave the second material's share
     * depending on where the first happened to fall.
     *
     * Divided **evenly**, which is what an unqualified list should mean and matches the resolver's own rule
     * that an even division is the honest outcome when nothing said otherwise. Weighting these by the share
     * ladder wants the noise's distribution measured first, the way `ClaimTilt` measures the region noise —
     * see design §3.2.
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
     * Our own noise, at a deliberately tiny scale — two blocks or so, which is as close to *evenly
     * intermixed* as surface rules can get (Jonah's call, 2026-07-27, after walking it).
     *
     * `Noises.SURFACE` was the first choice, borrowed because it was available. It gave patches of maybe
     * sixty blocks, which read well but read as *patches* — and patch size turns out to be a thing worth
     * saying deliberately rather than inheriting from whichever noise we happened to reach for. So mingling
     * defaults to as fine as it goes, and the coarse version comes back as a **quantifier in the grammar**
     * (see `notes/the-art-implementation-plan.md`, Phase 4) rather than as a constant nobody chose.
     *
     * Registered as datapack content, so a pack can retune the scale without touching code.
     */
    private val MINGLE_NOISE: ResourceKey<NormalNoise.NoiseParameters> =
        ResourceKey.create(Registries.NOISE, "mingle".location())

    /**
     * The noise the mottling reads, borrowed rather than registered.
     *
     * Vanilla uses `Noises.SURFACE` in its *own* surface rules, which would be a correlation worth worrying
     * about — except that the palettes taking a material are ours, and vanilla's rules never run over an Age
     * wearing one. [VANILLA_OVERWORLD] cannot take a material at all, so the two never meet.
     */
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
     * How far a crust reaches below the face, in blocks.
     *
     * **A crust is now a real stratum rather than a detail of the skin**, which is what removing the
     * unconditional tail from [BARE_ROCK_LAYERS] bought: a biome's own soil still wins the top few blocks — a
     * more specific rule comes first — this shows beneath it, and the Age's own material shows beneath *that*.
     * Verified block by block: andesite at the face, tuff below it, blackstone deeper.
     *
     * **Fifteen, and the barren Ages moving with it is accepted** (Jonah, 2026-07-29). Measured before taking it:
     * at fifteen the parity set loses `pbare` (40 of 81 chunks) and `pvoid` (81 of 81), neither of which names a
     * material — so step 4's *"must not move: any Age that names no material"* is deliberately broken here, and
     * only here. At two the mechanism alone is parity-clean, which is how the two changes were told apart.
     *
     * **A hard number is a placeholder.** The vocabulary pass wants layering that can express *"an Age made of
     * layers of granite, tuff, blackstone, gold ore"* and behave sensibly, which is a stratum *list* rather than
     * one depth — see the tooling backlog. Not needed yet.
     */
    private const val CRUST_DEPTH = 15
    /** How far the bedrock floor dissolves upward, matching vanilla's own five-block fade. */
    private const val BEDROCK_FADE = 5

    private const val DEEPSLATE_SOLID_BELOW = -8
    private const val DEEPSLATE_ABSENT_ABOVE = 8
}
