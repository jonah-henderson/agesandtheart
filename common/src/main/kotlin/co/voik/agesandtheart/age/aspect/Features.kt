package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.worldgen.feature.FeatureDensity
import co.voik.agesandtheart.worldgen.feature.FeatureShape
import net.minecraft.core.Holder
import net.minecraft.core.HolderLookup
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.tags.TagKey
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.BiomeGenerationSettings
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.GenerationStep
import net.minecraft.world.level.levelgen.feature.BlockPileFeature
import co.voik.agesandtheart.worldgen.feature.Heap
import net.minecraft.world.level.levelgen.XoroshiroRandomSource
import net.minecraft.world.level.levelgen.placement.PlacedFeature as VanillaPlacedFeature
import co.voik.agesandtheart.worldgen.feature.Formation
import co.voik.agesandtheart.worldgen.feature.PitClearing
import java.util.concurrent.ConcurrentHashMap

/**
 * What is placed in and on the ground — ores, flora, lakes, springs (design §3.1, vanilla's `feature` and
 * `GenerationStep.Decoration`). **Whatever the biomes would place**, until a sentence says otherwise.
 *
 * No preset, for the same reason biomes and structures have none: an Age does not pick one of a few ways
 * to be decorated, it starts from what its biomes carry and a sentence adjusts it. [PLACES] is where the
 * writing happens — naming a feature asks for it, `except` strikes one out, `only` keeps just what was
 * named.
 *
 * §7.2 is why it is load-bearing rather than decoration: "write an Age that supplies an ink farm" needs a
 * writer to be able to say what is *in* the ground.
 */
object Features {

    /**
     * What is placed here — populative, with `only`/`except` to narrow (§3.2, [Claim]). Its values are
     * *placed* features. Named `places` to avoid `features.features`.
     *
     * A mention is worth the **ordinary** amount, like a structure set and unlike a biome: naming a
     * feature asks for a thing that was not there rather than for more of a thing that was. And it may be
     * emptied — a world where nothing is placed is a world — so [Pool.NOTHING] is what says so: bare ground,
     * whatever its biomes would have carried.
     *
     * **A rung here is absolute and takes nothing from anything else** ([FeatureDensity]): twice the trees
     * is twice the trees, where twice the desert is necessarily less of some other biome.
     */
    val PLACES = Pool(
        "grows",
        emptiedBy = Pool.NOTHING,
        help = "What is placed in the world: trees, ores, plants, ruins.",
        confinable = true,
    )

    /**
     * The claim naming [feature] in this composition, or null where nothing asks for it.
     *
     * Read off the claim rather than the placed feature registry, so it answers the same before an Age is
     * opened as after — which is what lets the desk survey and `/age danger score` ask it of a recipe that
     * has never been built. The amount rides on the claim, so a caller wanting one takes it from here
     * rather than asking a second time.
     *
     * **Naming is the whole of it** ([Claim.introducedIfAbsent] marks one), and two kinds of mention are not
     * that. A description ([Claim.onlyWhereItGrows]) asks for more of the thing where it already grows, and
     * a landform grows nowhere — so `desolate`, leaning on `#barren`, reached `agesandtheart:volcano` at 1.6
     * and raised cones through a frozen Age (Jonah, 2026-09-17, the Age Tumar). A strike-out is the opposite mention
     * and used to build what it struck, which [Danger] already fixed on its own side.
     *
     * Confinement is *not* filtered here and cannot be honoured by the callers, whose fields are laid over
     * the whole Age — `volcano[in=minecraft:badlands]` raises them everywhere. That wants `unhonoured`
     * rather than a silent drop, and is left alone.
     */
    fun claimNaming(composition: AgeComposition, feature: Identifier): Claim? {
        val mentions = composition.optionsFor(Aspect.FEATURES, 0).claimsOn(PLACES).filter { it.id == feature }
        val isStruckOut = mentions.any { it.polarity == Polarity.EXCEPT }
        if (isStruckOut) return null
        fun wasNamed(claim: Claim) = !claim.onlyWhereItGrows || claim.introducedIfAbsent
        return mentions.firstOrNull(::wasNamed)
    }

