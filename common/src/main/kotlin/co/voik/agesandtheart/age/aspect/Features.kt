package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.age.AgeComposition
import co.voik.ephemeris.Rgba
import co.voik.agesandtheart.worldgen.feature.FeatureDensity
import co.voik.agesandtheart.worldgen.feature.DeepLake
import co.voik.agesandtheart.worldgen.feature.FeatureShape
import net.minecraft.core.Holder
import net.minecraft.core.HolderLookup
import net.minecraft.core.registries.Registries
import co.voik.agesandtheart.location
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
import co.voik.agesandtheart.worldgen.feature.ImpactCrater
import net.minecraft.world.level.levelgen.XoroshiroRandomSource
import net.minecraft.world.level.levelgen.placement.PlacedFeature as VanillaPlacedFeature
import co.voik.agesandtheart.worldgen.feature.Formation
import co.voik.agesandtheart.worldgen.feature.PitClearing
import java.util.concurrent.ConcurrentHashMap

/**
 * What is placed in and on the ground — ores, flora, lakes, springs (vanilla's `feature` and
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

    /** The book's feature claims, and those its landforms carry of their own ([Terrain.carriedFeatures]). */
    fun writtenAndCarried(composition: AgeComposition): Options {
        val written = composition.optionsFor(Aspect.FEATURES, 0)
        val carried = composition.terrains.flatMapIndexed { member, terrain ->
            terrain.carriedFeatures(composition.optionsFor(Aspect.TERRAIN, member), member)
        }.distinct()
        if (carried.isEmpty()) return written
        val places = PLACES.name
        return Options(written.chosen + (places to (written.chosen[places].orEmpty() + carried).distinct()))
    }

    /**
     * The claim naming [feature] in this composition, or null where nothing asks for it.
     *
     * Read off the claim rather than the placed feature registry, so it answers the same before an Age is
     * opened as after — which is what lets the desk survey and `/age instability <name> danger` ask it of a recipe that
     * has never been built. The amount rides on the claim, so a caller wanting one takes it from here
     * rather than asking a second time.
     *
     * **Naming is the whole of it** ([Claim.introducedIfAbsent] marks one), and two kinds of mention are not
     * that. A description ([Claim.onlyWhereItGrows]) asks for more of the thing where it already grows, and
     * a landform grows nowhere — so `desolate`, leaning on `#barren`, reached `agesandtheart:volcano` at 1.6
     * and raised cones through a frozen Age (Jonah, 2026-09-17, the Age Tumar). A strike-out is the opposite mention
     * and used to build what it struck, which [co.voik.agesandtheart.age.reward.Danger] already fixed on its own side.
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
    /**
     * A dial honoured by the placed features [asks] answers for, each looked up by its id — and by one the registries
     * do not hold, which cannot be looked at, as by a feature with no registries at all.
     */
    private fun honouredWhere(asks: (Holder<VanillaPlacedFeature>, HolderLookup.Provider) -> Boolean) =
        MemberHonouring { member, registries ->
            val placed = (member as? PlacedFeature)?.id?.let { id ->
                registries.lookupOrThrow(Registries.PLACED_FEATURE)
                    .get(ResourceKey.create(Registries.PLACED_FEATURE, id))
                    .orElse(null)
            }
            placed == null || asks(placed, registries)
        }

    /** What colour the algae glows — `red algae`, `blue algae` — or [Parameter.DEFAULT] for its own. Only the algae honours it. */
    val COLOUR = Sky.colour("colour", "The colour the algae glows.").perMember(MemberHonouring { member, _ -> member.key == ALGAE })

    /** The colour [options] say the algae glows, or null where they leave it its own. */
    fun algaeColourIn(options: Options): Rgba? {
        val holders = options.holdersOfAny(COLOUR)
        val said = when {
            ALGAE in holders.only -> options.of(COLOUR, member = ALGAE)
            holders.everyone -> options.of(COLOUR)
            else -> return null
        }
        return Colour.named(said)
    }

    /** The algae, as a feature and as the block it lays. */
    const val ALGAE = "agesandtheart:algae"

    /**
     * Whether everything the Age grows grows anywhere — every biome's features in every biome, at any height —
     * `incongruous features`. Yema keep to where they grow.
     */
    val INCONGRUOUS = Parameter.flag("incongruous", help = "Whether anything the Age grows may grow anywhere, at any height.")

    /** Whether everything that grows comes out the same shape, as if stamped — `uniform features`, `uniform trees`. */
    val UNIFORM = Parameter.flag("uniform", help = "Whether every tree and boulder comes out the same shape.").perMember()

    /** Whether trees grow with no leaves at all — `bare features`, `bare trees`. */
    val BARE = Parameter.flag("bare", help = "Whether the trees grow bare, with no leaves.")
        .perMember(honouredWhere { feature, _ -> FeatureShape.growsTrees(feature) })

    /** The block trees grow in place of their leaves — `diamond_block trees`, `frozen trees` — where it is not leaves. */
    val FOLIAGE = Parameter.material("foliage", help = "The block the trees grow in place of their leaves.")
        .perMember(honouredWhere { feature, _ -> FeatureShape.growsTrees(feature) })
        .onlyWhenAimed()

    /**
     * How far what the features build of rock above the ground has crumbled — `eroded features`. None unsaid, and only
     * ever away, never shaped: the boulders, spikes and formations lose blocks, the trees are left alone.
     *
     * **Not `wear`**, the landmass's dial, though it is the same idea: a bend names a dial and lands on whichever part
     * owns that name, so a features dial of the same name would split `pristine` and `uniform` between the land and the
     * features by chance. Only the words that name this one reach it.
     */
    val CRUMBLE = Parameter.ranged(
        "crumble",
        help = "How far the rock the features build above the ground has crumbled away.",
        landmarks = listOf(
            Parameter.Landmark(-1.0, "whole"),
            Parameter.Landmark(0.0, "whole"),
            Parameter.Landmark(1.0, "crumbled"),
        ),
    )

    /**
     * How much of what the features build is holes — `porous features` the rock they build above the ground, `porous
     * trees` everything a tree lays ([co.voik.agesandtheart.generation.PorousFeatures]). Only an aimed word turns it.
     */
    val POROSITY = Parameter.ranged(
        "porosity",
        help = "How much of what the features build is round holes.",
        landmarks = listOf(
            Parameter.Landmark(-1.0, "whole"),
            Parameter.Landmark(0.0, "whole"),
            Parameter.Landmark(1.0, "a third holes"),
        ),
    ).perMember().onlyWhenAimed()

    val SIZE = Parameter.ranged(
        "size",
        help = "How big one of a thing is.",
        landmarks = listOf(
                Parameter.Landmark(-1.0, "stunted"),
                Parameter.Landmark(0.0, "ordinary"),
                Parameter.Landmark(1.0, "as big as it gets"),
            ),
        ).perBiome().perMember(honouredWhere(FeatureShape::honoursSize))

    /**
     * How big a patch of ore is, for the ore alone: `rich underground` is thick with ore in bigger patches, where a
     * [SIZE] said of the whole Age would grow its trees with it. An ore takes the larger of the two.
     */
    val ORE_SIZE = Parameter.ranged(
        "ore_size",
        help = "How big a patch of ore is.",
        landmarks = listOf(
            Parameter.Landmark(-1.0, "a speck"),
            Parameter.Landmark(0.0, "ordinary"),
            Parameter.Landmark(1.0, "as big as it gets"),
        ),
    )
    val THICKNESS = Parameter.ranged(
        "thickness",
        help = "How thick a patch of them is.",
        landmarks = listOf(
                Parameter.Landmark(-1.0, "one here and there"),
                Parameter.Landmark(0.0, "ordinary"),
                Parameter.Landmark(1.0, "a thicket"),
            ),
        ).perBiome().perMember(honouredWhere { feature, _ -> FeatureShape.honoursThickness(feature) })
    val HEIGHT = Parameter.ranged(
        "height",
        help = "How deep in the column they sit.",
        landmarks = listOf(
                Parameter.Landmark(-1.0, "at the bedrock"),
                Parameter.Landmark(0.0, "mid-column"),
                Parameter.Landmark(1.0, "at the surface"),
            ),
        ).perBiome().perMember(honouredWhere { feature, _ -> FeatureShape.honoursHeight(feature) })

    /**
     * How tall a formation stands for its width — `high obelisks` twice as tall, `low pyramids` half — where [SIZE]
     * grows all of it. Whole doublings and halvings keep a stepped shape's courses even.
     */
    val TALLNESS = Parameter.ranged(
        "tallness",
        help = "How tall a formation stands for its width.",
        landmarks = listOf(
            Parameter.Landmark(-1.0, "half as tall"),
            Parameter.Landmark(0.0, "as it is built"),
            Parameter.Landmark(1.0, "twice as tall"),
        ),
    ).perBiome().perMember(honouredWhere { feature, _ -> FeatureShape.isAFormation(feature) })

    /**
     * How a formation's copies are laid out — `gridded obelisks` in rows and columns, `concentric rings` in rings about
     * the middle of the world — where unsaid they are scattered.
     */
    val ARRANGEMENT = Parameter(
        "arrangement",
        listOf(FeatureShape.SCATTERED, FeatureShape.GRID, FeatureShape.RINGS),
        help = "How a formation's copies are laid out.",
        optionHelp = mapOf(
            FeatureShape.SCATTERED to "Strewn about, as they always are.",
            FeatureShape.GRID to "Evenly spaced, in rows and columns.",
            FeatureShape.RINGS to "In concentric rings about the middle of the world.",
        ),
    ).perMember(honouredWhere { feature, _ -> FeatureShape.isAFormation(feature) })

    /** How far apart a formation's copies stand — `sparse gridded obelisks` a wider grid, `dense` a tighter one. */
    val SPACING = Parameter.ranged(
        "spacing",
        help = "How far apart a formation's copies stand.",
        landmarks = listOf(
            Parameter.Landmark(-1.0, "half as far apart"),
            Parameter.Landmark(0.0, "as they are laid"),
            Parameter.Landmark(1.0, "twice as far apart"),
        ),
    ).perMember(honouredWhere { feature, _ -> FeatureShape.isAFormation(feature) })

    /** Where a lake stands against the sea — `high lakes` up on the high ground, `low lakes` down near the sea. */
    val ALTITUDE = Parameter.ranged(
        "altitude",
        help = "How high over the sea a lake stands.",
        landmarks = listOf(
            Parameter.Landmark(-1.0, "only beside the sea"),
            Parameter.Landmark(0.0, "anywhere"),
            Parameter.Landmark(1.0, "only on the highest ground"),
        ),
    ).perBiome().perMember(honouredWhere { feature, _ -> FeatureShape.holdsALake(feature) })

    /** How deep a lake's water stands — `deep lakes`, `shallow lakes`. */
    val DEPTH = Parameter.ranged(
        "depth",
        help = "How deep a lake's water stands.",
        landmarks = listOf(
            Parameter.Landmark(-1.0, "a block of water"),
            Parameter.Landmark(0.0, "three blocks, as vanilla's"),
            Parameter.Landmark(1.0, "${DeepLake.DEEPEST} blocks"),
        ),
    ).perMember(honouredWhere { feature, _ -> FeatureShape.holdsALake(feature) })

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
        /**
         * How far a book asked for vanilla's large veins ([Underground.VEINS]) on rock of ours, which has none, or null:
         * the copper and iron there come larger and oftener instead.
         */
        veinsMadeUpFor: Double? = null,
    ): (Holder<Biome>) -> BiomeGenerationSettings {
        val claims = options.claimsOn(PLACES)
        val asked = PLACES.skewOf(claims)
        val shape = Shape(
            options = options,
            salt = salt,
            registries = server.registryAccess(),
            // **Not a parameter a writer turns.** An ore replaces what its rule matches, and vanilla's rules
            // match vanilla's stone — so an Age made of anything else grows no ore at all unless the rock
            // it *is* gets added to them. Carried here because this is where a feature is rebuilt.
            rock = rock,
            veinsMadeUpFor = veinsMadeUpFor?.coerceIn(0.0, 1.0),
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
                // A claim confined to one biome is absent from every other, so each biome's
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
        private val options: Options,
        private val salt: Long,
        val registries: HolderLookup.Provider,
        val rock: List<BlockState>,
        /** How far the veins a rock of ours lacks were asked for, or null. */
        private val veinsMadeUpFor: Double?,
    ) {
        /** Nothing turned, and nothing to reach — the case where a feature is handed back untouched. */
        val asksForNothing: Boolean
            get() {
                val nothingWasTurned = listOf(SIZE, ORE_SIZE, THICKNESS, HEIGHT, TALLNESS, ALTITUDE, ARRANGEMENT, SPACING, POROSITY).none { it.name in options.chosen }
                return nothingWasTurned && FeatureShape.oresCanReach(rock) && veinsMadeUpFor == null
            }

        /** How much oftener [feature] comes for the veins it makes up for: one, unless it is copper or iron here. */
        fun oftenerForVeins(feature: Holder<VanillaPlacedFeature>): Double =
            veinsOf(feature)?.let { 1.0 + it * (OFTENEST_FOR_VEINS - 1.0) } ?: 1.0

        private fun veinsOf(feature: Holder<VanillaPlacedFeature>): Double? =
            veinsMadeUpFor?.takeIf { FeatureShape.laysAVeinOre(feature) }

        /**
         * How big [feature] is to be — its own size where one was said of it (`colossal trees`), else the Age's —
         * and a little larger again where it is copper or iron making up for veins.
         */
        fun sizeOf(feature: Holder<VanillaPlacedFeature>): Double? {
            val saidOfIt = options.steer(SIZE, salt, member = keyOf(feature))
            val saidOfOre = if (FeatureShape.laysOre(feature)) options.steer(ORE_SIZE, salt) else null
            val said = listOfNotNull(saidOfIt, saidOfOre).maxOrNull()
            val veins = veinsOf(feature) ?: return said
            return ((said ?: 0.0) + veins * LARGEST_FOR_VEINS).coerceAtMost(1.0)
        }

        fun thicknessOf(feature: Holder<VanillaPlacedFeature>): Double? =
            options.steer(THICKNESS, salt, member = keyOf(feature))

        fun heightOf(feature: Holder<VanillaPlacedFeature>): Double? = options.steer(HEIGHT, salt, member = keyOf(feature))

        fun tallnessOf(feature: Holder<VanillaPlacedFeature>): Double? = options.steer(TALLNESS, salt, member = keyOf(feature))

        fun altitudeOf(feature: Holder<VanillaPlacedFeature>): Double? = options.steer(ALTITUDE, salt, member = keyOf(feature))

        fun arrangementOf(feature: Holder<VanillaPlacedFeature>): String? =
            options.of(ARRANGEMENT, member = keyOf(feature)).takeUnless { it == ARRANGEMENT.default }

        fun spacingOf(feature: Holder<VanillaPlacedFeature>): Double? = options.steer(SPACING, salt, member = keyOf(feature))

        /** How holed [feature] is where the book said it of this feature itself — `porous trees` — or null. */
        fun porosityOf(feature: Holder<VanillaPlacedFeature>): Double? {
            val key = keyOf(feature) ?: return null
            if (key !in options.holdersOfAny(POROSITY).only) return null
            return options.steer(POROSITY, salt, member = key)
        }

        val holesSeed: Long get() = salt

        private fun keyOf(feature: Holder<VanillaPlacedFeature>): String? = idOf(feature)?.toString()

        private val rebuilt = ConcurrentHashMap<Holder<VanillaPlacedFeature>, Holder<VanillaPlacedFeature>>()

        fun applied(feature: Holder<VanillaPlacedFeature>): Holder<VanillaPlacedFeature> =
            if (asksForNothing) {
                feature
            } else {
                rebuilt.computeIfAbsent(feature) {
                    val arranged = FeatureShape.arranged(it, arrangementOf(it), spacingOf(it))
                    FeatureShape.reshaped(arranged, sizeOf(it), thicknessOf(it), heightOf(it), rock, registries)
                        .let { reshaped -> FeatureShape.heightened(reshaped, tallnessOf(it)) }
                        .let { reshaped -> FeatureShape.deepened(reshaped, depth = null, altitude = altitudeOf(it)) }
                        .let { reshaped -> FeatureShape.perforated(reshaped, porosityOf(it), salt) }
                }
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
                val minted = if (substances.isEmpty()) found else FeatureShape.mintedFrom(found, substances)
                val scattered = claim.frequency?.let { FeatureShape.scatteredAtFrequency(minted, it) } ?: minted
                val shaped = FeatureShape.arranged(scattered, claim.arrangement ?: shape.arrangementOf(found), claim.spacing ?: shape.spacingOf(found))
                // **Shaped before the amount**, so what is scaled is the shape and not the placement the
                // amount prepends to.
                val reshaped = FeatureShape.reshaped(
                    shaped,
                    size = claim.size ?: shape.sizeOf(found),
                    thickness = shape.thicknessOf(found),
                    height = claim.height ?: shape.heightOf(found),
                    rock = shape.rock,
                    registries = shape.registries,
                ).let { FeatureShape.deepened(it, claim.depth, claim.altitude ?: shape.altitudeOf(found)) }
                    .let { FeatureShape.heightened(it, claim.tallness ?: shape.tallnessOf(found)) }
                val confined = claim.confinedTo?.let { FeatureShape.confinedTo(reshaped, it) } ?: reshaped
                val kept = claim.territory?.let { FeatureShape.keptToTerritory(confined, it) } ?: confined
                FeatureShape.perforated(FeatureDensity.applied(kept, claim.density), claim.porosity ?: shape.porosityOf(found), salt)
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

    /** How much larger copper and iron grow at most, on the size axis, making up for veins: about a quarter. */
    private const val LARGEST_FOR_VEINS = 0.16

    /** And how much oftener they come at most. */
    private const val OFTENEST_FOR_VEINS = 1.25

    /**
     * Which step a feature sits in wherever this pack already uses it — see [wanted] — or, for a formation
     * that digs, [DIGGING_STEP].
     */
    private fun stepFor(feature: Identifier, placed: VanillaPlacedFeature, biomes: HolderLookup<Biome>): Int {
        if ((placed.feature().value() as? Formation)?.sunk == true) return DIGGING_STEP
        val heaps = placed.feature().value().let { it is BlockPileFeature || it is Heap }
        // A crater carved after the trees would leave them standing in the air over it, as `Craters` says.
        // `craters` is a random selector over two sizes, so it is known by name rather than by type.
        val cratersTheGround = placed.feature().value() is ImpactCrater || feature == CRATERS
        if (heaps || cratersTheGround) return BARE_GROUND_STEP
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

    private val CRATERS: Identifier = "craters".location()

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
        val pit = (FeatureShape.underItsHoles(laid).value().feature().value() as? Formation)?.takeIf { it.sunk } ?: return null
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
                    val bending = idOf(feature)?.let(bendingWhereItGrows::get)
                    val oftener = shape.oftenerForVeins(feature)
                    val amount = if (oftener == 1.0) bending else (bending ?: 1.0) * oftener
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
