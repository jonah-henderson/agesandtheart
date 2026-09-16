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
 * ([laidOn]), or none at all ([NO_SKIN]), which lays only the world's floor and lets the [TerrainFill]
 * beneath show through.
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

    // --- Conditions ---

    /** Within [blocks] of the surface: the soil layer beneath the skin. */
    private fun withinDepth(blocks: Int): SurfaceRules.ConditionSource =
        SurfaceRules.stoneDepthCheck(blocks, false, CaveSurface.FLOOR)

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

    /**
     * The ceiling of the world — bedrock, fading in just below the top, and the mirror of [worldFloor].
     *
     * **Only an Age whose rock reaches the ceiling wears one**, which is why it is applied over a chosen
     * rule rather than being part of every palette: a surface rule paints blocks that already exist, so
     * this would turn a canyon's plateau — which genuinely stands at the top of the world — into bedrock.
     */
    private fun worldRoof(): SurfaceRules.RuleSource = SurfaceRules.ifTrue(
        // **Negated, unlike the floor.** A vertical gradient is certainly true at its lower anchor and
        // certainly false at its upper one, so read straight it says "not the roof"; vanilla's own nether
        // roof inverts it for exactly this reason.
        SurfaceRules.not(
            SurfaceRules.verticalGradient(
                "bedrock_roof",
                VerticalAnchor.belowTop(BEDROCK_FADE),
                VerticalAnchor.top(),
            ),
        ),
        solid(Blocks.BEDROCK.defaultBlockState()),
    )

    /**
     * [rule] with the world closed overhead — what a landform solid to the ceiling wears so that its own
     * roof cannot be mined through (`Terrain.roofsTheWorld`).
     *
     * **First in the sequence**, for the same reason the floor is: a world boundary is not the palette's to
     * decide, and a material least of all.
     *
     * **And [rule] is kept out of the band entirely**, which is not tidiness. [worldRoof] fades its bedrock
     * over those blocks the way vanilla's floor does, so some of them are not bedrock — and a sequence
     * falls through, so each of those reached the palette instead. The palette is the biome's own tree,
     * the ceiling is a rock face by every test it makes, and it dressed the gaps in the world's roof with
     * grass over dirt. Nothing matching leaves the block as the generator laid it, which is the Age's rock.
     */
    fun shutOverhead(rule: SurfaceRules.RuleSource): SurfaceRules.RuleSource =
        layers(worldRoof(), SurfaceRules.ifTrue(SurfaceRules.not(withinTheRoof()), rule))

    /** The band [worldRoof] fades its bedrock through — where the palette has no business. */
    private fun withinTheRoof(): SurfaceRules.ConditionSource =
        SurfaceRules.yBlockCheck(VerticalAnchor.belowTop(BEDROCK_FADE), 0)

    // --- The three an Age can wear ---

    /**
     * **Vanilla's own overworld surface**, biome for biome, standing on the [terrain] it is dressing.
     *
     * One substitution, and it is the reason this function exists: `aboveGround = false` drops vanilla's
     * `abovePreliminarySurface` gate and [NearTheSurface] takes its place. That gate is the one thing
     * standing between a cave floor and a lawn, and vanilla's version interpolates a heightmap across a
     * 16-block cell, which our terrain outruns in both directions — see [NearTheSurface].
     *
     * **Its bedrock is [worldFloor], laid first and outside the gate.** Each vanilla tree carries a floor of
     * its own, but inside [NearTheSurface] that floor was only ever asked of columns near the face, which
     * the bottom of the world is not — so an Age whose ground reached the floor stood on stone over the void.
     *
     * [skin] is the world the book was written over ([co.voik.agesandtheart.age.AgeTemplate.skin]) and not
     * the overworld's for everyone, which is what grew grass on the hills of an infernal Age (Jonah,
     * 2026-08-14, walked). The nether's own tree dresses nether biomes; the overworld's does not know them
     * and falls through to its default, which is dirt with grass on top.
     */
    fun delegatedToBiomes(terrain: TerrainField, skin: SurfaceRules.RuleSource): SurfaceRules.RuleSource =
        layers(worldFloor(), SurfaceRules.ifTrue(NearTheSurface(terrain), skin))

    /**
     * The overworld's own dressing as an Age wears it — bedrock underfoot and none overhead, and the
     * surface tree entered from below rather than from open air, since [NearTheSurface] has already said
     * where the face of our rock is.
     *
     * Only this one is spelled with the builder: the flags exist nowhere else, and the other two worlds
     * have a single tree each with nothing to choose.
     */
    fun overworldsSkin(): SurfaceRules.RuleSource = SurfaceRuleData.overworldLike(
        /* aboveGround = */ false,
        /* bedrockRoof = */ false,
        /* bedrockFloor = */ true,
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

    /**
     * **A world's own skin, as patches over its rock rather than instead of it.**
     *
     * A surface tree is supposed to paint the face and leave the bulk to `defaultBlock`, which is what
     * makes substituting that block the way a book changes what a world we did not lay is made of. Two of
     * vanilla's three do not: `SurfaceRuleData.nether()` ends in a bare `NETHERRACK` arm that takes every
     * block its conditioned arms did not, and `end()` is one unconditional `ENDSTONE`. A blackstone nether
     * came out netherrack throughout (Jonah, 2026-08-25, walked).
     *
     * So the unconditional tail comes off, and **that changes nothing until a rock has been substituted**:
     * `SurfaceSystem.buildSurface` consults the rule only where `old == this.defaultBlock` and leaves the
     * block as it found it when the rule declines, so an arm painting netherrack onto netherrack was
     * already a no-op. `VanillasSkinCheck` holds both halves of that.
     *
     * Only the **last** arm of a sequence can be the tail; the earlier ones are alternatives that may
     * decline. A tree that is nothing but a block — the End's — comes back [SUPPRESSED], which is the same
     * statement about a world whose skin and whose rock were always the same block, and not an empty
     * sequence, which vanilla rejects.
     */
    fun asPatchesOver(skin: SurfaceRules.RuleSource): SurfaceRules.RuleSource = when (skin) {
        is SurfaceRules.BlockRuleSource -> SUPPRESSED
        is SurfaceRules.SequenceRuleSource -> {
            val earlier = skin.sequence().dropLast(1)
            val tail = asPatchesOver(skin.sequence().last())
            val arms = if (tail == SUPPRESSED) earlier else earlier + tail
            if (arms.isEmpty()) SUPPRESSED else SurfaceRules.sequence(*arms.toTypedArray())
        }
        else -> skin
    }

    /** How far a named skin reaches below the face — thin, since below it is what the Age is made of. */
    private const val SKIN_DEPTH = 2

    /**
     * No skin at all — only the world's floor, which is not the palette's to decide — how "named no
     * material" is spelled.
     */
    val NO_SKIN: SurfaceRules.RuleSource = worldFloor()

    /**
     * A rule that never matches, so whatever follows it decides — what [asPatchesOver] gives back for a tree
     * with nothing to add.
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
     * can want the same block — nested noise conditions would leave the second material's share
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
                where(SurfaceRules.noiseCondition(MINGLE_NOISE, from, from + bandWidth), block)
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
            // A soft stratum boundary: certainly deepslate at the lower anchor and below, certainly not at
            // the upper one and above, dissolving randomly in between, so it fades into stone without a seam.
            SurfaceRules.verticalGradient(
                "deepslate",
                VerticalAnchor.absolute(DEEPSLATE_SOLID_BELOW),
                VerticalAnchor.absolute(DEEPSLATE_ABSENT_ABOVE),
            ),
            Blocks.DEEPSLATE.defaultBlockState(),
        )

    /** How far the bedrock floor dissolves upward, matching vanilla's own five-block fade. */
    private const val BEDROCK_FADE = 5

    private const val DEEPSLATE_SOLID_BELOW = -8
    private const val DEEPSLATE_ABSENT_ABOVE = 8
}