    /**
     * How big one of a thing is, how thick a patch of it is, and how deep in the column it sits — the
     * three parameters [FeatureShape] found worth turning in the whole of vanilla's feature data.
     *
     * **Dials rather than rungs, and the difference is what each is about.** A rung says how many of one
     * named thing there are; these say what *this Age* is like, so they apply to everything it grows. A
     * word bends them exactly as `arid` bends a climate axis.
     */
    val SIZE = Parameter.ranged(
        "size",
        help = "How big one of a thing is.",
        landmarks = listOf(
                Parameter.Landmark(-1.0, "stunted"),
                Parameter.Landmark(0.0, "ordinary"),
                Parameter.Landmark(1.0, "as big as it gets"),
            ),
        ).perBiome()
    val THICKNESS = Parameter.ranged(
        "thickness",
        help = "How thick a patch of them is.",
        landmarks = listOf(
                Parameter.Landmark(-1.0, "one here and there"),
                Parameter.Landmark(0.0, "ordinary"),
                Parameter.Landmark(1.0, "a thicket"),
            ),
        ).perBiome()
    val HEIGHT = Parameter.ranged(
        "height",
        help = "How deep in the column they sit.",
        landmarks = listOf(
                Parameter.Landmark(-1.0, "at the bedrock"),
                Parameter.Landmark(0.0, "mid-column"),
                Parameter.Landmark(1.0, "at the surface"),
            ),
        ).perBiome()

    /**
     * A biome's own generation settings, adjusted by whatever the sentence said — the function
     * `ChunkGenerator` takes for exactly this and `NoiseBasedChunkGenerator` never passes on.
     *
     * Returns the settings **unchanged** where nothing was said, so an Age nobody spoke to about growing
     * things is decorated by its biomes alone and pays nothing for the seam.
     */
    fun placedIn(
        server: MinecraftServer,
        options: Options,
        salt: Long,
        rock: List<BlockState>,
    ): (Holder<Biome>) -> BiomeGenerationSettings {
        val claims = options.claimsOn(PLACES)
        val asked = PLACES.skewOf(claims)
        val shape = Shape(
            size = options.steer(SIZE, salt),
            thickness = options.steer(THICKNESS, salt),
            height = options.steer(HEIGHT, salt),
            // **Not a parameter a writer turns.** An ore replaces what its rule matches, and vanilla's rules
            // match vanilla's stone — so an Age made of anything else grows no ore at all unless the rock
            // it *is* gets added to them. Carried here because this is where a feature is rebuilt.
            rock = rock,
        )
        val scoped = claims.any { it.confinedTo != null }
        if (asked.isSilent && !scoped && shape.asksForNothing) {
            return { biome -> biome.value().generationSettings }
        }
        // **Answered once per biome and remembered, and that is a correctness rule rather than a saving.**
        // `FeatureSorter` indexes the sorted feature list by **identity** (`createIndexIdentityLookup`), and
        // `applyBiomeDecoration` looks a feature up in that index every chunk. Hand it an equal-but-new
        // `PlacedFeature` the second time and the lookup misses, which is -1 into a list.
        val settled = ConcurrentHashMap<Holder<Biome>, BiomeGenerationSettings>()
        // **And once per *claim*, for the same reason one step further in.** Remembering the settings is
        // not enough on its own: a claim that lands in twenty biomes was minted and rescaled twenty times
        // over, so the twenty copies were equal and none of them the same object. The sorted list keeps
        // one of them and the other nineteen biomes look up an identity it does not hold.
        //
        // It bit the moment a formation went into every biome. A minted spring is the same shape of bug
        // and had simply never been generated over enough ground to meet a second biome carrying it.
        val grown = ConcurrentHashMap<Claim, Holder<VanillaPlacedFeature>>()
        // A dug formation's second pass, one per formation for the same reason.
        val clearings = ConcurrentHashMap<Holder<VanillaPlacedFeature>, Holder<VanillaPlacedFeature>>()
        // Scaling a biome's own feature makes a new one too, so it is memoised for the same reason —
        // keyed by the feature and the amount, which is what decides the object.
        val bent = ConcurrentHashMap<Pair<Holder<VanillaPlacedFeature>, Double>, Holder<VanillaPlacedFeature>>()
        // Every claim confined to a biome, as that biome would want it — offered to every biome, since a
        // confined formation is carried everywhere and asks its own origin (`Formation.onlyIn`).
        val confined = claims.filter { it.confinedTo != null }
            .groupBy { it.confinedTo }
            .flatMap { (ground, there) -> PLACES.skewOf(there, ground).wanted }
        val grownSomewhere = WhereThingsGrow.featuresGrown(server.registryAccess())
        return { biome ->
            settled.computeIfAbsent(biome) {
                // A claim confined to one biome (§4.3.1) is absent from every other, so each biome's
                // settings are built from what applies *there*.
                val here = PLACES.skewOf(claims, it.unwrapKey().orElse(null)?.identifier())
                settingsFrom(
                    it,
                    wanted(server, here, salt, grown, clearings, shape, confined, grownSomewhere),
                    bentWhereItGrows(here),
                    bent,
                    here.struck.mapNotNull(Identifier::tryParse).toSet(),
                    here.startsFromNothing,
                    shape,
                )
            }
        }
    }

    /**
     * The Age's three dials and its rock, and **one rebuilt feature per feature for the whole Age**.
     *
     * Not a data class, because the memo is the point of it: reshaping is a *new* placed feature, and the
     * sorted list decoration reads is indexed by identity — so a feature reshaped separately for each of
     * twenty biomes gives twenty equal objects, of which the list keeps one. Every other biome then looks
     * up an identity it does not hold, and gets -1 into a list in the middle of generation.
     */
    private class Shape(
        val size: Double?,
        val thickness: Double?,
        val height: Double?,
        val rock: List<BlockState>,
    ) {
        /** Nothing turned, and nothing to reach — the case where a feature is handed back untouched. */
        val asksForNothing: Boolean
            get() {
                val nothingWasTurned = size == null && thickness == null && height == null
                return nothingWasTurned && FeatureShape.oresCanReach(rock)
            }

        private val rebuilt = ConcurrentHashMap<Holder<VanillaPlacedFeature>, Holder<VanillaPlacedFeature>>()

        fun applied(feature: Holder<VanillaPlacedFeature>): Holder<VanillaPlacedFeature> =
            if (asksForNothing) {
                feature
            } else {
                rebuilt.computeIfAbsent(feature) { FeatureShape.reshaped(it, size, thickness, height, rock) }
            }
    }

    /**
     * The features the sentence asked for, each with the step it belongs in, **already shaped**: a clause's
     * own size and height win over the Age's dials, and the Age's rock reaches every one.
     *
     * **A placed feature does not know its own step** — the step is the *index* of the list it sits in, so
     * it is a fact about the biome rather than about the feature. So a named feature is given the step it
     * already occupies wherever the pack uses it, and [ORPHAN_STEP] only where nothing does: an ore asked
     * for by name lands among the ores, and a flower among the flowers, with no table of ours to maintain.
     */
    private fun wanted(
        server: MinecraftServer,
        asked: Skew,
        salt: Long,
        grown: ConcurrentHashMap<Claim, Holder<VanillaPlacedFeature>>,
        clearings: ConcurrentHashMap<Holder<VanillaPlacedFeature>, Holder<VanillaPlacedFeature>>,
        shape: Shape,
        confinedElsewhere: List<Claim>,
        grownSomewhere: Set<Identifier>,
    ): Map<Int, List<Holder<VanillaPlacedFeature>>> {
        val features = server.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE)
        val biomes = server.registryAccess().lookupOrThrow(Registries.BIOME)
        val byStep = mutableMapOf<Int, MutableList<Holder<VanillaPlacedFeature>>>()
        fun placesAFormation(claim: Claim): Boolean {
            val named = claim.id ?: return false
            val found = features.get(ResourceKey.create(Registries.PLACED_FEATURE, named)).orElse(null)
            return found != null && FeatureShape.isAFormation(found)
        }
        val formationsFromElsewhere = confinedElsewhere.filter { it !in asked.wanted && placesAFormation(it) }
        for (claim in asked.wanted + formationsFromElsewhere) {
            val named = claim.id ?: continue
            // A description, or a naming of something some biome grows, asks for more of it where it
            // grows rather than for it here — see [bentWhereItGrows] and `Claim.introduces`.
            val bendsWhatIsHere = claim.madeOf == null && !claim.introduces(named in grownSomewhere)
            if (bendsWhatIsHere) continue
            val found = features.get(ResourceKey.create(Registries.PLACED_FEATURE, named)).orElse(null)
            if (found == null) {
                Constants.LOG.warn("An Age asked to grow '{}', which is no placed feature in this pack", named)
                continue
            }
            // **Minted where the claim says what it is made of** — `ink springs` is vanilla's own spring
            // running with something it never runs with. The pattern keeps its placement, its rarity and
            // its step; only the substance changes.
            //
            // Built once for the whole Age and shared by every biome that carries it: what comes out is a
            // *new* placed feature, and the sorted list decoration reads is indexed by identity.
            val laid = grown.computeIfAbsent(claim) {
                // A tag names a small pool the pattern is made of when the clause said nothing; a bare id
                // is one answer. Drawn against the Age's own salt, so a world rebuilds identically.
                val substances = claim.substances.mapNotNull { drawnSubstance(server, it, salt, claim.value) }
                val shaped = if (substances.isEmpty()) found else FeatureShape.mintedFrom(found, substances)
                // **Shaped before the amount**, so what is scaled is the shape and not the placement the
                // amount prepends to.
                val reshaped = FeatureShape.reshaped(
                    shaped,
                    size = claim.size ?: shape.size,
                    thickness = shape.thickness,
                    height = claim.height ?: shape.height,
                    rock = shape.rock,
                )
                val confined = claim.confinedTo?.let { FeatureShape.confinedTo(reshaped, it) } ?: reshaped
                FeatureDensity.applied(confined, claim.density)
            }
            byStep.getOrPut(stepFor(named, found.value(), biomes)) { mutableListOf() } += laid
            clearingOver(laid, clearings)?.let { byStep.getOrPut(CLEARING_STEP) { mutableListOf() } += it }
        }
        return byStep
    }

    /**
     * **What a description asks for more or less of**, by feature, rather than what it asks to be put here.
     *
     * `teeming trees` reaches seventy features through a tag and means the trees *this* biome grows; read
     * as seventy namings it put acacia, bamboo and cherry in every biome at once. A minted claim is never
     * here: `ink springs` is a thing no biome has, so there is nothing to bend.
     */
    private fun bentWhereItGrows(asked: Skew): Map<Identifier, Double> =
        asked.wanted
            .filter { it.onlyWhereItGrows && it.madeOf == null }
            .mapNotNull { claim -> claim.id?.let { it to claim.density } }
            .toMap()

    /**
     * The block a claim is made of — **a tag being a pool the Age draws one from**, and a bare id being
     * itself.
     *
     * Resolved here rather than in the resolver, which is a pure function of (vocabulary, sentence, seed)
     * and holds no registry. Salted by what is being made, so two patterns left unstated in one book do
     * not come out of the same rock.
     */
    private fun drawnSubstance(server: MinecraftServer, named: String, salt: Long, of: String): String? {
        if (!named.startsWith(TAG_MARK)) return named
        val id = Identifier.tryParse(named.drop(1)) ?: return null
        val blocks = server.registryAccess().lookupOrThrow(Registries.BLOCK)
        val pool = blocks.get(TagKey.create(Registries.BLOCK, id)).orElse(null)?.toList().orEmpty()
        if (pool.isEmpty()) {
            Constants.LOG.warn("Nothing carries '{}', so '{}' is made of nothing in particular", named, of)
            return null
        }
        val drawn = XoroshiroRandomSource(salt xor of.hashCode().toLong()).nextInt(pool.size)
        return pool[drawn].unwrapKey().orElse(null)?.identifier()?.toString()
    }

    private const val TAG_MARK = '#'

    /**
     * Which step a feature sits in wherever this pack already uses it — see [wanted] — or, for a formation
     * that digs, [DIGGING_STEP].
     */
    private fun stepFor(feature: Identifier, placed: VanillaPlacedFeature, biomes: HolderLookup<Biome>): Int {
        if ((placed.feature().value() as? Formation)?.sunk == true) return DIGGING_STEP
        val heaps = placed.feature().value().let { it is BlockPileFeature || it is Heap }
        if (heaps) return BARE_GROUND_STEP
        for (biome in biomes.listElements()) {
            biome.value().generationSettings.features().forEachIndexed { step, atStep ->
                if (atStep.any { it.unwrapKey().orElse(null)?.identifier() == feature }) return step
            }
        }
        return ORPHAN_STEP
    }

    /**
     * Where a feature nothing else places goes: among the vegetation, which is where a thing that grows
     * belongs and the step that runs after the ground is settled.
     */
    private val ORPHAN_STEP = GenerationStep.Decoration.VEGETAL_DECORATION.ordinal

    /**
     * Where a pit is dug: vanilla's own surface lakes' step, **before anything grows**. Among the vegetation
     * it cut the ground from under trees, grass and flowers already standing on it and left them hanging.
     */
    private val DIGGING_STEP = GenerationStep.Decoration.LAKES.ordinal

    /**
     * What grew in a dug formation, cleared once it has grown — [PitClearing] — or null for anything that
     * does not dig.
     */
    private fun clearingOver(
        laid: Holder<VanillaPlacedFeature>,
        clearings: ConcurrentHashMap<Holder<VanillaPlacedFeature>, Holder<VanillaPlacedFeature>>,
    ): Holder<VanillaPlacedFeature>? {
        val pit = (laid.value().feature().value() as? Formation)?.takeIf { it.sunk } ?: return null
        return clearings.computeIfAbsent(laid) {
            Holder.direct(VanillaPlacedFeature(Holder.direct(PitClearing(pit)), laid.value().placement()))
        }
    }

    /** Where a pit is cleared of what grew in it: with the snow, after everything has grown. */
    private val CLEARING_STEP = GenerationStep.Decoration.TOP_LAYER_MODIFICATION.ordinal

    /**
     * Where a pile is heaped: on the ground **before the grass grows over it**. A pile only lands on an open
     * spot above a sturdy block, and among the vegetation nearly every spot on a plain already held a tuft.
     */
    private val BARE_GROUND_STEP = GenerationStep.Decoration.LOCAL_MODIFICATIONS.ordinal

    /**
     * One biome's settings with the sentence applied: everything named added at the step it belongs in,
     * everything struck out dropped, and the biome's own list left out entirely where the writer said
     * `only` or [Pool.NOTHING].
     *
     * **Carvers are carried across untouched.** They live on these settings too and belong to the Carvers
     * aspect, which reaches them through the generator — so this rebuilds the feature half and copies the
     * other, rather than silently emptying a world of its caves.
     */
    private fun settingsFrom(
        biome: Holder<Biome>,
        added: Map<Int, List<Holder<VanillaPlacedFeature>>>,
        bendingWhereItGrows: Map<Identifier, Double>,
        bent: ConcurrentHashMap<Pair<Holder<VanillaPlacedFeature>, Double>, Holder<VanillaPlacedFeature>>,
        struck: Set<Identifier>,
        startsFromNothing: Boolean,
        shape: Shape,
    ): BiomeGenerationSettings {
        val own = biome.value().generationSettings
        val built = BiomeGenerationSettings.PlainBuilder()
        // 26.1 keeps one carver list per biome rather than one per carving step, so this is a copy
        // rather than a walk over steps.
        own.carvers.forEach(built::addCarver)
        val kept = if (startsFromNothing) emptyList() else own.features()
        kept.forEachIndexed { step, atStep ->
            atStep.filterNot { idOf(it) in struck }
                .forEach { feature ->
                    val amount = idOf(feature)?.let(bendingWhereItGrows::get)
                    val asOften = if (amount == null) {
                        feature
                    } else {
                        bent.computeIfAbsent(feature to amount) { (it, density) ->
                            FeatureDensity.applied(it, density)
                        }
                    }
                    built.addFeature(step, shape.applied(asOften))
                }
        }
        for ((step, features) in added) {
            // Already shaped by [wanted], with the clause's own dials over the Age's.
            features.forEach { built.addFeature(step, it) }
        }
        return built.build()
    }

    private fun idOf(feature: Holder<VanillaPlacedFeature>): Identifier? =
        feature.unwrapKey().orElse(null)?.identifier()
}
