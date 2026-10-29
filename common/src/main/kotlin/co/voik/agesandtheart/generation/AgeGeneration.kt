package co.voik.agesandtheart.generation

import co.voik.agesandtheart.worldgen.DeepWrecks
import co.voik.agesandtheart.age.aspect.Biomes
import co.voik.agesandtheart.age.aspect.Sea
import co.voik.agesandtheart.age.aspect.TreeFoliage
import co.voik.agesandtheart.worldgen.ruins.CavernRuins
import co.voik.agesandtheart.worldgen.field.SeaFill
import co.voik.agesandtheart.worldgen.field.WaterTable
import kotlin.math.pow
import co.voik.agesandtheart.age.consequence.Consequence
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Options
import co.voik.agesandtheart.age.aspect.Terrain
import co.voik.agesandtheart.age.aspect.Underground
import co.voik.ephemeris.OwnClock
import co.voik.ephemeris.sky.Look
import co.voik.ephemeris.sky.SkySpec
import co.voik.agesandtheart.worldgen.biome.AgeBiomeSource
import co.voik.agesandtheart.worldgen.biome.Grounding
import co.voik.agesandtheart.worldgen.biome.RegionalClimate
import co.voik.agesandtheart.content.DeepWater
import co.voik.agesandtheart.worldgen.biome.BiomeBand
import co.voik.agesandtheart.worldgen.field.Fault
import co.voik.agesandtheart.worldgen.field.SurfacingStrategy
import co.voik.agesandtheart.worldgen.field.RegionMap
import co.voik.agesandtheart.worldgen.field.Regions
import co.voik.agesandtheart.worldgen.field.Reconciled
import co.voik.agesandtheart.worldgen.field.Ridge
import co.voik.agesandtheart.worldgen.field.Rift
import co.voik.agesandtheart.worldgen.field.TableLayout
import co.voik.agesandtheart.worldgen.field.TerrainField
import co.voik.agesandtheart.worldgen.field.TerrainTable
import co.voik.agesandtheart.worldgen.field.Slab
import co.voik.agesandtheart.worldgen.field.Subtract
import co.voik.agesandtheart.age.aspect.MagmaChambers
import co.voik.agesandtheart.worldgen.field.StandingFluid
import co.voik.agesandtheart.worldgen.field.Union
import co.voik.agesandtheart.worldgen.field.Weathered
import co.voik.agesandtheart.worldgen.field.TerrainFill
import net.minecraft.world.level.levelgen.material.MaterialRules
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import co.voik.agesandtheart.location
import co.voik.agesandtheart.worldgen.AgeRock
import co.voik.agesandtheart.worldgen.CeilingField
import co.voik.agesandtheart.worldgen.ClimateStretch
import co.voik.agesandtheart.worldgen.WithoutCaves
import co.voik.agesandtheart.worldgen.MagmaChamberField
import co.voik.agesandtheart.worldgen.Dislocation
import co.voik.agesandtheart.worldgen.Overlay
import co.voik.agesandtheart.worldgen.field.Dislocated
import co.voik.agesandtheart.worldgen.VolcanoField
import co.voik.agesandtheart.worldgen.VerticalWindow
import co.voik.agesandtheart.worldgen.VanillaDelegate
import net.minecraft.core.Holder
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.biome.Biome
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings
import net.minecraft.world.level.levelgen.material.rule.MaterialRule
import net.minecraft.world.level.levelgen.XoroshiroRandomSource
import co.voik.agesandtheart.age.aspect.Features
import co.voik.agesandtheart.age.reward.EarlyGameRareMaterials
import co.voik.agesandtheart.age.reward.Craters
import co.voik.agesandtheart.age.reward.Danger
import co.voik.agesandtheart.age.reward.Decoration
import co.voik.agesandtheart.age.reward.Deposits
import co.voik.agesandtheart.age.reward.PaperTreeWindow
import co.voik.agesandtheart.age.reward.ScarabHabitat
import co.voik.agesandtheart.age.aspect.AgeSpawner
import co.voik.agesandtheart.content.DriftingOreSpawner
import net.minecraft.world.level.CustomSpawner
import co.voik.agesandtheart.age.aspect.Spawns
import co.voik.agesandtheart.age.aspect.WhereThingsGrow
import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.aspect.Structures
import co.voik.agesandtheart.age.aspect.Sky
import co.voik.agesandtheart.age.aspect.SkyBodies
import co.voik.agesandtheart.age.aspect.Volcanoes
import co.voik.agesandtheart.age.aspect.Surface
import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.AgePreset
import co.voik.agesandtheart.age.AgeRecipe
import co.voik.agesandtheart.age.AgeTemplate
import co.voik.agesandtheart.age.AgeWorld
import co.voik.agesandtheart.age.AspectOptions
import co.voik.agesandtheart.age.Manifestation
import co.voik.agesandtheart.age.Seam
import co.voik.agesandtheart.age.Spending
import co.voik.agesandtheart.age.Spread

/**
 * Turns an [AgeRecipe] into the generator that builds its world — a pure function of the recipe (plus the
 * server, for registries), because an Age must rebuild identically on every open.
 */
object AgeGeneration {
    fun chunkGenerator(server: MinecraftServer, recipe: AgeRecipe): ChunkGenerator = when (val world = recipe.world) {
        is AgeWorld.Composed -> assemble(server, world.composition, recipe)
        is AgeWorld.Bespoke -> bespoke(server, world.preset)
    }

    /**
     * One preset per aspect, each answering for its own part of the world — and where an aspect holds
     * several, the territories they divide it into.
     *
     * The field, the surface rule and the biome source all read the *terrain's* [Spread], so their seams
     * agree to the column; three maps drawn independently would read as three faults rather than one edge.
     */
    private fun assemble(server: MinecraftServer, composition: AgeComposition, recipe: AgeRecipe): ChunkGenerator {
        val seed = recipe.seed
        // Per territory, not per aspect — see [AspectOptions].
        fun terrainOptions(member: Int) = composition.optionsFor(Aspect.TERRAIN, member)

        // How each divided aspect is laid out: its members' ground and the form of the boundary between
        // them, which is one object because a blend width moves the boundary line itself (see [Spread]).
        val character = recipe.character
        val landmass = composition.spreadOf(Aspect.TERRAIN)

        // What this Age's instability bought, as a fraction of everything tearing could be (design §5.0).
        // Derived rather than stored: a pure function of the recipe, so it comes out the same on every open.
        val spending = Spending.of(server, recipe)
        val earlyMaterials = EarlyGameRareMaterials.grownIn(composition, seed, spending)
        // What the book wrote of the land takes the top of each, where instability would have climbed to it.
        val torn = if (composition.anyMemberSays(Aspect.TERRAIN, Terrain.TORN)) ALL_OF_IT
            else spending.reach(Manifestation.TORN_SEAMS)
        val dislocated = if (composition.anyMemberSays(Aspect.TERRAIN, Terrain.INCONGRUOUS)) ALL_OF_IT
            else spending.reach(Manifestation.FOREIGN_GROUND)
        val dislocation = Dislocation(seed xor DISLOCATION_SALT, dislocated)

        // One band for every Age, and the same one every dimension type admits — see [VerticalWindow].
        val window = VerticalWindow.DEFAULT

        val table = terrainTableOf(composition, seed)
        // An Age laid out by the table sets its landforms at one sea; one written before keeps each at its own.
        val sea = table?.let {
            Terrain.seaSettingFor(composition.terrains, { member -> composition.optionsFor(Aspect.TERRAIN, member) }, { saltFor(seed, it) })
        }
        val ground = laidOut(character.mapFor(Aspect.TERRAIN, landmass, seed, torn), table, composition, seed)
        // **Null where the rock is vanilla's, and this is the one place that is decided**: no field is
        // built, no biome is grounded in one, nothing is hollowed under it, and the generator hands
        // `fillFromNoise` and its companions to the base class. Named at all rather than named alone —
        // `/age compose` refuses the mixture, and a hand-written recipe that says it anyway gets vanilla's
        // rock rather than a landform asked for a field it has none of.
        // **Built whether or not there is a landform of ours**, which is the whole point of it: an Age
        // wearing vanilla's rock still gets its mountains, written into the chunk after vanilla's own fill.
        // **Built once and shared**, so the rock, the bodies the sea carries and the generator's own copy
        // are all the same object — see the note on `ourGround`'s parameter.
        val overlay = volcanicOverlay(composition, seed)
        val grounds = if (composition.terrains.any { it.isVanillas }) null else groundsOf(composition, window, seed, table, sea)
        val merged = landmass.seam == Seam.MERGED
        val territories = Territories(
            ground,
            grounds?.map { it.shape }?.takeIf { merged },
            table,
            sea,
            eases = if (merged) composition.terrains.map(Terrain::reconciles) else emptyList(),
        )
        val drawnSeam = DrawnSeam.of(landmass, composition.terrains)
        val ourGround = grounds?.let { ourGround(composition, it, drawnSeam, territories, window, seed, torn, overlay, dislocation) }

        val chasm = ourGround?.chasm
        val standing = ourGround?.standing
        val flow = character.mapFor(Aspect.SEA, composition.spreadOf(Aspect.SEA), seed)
        val waterlines = waterlinesOf(composition, seed, sea)
        val seaFill = Sea.pour(
            composition.seas,
            waterlineOf(composition, waterlines, seed),
            // The first territory's: `depth` shifts every waterline by one amount for the whole Age.
            composition.optionsFor(Aspect.SEA, 0),
            flow,
            seed,
            // Each landform's own sea, on the map its rock is laid by, walled apart where they differ.
            waterlines,
            ground,
            // **The bodies are carried whichever path built them**, because `VolcanoVents` finds its lava
            // through this and would otherwise seat no vents at all in a vanilla-rock Age.
        ).copy(
            dry = chasm,
            wet = standing,
            carried = overlay.pours + listOfNotNull(riversOf(composition, drawnSeam, territories, seed, torn)),
        )

        // What the rock *is*, on the terrain's own map, laid by the fill rather than painted by a rule — which
        // is what lets vanilla's surface tree keep its skin over our fill (see [TerrainFill]).
        //
        // **What a terrain nobody named a material for is made of is the template's own rock**, and it was
        // stone for every Age. A surface tree paints *patches* — nylium, soul soil, gravel — and leaves the
        // rest to the world's default block, so an infernal Age's hills came out bare grey stone under the
        // nether's own dressing, which then had nothing it recognised to dress (Jonah, 2026-08-14, walked).
        val noiseSettings = server.registryAccess().lookupOrThrow(Registries.NOISE_SETTINGS)
        val theirRockSettings = noiseSettings.getOrThrow(recipe.template.rock)
        val theirRock = theirRockSettings.value().defaultBlock()
        // **The rock's own world, not the template's**, where the Age wears vanilla's: nether rock under an
        // overworld sky is the nether's settings with the overworld's biomes over them.
        val vanillasWorld = composition.terrains.firstNotNullOfOrNull(AgeTemplate::ofRock) ?: recipe.template
        val vanillasRockSettings = noiseSettings.getOrThrow(vanillasWorld.rock)
        // **How wide the biomes are**, which on vanilla's overworld rock is how wide the land is too: its router is
        // read wider ([ClimateStretch]), as vanilla's large biomes is, where stretching only the biomes would pull
        // ocean biomes onto dry land. Elsewhere the biome source stretches its own reading.
        val onVanillasOverworld = ourGround == null && vanillasWorld.rock == NoiseGeneratorSettings.OVERWORLD
        val vanillasClimateStretched = onVanillasOverworld
        // **What the book built under vanilla's overworld**, cut after its fill, and vanilla's own noise caves taken
        // out of its router where the book asked for another underground or for none.
        val vanillasUnderground = composition.terrains.withIndex().firstOrNull { it.value.isVanillas }
            ?.takeIf { ourGround == null }
            ?.let { (member, terrain) ->
                terrain.undergroundCutFromVanilla(
                    composition.underground,
                    composition.optionsFor(Aspect.UNDERGROUND, 0),
                    terrainOptions(member),
                    window,
                    saltFor(seed, member),
                )
            }
        val vanillasCavesTaken = onVanillasOverworld && composition.underground != Underground.NOISE_CAVES
        // **Vanilla's rock pours its own sea**, so the book's depth moves its level rather than a fill of ours,
        // and where the sea's top stands is read off that level — the abyss and its wreckage are laid against it.
        val writtenSeaShift = Sea.depthShift(composition.optionsFor(Aspect.SEA, 0), seed)
        val vanillasSeaShift = Sea.raiseOnVanillasRock(writtenSeaShift)
        val vanillasSeaFluid = composition.seas.firstOrNull()?.substance() ?: vanillasRockSettings.value().defaultFluid()
        // **An abyss on vanilla's rock only where a deep sea was asked for** (Jonah, 2026-10-17), where a rock of
        // ours has one wherever its sea runs deep enough.
        // **And only in a sea of water** (Jonah, 2026-10-19), on either rock — see [Sea.areWater].
        val seaIsWater = Sea.areWater(composition.seas)
        val vanillasAbyssAsked = writtenSeaShift >= Sea.ABYSSAL_RAISE && seaIsWater
        // **A rock that pours no sea of its own takes one only where a book names it** (Jonah, 2026-10-17): the End's
        // sea level is the bottom of its own noise, so it never pours one, and a named sea is laid by the overlay
        // instead — on bedrock, at the bottom of the world, as a sealess landform of ours takes one.
        val vanillasPoursNoSea = vanillasRockSettings.value().let { it.seaLevel() <= it.noiseSettings().minY() }
        val seaUnderVanillasVoid = ourGround == null && vanillasPoursNoSea && composition.seaNamed && !vanillasSeaFluid.isAir
        val seaSurfaceY = seaFill.surfaceY ?: when {
            ourGround != null || vanillasSeaFluid.isAir -> null
            vanillasPoursNoSea -> if (seaUnderVanillasVoid) window.minY + SEA_UNDER_THE_VOID - 1 else null
            else -> vanillasRockSettings.value().seaLevel() + vanillasSeaShift - 1
        }
        // **The abyss on vanilla's rock is laid into each chunk once its rock is poured**, rather than banded by the
        // biome source as ours is: the band stops at the sea bed, and vanilla's is not known until then.
        val hasAnAbyss = abyssLineOf(seaSurfaceY.takeIf { (ourGround != null && seaIsWater) || vanillasAbyssAsked }, window, ourGround == null) != null
        val vanillasAbyss = abyssBiome(server).takeIf {
            ourGround == null && vanillasAbyssAsked && abyssLineOf(seaSurfaceY, window, onVanillasRock = true) != null
        }
        val fill = TerrainFill(
            composition.terrains.mapIndexed { member, terrain ->
                terrain.fillBlocks(terrainOptions(member)).ifEmpty { listOf(theirRock) }
            },
            ground,
            // The first territory's, like `Sea.DEPTH`: the mingling noise is one field over the whole Age.
            composition.terrains.first().mingling(terrainOptions(0), seed),
        )
        // The Age's rock as one list, worked out once: the ores are seeded into it and the features are
        // placed on it, and it was flattened separately for each.
        val rockBlocks = fill.blocks.flatten()
        // What the Age pays and what it may hold are read off one danger, so the two can never disagree (§7.7).
        val danger = Danger.of(server, recipe)
        val below = character.mapFor(Aspect.CARVERS, composition.spreadOf(Aspect.CARVERS), seed)
        // Climate divides on a map of its own: which climate a column has is a different question from what
        // paints it. One climate needs no map and gets `whole` (see [RegionalClimate]).
        // Read straight off the composition: a climate's answer *is* its spans, so there is nothing to
        // derive from options any more (see [AgeComposition.climates]).
        val climate = RegionalClimate(
            composition.climates,
            character.mapFor(Aspect.CLIMATE, composition.spreadOf(Aspect.CLIMATE), seed),
        )
        // One biome source for the whole Age, and no region map: one climate table spans the world however
        // many terrains carve it up.
        val biomeOptions = composition.optionsFor(Aspect.BIOMES, 0)
        val biomeStretch = Biomes.stretchIn(biomeOptions, seed)
        return AgeChunkGenerator(
            // **The world the book was written over decides which biomes there are to choose between.**
            // This was the overworld's list for every Age, so an infernal one grew overworld biomes over
            // nether rock — and the overworld's features with them (Jonah, 2026-08-14, walked).
            AgeBiomeSource(
                recipe.template.biomesOf(
                    server,
                    Biomes.preferencesIn(biomeOptions),
                    Biomes.keepsOnlyNamed(biomeOptions),
                    seed,
                ),
            )
                .told(climate)
                // On vanilla's overworld rock the stretch is in the rock's own router, land and biomes together.
                .stretched(if (vanillasClimateStretched) UNSTRETCHED else biomeStretch)
                .alsoLaying(listOfNotNull(vanillasAbyss))
                .let { if (ourGround == null) it.sampledForDepth() else it.groundedIn(ourGround.landform) }
                // On unless the Age said otherwise — `biomes.footing=free` is the lever, and an Age whose
                // biomes ignore its land is allowed rather than broken. See [Grounding] and [Biomes.FOOTING].
                .suitedTo(
                    if (ourGround == null || !Biomes.groundsBiomes(biomeOptions)) null
                    // A shore is where the Age's one sea meets whichever territory reaches it, so a single
                    // island territory is enough to make the coast sand — the level it stands at is already
                    // Age-wide.
                    else Grounding(
                        ourGround.landform,
                        seaFill.level,
                        // Whether anything is actually poured at that level. A sea of air leaves the
                        // waterline standing with nothing in it, and measuring against it drowns the map.
                        hasSea = seaFill.blocks.any { !it.isAir },
                        rivers = standing,
                        declared = Grounding.Declared.of(
                            composition.terrains.mapIndexed { member, terrain ->
                                terrain.grounding(composition.optionsFor(Aspect.TERRAIN, member), saltFor(seed, member), sea)
                            },
                            seaIsShared = sea?.isShared == true,
                        ),
                        table = table,
                        // An Age written before generator 20 reads one sea and its relief everywhere, as it did.
                        seas = seaFill.territories.takeIf { table != null },
                        keepsItsCoast = table != null,
                    ),
                )
                // Age-wide like the shore and the treeline: the band is a pair of heights, and an Age has
                // one set of those however many territories divide it.
                .banded(
                    listOfNotNull(
                        // Age-wide like the shore and the treeline: the band is a pair of heights, and an
                        // Age has one set of those however many territories divide it.
                        composition.terrains
                            .withIndex()
                            .firstNotNullOfOrNull { (member, terrain) ->
                                terrain.undergroundBand(
                                    composition.underground,
                                    window,
                                    composition.optionsFor(Aspect.TERRAIN, member),
                                    saltFor(seed, member),
                                    sea,
                                )
                            }
                            ?.let { band -> BiomeBand(greatHallBiome(server), band.first, band.last) },
                        // And the abyss, which is the same mechanism for the same reason: eighty blocks of
                        // sea is a place, and no climate axis means it. Its biome is what keeps anything
                        // from growing down there — a feature list is a biome's, so an empty one is a rule.
                        // **Above the sea bed only**, or every cave under the floor comes out as abyss and
                        // the ordinary cave biomes are clobbered — see [BiomeBand.above].
                        abyssLineOf(seaSurfaceY, window)
                            ?.takeIf { ourGround != null && seaIsWater }
                            ?.let { line ->
                                BiomeBand(abyssBiome(server), window.minY, line, ourGround?.rock?.landform, seaFill.takeIf { table != null })
                            },
                    ),
                ),
            ourGround?.rock ?: AgeRock.Vanillas(
                Holder.direct(
                    vanillasRockFor(
                        vanillasRockSettings.value(),
                        composition,
                        fill,
                        SurfacingStrategy.ofOurBiomes(
                            server.registryAccess().lookupOrThrow(Registries.BIOME),
                            vanillasRockSettings.value().seaLevel(),
                            Biomes.grownIn(biomeOptions),
                        ),
                        vanillasSeaShift,
                        flooredForASea = seaUnderVanillasVoid,
                        climateStretch = if (vanillasClimateStretched) biomeStretch else UNSTRETCHED,
                        withoutCaves = vanillasCavesTaken,
                    ),
                ),
            ),
            seaFill,
            // The Surface aspect's answer, not a constant: vanilla's tree paints grass over dirt above
            // water without consulting the biome, so there has to be a way to say "no skin" and a way to
            // lay something else. See [Surface.ruleFor].
            // Only where the rock is ours: a rule delegating to the biomes does so *through* the field
            // tree, and an Age wearing vanilla's rock has none to delegate through. `vanillasRockFor`
            // carries that Age's skin instead.
            ourGround?.let {
                val skin = Surface.ruleFor(
                    composition.optionsFor(Aspect.SURFACE, 0),
                    it.rock,
                    recipe.template,
                    server.registryAccess().lookupOrThrow(Registries.MATERIAL_RULE),
                    server.registryAccess().lookupOrThrow(Registries.BIOME),
                    seaFill.surfaceY,
                    Biomes.grownIn(biomeOptions),
                    deepslateBelow = SurfacingStrategy.deepensToDeepslate(rockBlocks),
                )
                // A landform that is its own roof closes it with bedrock, as vanilla closes the nether's.
                if (composition.roofedByItsRock) SurfacingStrategy.shutOverhead(skin) else skin
            }
                ?: SurfacingStrategy.SUPPRESSED,
            composition.carvers.map { it.carversFor(server) },
            below,
            waterTablesOf(composition, seaFill, seed),
            // One answer for the whole dimension — vanilla places structures against the level, and what
            // it starts from is the template's: the overworld's sets in the nether was the same bug the
            // biomes had.
            Structures.structureSets(
                server,
                composition.optionsFor(Aspect.STRUCTURES, 0),
                recipe.template.standingStructures,
                if (hasAnAbyss) WRECKAGE_ON_AN_ABYSS_FLOOR else emptyMap(),
                withoutWhatIsBuiltUnderground = composition.hasNothingBeneathIt,
            ).let { built -> built + DeepWrecks.structureSets(server, hasAnAbyss, built) } +
                CavernRuins.structureSets(server, composition, danger),
            // **The climate the biomes are looked up by, and it comes from the same world they do.** This
            // was the overworld's for every Age, so a landform of ours over the infernal template chose
            // nether biomes with overworld noise — and over the dark void, where the End picks by distance
            // from the centre and reads that off `erosion`, it scattered the islands' biomes at random.
            // The template's even under another world's rock, since the table is the template's too.
            theirRockSettings,
            fill,
            window,
            // What is placed, which vanilla's own decoration hook takes it — see [Features] for the seam,
            // and what the Age owes its writer laid over the top of it (design §7.7).
            //
            // **The order of the layers is the order they are laid**, features being appended: the
            // deposits sit nearest the biome's own and the character materials furthest out, which is the
            // order the four nested wrappers this replaced happened to produce. See [Decoration].
            Decoration.laidOver(
                Features.placedIn(server, Features.writtenAndCarried(composition), seed, rockBlocks),
                listOfNotNull(
                    Deposits.layer(danger, rockBlocks),
                    // The buried lava tubes were laid here until 2026-09-11. They are their own page now
                    // (`agesandtheart:lava_tubes`) and reach the world through the ordinary claim path,
                    // which also gets them the Age's own rock in their ore targets — something a layer
                    // built by hand never had.
                    Craters.layer(composition, seed),
                    // The paper tree's grove, where its window is met (design §7.1.2).
                    PaperTreeWindow.layer(recipe, skySpec(recipe)),
                    // The scarab colonies an Age that would hold one is found with (design §7.1.2).
                    ScarabHabitat.colonyLayer(server.registryAccess(), recipe),
                ) + EarlyGameRareMaterials.layers(earlyMaterials),
                EarlyGameRareMaterials.swaps(earlyMaterials),
            ),
            // What lives here, narrowing what vanilla resolves per biome and per structure.
            Spawns.livingIn(
                composition.optionsFor(Aspect.SPAWNS, 0),
                Vocabulary.of(server).spawning,
                WhereThingsGrow.creaturesListed(server.registryAccess()),
            ),
            spawnCaps = Spawns.capsOf(composition.optionsFor(Aspect.SPAWNS, 0)),
            nothingLives = Spawns.nothingLives(composition.optionsFor(Aspect.SPAWNS, 0)),
            incongruity = Incongruity.of(composition),
            vanillasAbyss = vanillasAbyss,
            seaIsWater = seaIsWater,
            structuresBuiltOf = StructureMaterials.Written.of(composition.optionsFor(Aspect.STRUCTURES)),
            treesGrownWith = TreeFoliage.of(composition.optionsFor(Aspect.FEATURES)),
            banned = BannedBlocks.named(composition.banned),
            // What the Age's instability bought. Derived here rather than restated: these were three lines
            // recomputing `Consequence.of`'s own arithmetic, which is how a copy comes to drift from it.
            bought = Consequence.of(server, recipe),
            // Read only where the rock is vanilla's; a landform of ours folded the same object in already.
            // On vanilla's rock the overlay also carries the roof a sealed sky needs, which a landform of ours
            // builds into its own shape ([ourGround]); vanilla's overworld and End have none of their own.
            overlay = if (ourGround == null) {
                overlay.alsoRaising(roofOverVanillasRock(composition, window, seed))
                    .let { if (seaUnderVanillasVoid) it.withASeaUnderTheVoid(window, vanillasSeaFluid) else it }
                    .let { if (vanillasUnderground == null) it else it.cutBeneath(vanillasUnderground, vanillasSeaFluid) }
            } else {
                Overlay.NONE
            },
            vanillasSeaSurfaceY = seaSurfaceY.takeIf { ourGround == null },
            dislocation = if (ourGround == null) dislocation else Dislocation.NONE,
        )
    }

    /**
     * **Vanilla's own rock for this Age's template, with the writer's claims laid into it** (world model §4).
     *
     * `NoiseGeneratorSettings` is a record, so this is vanilla's own settings rebuilt: its noise, its router
     * and its spawn target kept, and the Age's block, fluid and skin substituted where it asked for one.
     *
     * A pure function of the template's settings and the composition, so what a book can and cannot change
     * about a rock we did not lay is answerable without a server.
     *
     * **Aquifers and ore veins go back on.** They are off for a field-tree Age because the toolkit answers
     * for water itself; under vanilla's router they are part of the rock being vanilla's.
     */
    fun vanillasRockFor(
        theirs: NoiseGeneratorSettings,
        composition: AgeComposition,
        fill: TerrainFill,
        /**
         * The skins of our own biomes this Age grows, laid ahead of vanilla's, whose tree does not know them
         * and paints a palm beach in grass to the water (Jonah, walk 2026-10-06). Null where it grows none.
         */
        ourBiomes: MaterialRule? = null,
        /**
         * How far the book's sea depth moves vanilla's sea level ([Sea.depthShift]): vanilla fills its seas and
         * caves to the level its settings say, so moving that is what makes a `deep` sea deep on this rock.
         */
        seaLevelShift: Int = 0,
        /** Whether a sea is laid under this rock's void, on a floor its own skin would never paint bedrock. */
        flooredForASea: Boolean = false,
        /** How much wider the router reads its climate — see [ClimateStretch]. */
        climateStretch: Double = UNSTRETCHED,
        /** Whether vanilla's own noise caves are taken out of the router — see [WithoutCaves]. */
        withoutCaves: Boolean = false,
    ): NoiseGeneratorSettings {
        // Only a skin the writer actually named: `Surface.ruleFor` would otherwise delegate to the biomes
        // *through the field tree*, and there is none here to delegate through. Silence means vanilla's own
        // rule, which is what a nether floor of netherrack is.
        val surface = composition.optionsFor(Aspect.SURFACE, 0)
        val named = surface.materialsOf(Surface.MATERIAL)
        val skin: Holder<MaterialRule> = when {
            surface.isTrue(Surface.BARE) -> Holder.direct(SurfacingStrategy.NO_SKIN)
            // **Patches over the rock, not instead of it** — see [SurfacingStrategy.asPatchesOver]. The
            // nether's tree and the End's each end in an unconditional arm that would repaint whatever
            // block was substituted below, and removing it changes nothing until one has been.
            //
            // **And where nothing came off, the holder we were handed goes back untouched.** Vanilla names
            // its trees now — the overworld's settings say `minecraft:overworld` — so re-wrapping an
            // unstripped rule would inline that whole tree into every recipe written over this world, for
            // a rule that is character for character the one it already pointed at.
            named.isEmpty() -> {
                val theirRule = theirs.materialRule()
                val rockOfStone = SurfacingStrategy.deepensToDeepslate(fill.blocks.flatten())
                val faded = if (rockOfStone) theirRule.value() else SurfacingStrategy.withoutTheDeepslateFade(theirRule.value())
                val patches = SurfacingStrategy.asPatchesOver(faded)
                val theirSkin = if (patches === theirRule.value()) theirRule else Holder.direct(patches)
                ourBiomes?.let { Holder.direct(MaterialRules.sequence(it, theirSkin.value())) } ?: theirSkin
            }
            named.all { it.isAir } -> Holder.direct(SurfacingStrategy.NO_SKIN)
            else -> Holder.direct(SurfacingStrategy.laidOnVanilla(named, SurfacingStrategy.deepensToDeepslate(fill.blocks.flatten())))
        }
        // **The substance alone, read off the book rather than off the fill.** A `SeaFill` answers where a
        // sea of *ours* is poured, and vanilla's rocks declare no waterline at all — vanilla's own
        // router places its fluid, so there is nothing here for a fill to pour. That made the fill `NONE`
        // for every Age wearing this rock however the book was written, and reading the sea off it dropped
        // every `sea=` one of them ever named: lava asked for over the overworld came out water, and water
        // asked for over the nether came out lava (Jonah, 2026-08-25, walked).
        val sea = composition.seas.firstOrNull()?.substance() ?: theirs.defaultFluid()
        val floored = if (flooredForASea) Holder.direct(SurfacingStrategy.withTheWorldFloor(skin.value())) else skin
        return NoiseGeneratorSettings(
            theirs.noiseSettings(),
            fill.representative.takeUnless { fill == TerrainFill.PLAIN } ?: theirs.defaultBlock(),
            sea,
            // Uncaved first, while the router still names what it reaches: stretching it inlines every name.
            ClimateStretch.of(if (withoutCaves) WithoutCaves.of(theirs.noiseRouter()) else theirs.noiseRouter(), climateStretch),
            floored,
            theirs.spawnTarget(),
            theirs.seaLevel() + seaLevelShift,
            // Deprecated on the record and still required by its constructor, so this hands back
            // exactly what the settings we were given already said.
            @Suppress("DEPRECATION") theirs.disableMobGeneration(),
            // Vanilla's rock keeps vanilla's aquifer, whatever the settings we were handed carried, reading the land
            // as wide as the router does.
            theirs.aquifers().map { ClimateStretch.of(it, climateStretch) },
            theirs.getRandomSource() == net.minecraft.world.level.levelgen.WorldgenRandom.Algorithm.LEGACY,
            theirs.debugFunctions(),
        )
    }

    /**
     * The water an Age's terrains carry themselves, divided on the terrain's own map — so a territory
     * whose shape has no water of its own contributes none, rather than the whole Age being wet wherever
     * one of them has a river.
     *
     * **Only a scarp reaches it.** A rift is already kept out by `SeaFill.dry`, and a wall *adds* rock,
     * which must not put a wall of water up alongside it.
     *
     * The rivers the terrains run of their own, as one body of water divided as the rock is — water whatever
     * the Age's sea is made of, since an island's river is there whether it has a sea or none.
     */
    private fun riversOf(
        composition: AgeComposition,
        seam: DrawnSeam,
        territories: Territories,
        seed: Long,
        torn: Double,
    ): StandingFluid? {
        val run = composition.terrains.mapIndexed { member, terrain ->
            terrain.rivers(composition.optionsFor(Aspect.TERRAIN, member), saltFor(seed, member), territories.table, territories.sea)
        }
        if (run.all { it == null }) return null
        val divided = territories.divided(run.map { it ?: Union(emptyList()) }, staysInItsBanks = true)
        val where = when (seam.form) {
            Seam.SCARP -> faulted(divided, seam, territories.map, seed, torn)
            Seam.SHEARED, Seam.FUZZED, Seam.RIFT, Seam.WALL, Seam.MERGED -> divided
        }
        return StandingFluid(where, Blocks.WATER.defaultBlockState(), StandingFluid.ISLAND_RIVERS)
    }

    private fun carriedWater(
        composition: AgeComposition,
        seam: DrawnSeam,
        territories: Territories,
        seed: Long,
        torn: Double,
    ): TerrainField? {
        val carried = composition.terrains.mapIndexed { member, terrain ->
            terrain.standingWater(composition.optionsFor(Aspect.TERRAIN, member), saltFor(seed, member), territories.table, territories.sea)
        }
        if (carried.all { it == null }) return null
        val divided = territories.divided(carried.map { it ?: Union(emptyList()) }, staysInItsBanks = true)
        // Exhaustive rather than a test for one form, so a new [Seam] breaks the build here instead of
        // silently taking the wrong branch.
        return when (seam.form) {
            Seam.SCARP -> faulted(divided, seam, territories.map, seed, torn)
            Seam.SHEARED, Seam.FUZZED, Seam.RIFT, Seam.WALL, Seam.MERGED -> divided
        }
    }

    /**
     * [rock] with the terrain's own [seam] made visible along its boundaries — a cliff, a chasm, a wall, or
     * nothing, since the fuzzed form is a blend width and acted on the map instead.
     *
     * **Only the terrain's**, and the population it belongs to is why: a displacement needs rock of its
     * own to displace, so a sea or a climate is never drawn one (see [Seam.drawnFor]).
     *
     * The magnitude is whatever the Age's instability bought (design §5.0).
     *
     * [torn] is 0 for a coherent Age and 1 for one that spent everything it could on tearing, and each
     * form reads it in the units it has: a scarp throws further, a rift cuts deeper, a wall stands higher.
     * **`SHEARED` reads it and does nothing**, on purpose — terrain either side of a shear is usually
     * dramatic enough that the two never meet smoothly, so it is instability already visible, and an Age
     * that drew one keeps its whole budget for something else (Jonah, 2026-08-07).
     */
    private fun faulted(
        rock: TerrainField,
        seam: DrawnSeam,
        ground: RegionMap,
        seed: Long,
        torn: Double,
    ): TerrainField = when (seam.form) {
        // `SHEARED` asks for no fault; `FUZZED` already happened, in the width `mapFor` took off the seam, and
        // `MERGED` in the territories' own division.
        Seam.SHEARED, Seam.FUZZED, Seam.MERGED -> rock
        Seam.SCARP -> Fault.of(rock, ground, seam.thrownAround(Fault.alternatingThrows(ground.members, Seam.scarpThrow(torn), seed)))
        Seam.RIFT -> Rift.opened(rock, ground, Seam.riftFloor(torn), Seam.RIFT_RIM, around = seam.around)
        Seam.WALL -> Ridge.raised(rock, ground, Seam.WALL_FOOTING, Seam.wallCrest(torn), around = seam.around)
    }

    /**
     * The form drawn along the terrain's seams, and the territories it runs [around]: every one where empty, and after
     * a merge only those that cannot be eased ([Spread.uneased]).
     */
    private class DrawnSeam(val form: Seam, val around: List<Boolean>) {
        /** [throws] kept only for the territories the seam runs round, so a merged neighbour stays where it is. */
        fun thrownAround(throws: List<Int>): List<Int> =
            if (around.isEmpty()) throws else throws.mapIndexed { member, thrown -> if (around.getOrElse(member) { false }) thrown else 0 }

        companion object {
            fun of(landmass: Spread, terrains: List<Terrain>): DrawnSeam {
                if (landmass.seam != Seam.MERGED) return DrawnSeam(landmass.seam, emptyList())
                val uneased = landmass.uneased ?: return DrawnSeam(Seam.MERGED, emptyList())
                return DrawnSeam(uneased, terrains.map { !it.reconciles })
            }
        }
    }

    /**
     * The volume a rift took out, for the sea to be kept out of — and nothing for any other seam.
     *
     * Built from the same numbers [faulted] cuts with, so the dry space and the chasm are the same shape
     * by construction rather than by two constants agreeing.
     */
    private fun riftVolume(seam: DrawnSeam, ground: RegionMap, torn: Double): TerrainField? =
        if (seam.form != Seam.RIFT || ground.members <= 1) null
        else Rift(ground, Rift.DEFAULT_HALF_WIDTH, Seam.riftFloor(torn), Seam.RIFT_RIM, around = seam.around)

    /**
     * [carried] with whatever water the **underground** holds added to it — the lakes standing in a
     * chambered Age's vaults, which answer to their own level rather than to the Age's waterline.
     *
     * The same shape as [keptDry] and for the same reason: an underground is per territory, so its water
     * is laid on the territory map beside the terrain's own rather than poured over the whole world.
     */
    private fun withLakes(
        carried: TerrainField?,
        grounds: List<Terrain.Ground>,
        territories: Territories,
    ): TerrainField? {
        if (grounds.none { it.wet != null }) return carried
        val perTerritory = territories.divided(grounds.map { it.wet ?: Union(emptyList()) })
        return if (carried == null) perTerritory else Union(listOf(carried, perTerritory))
    }

    /**
     * [chasm] and every territory's own dry underground, as one volume the sea is kept out of.
     *
     * Divided on the terrain's map like the rock itself, so a territory that has halls keeps its own dry
     * and a neighbour that does not is unaffected — an Age is allowed to be wet next door to dry, so long
     * as the boundary is a wall of rock rather than of water.
     */
    private fun keptDry(
        chasm: TerrainField?,
        grounds: List<Terrain.Ground>,
        territories: Territories,
    ): TerrainField? {
        if (grounds.none { it.dry != null }) return chasm
        val perTerritory = territories.divided(grounds.map { it.dry ?: Union(emptyList()) })
        return if (chasm == null) perTerritory else Union(listOf(chasm, perTerritory))
    }

    /**
     * Where water stands in this Age's rock — one table per carving, each answering for its own territory.
     * A carving with no table of its own contributes the sea's, so the list lines up with
     * the territory map index for index.
     */
    private fun waterTablesOf(composition: AgeComposition, seaFill: SeaFill, seed: Long): List<WaterTable> =
        composition.carvers.mapIndexed { member, carving ->
            carving.waterTable(seaFill, seed, composition.optionsFor(Aspect.CARVERS, member))
                ?: WaterTable.matching(seaFill, seaFill.level, seed)
        }

    /**
     * Each terrain territory's own waterline, null where that landform has no sea — which a floating one
     * beside a grounded one has unless the book named a sea. Where every landform floats, [AgeComposition.seas]
     * already says it: an unnamed sea over them is seated as none.
     */
    private fun waterlinesOf(composition: AgeComposition, seed: Long, sea: Terrain.SeaSetting?): List<Int?> {
        val everyLandformFloats = composition.terrains.all { it.takesASeaOnlyWhenNamed }
        return composition.terrains.mapIndexed { member, terrain ->
            val withheld = terrain.takesASeaOnlyWhenNamed && !composition.seaNamed && !everyLandformFloats
            if (withheld) null
            else terrain.waterlineAt(composition.optionsFor(Aspect.TERRAIN, member), saltFor(seed, member), sea)
        }
    }

    /**
     * The Age's one waterline, for whatever has to have one number — vanilla's sea level, the shores, the
     * aquifers, the abyss plane — or null where no territory has a sea at all. Each territory pours its own
     * ([waterlinesOf]); this is only the level the rest of the world is told.
     *
     * The widest territory with a sea wins, since it is its coastline most of the world has. The seed
     * decides only where shares tie.
     */
    private fun waterlineOf(composition: AgeComposition, claimed: List<Int?>, seed: Long): Int? {
        if (claimed.size == 1) return claimed.first()
        val shares = composition.spreadOf(Aspect.TERRAIN).shares
        val withASea = claimed.indices.filter { claimed[it] != null }
        if (withASea.isEmpty()) return null
        val widest = withASea.maxOf { shares[it] }
        val contenders = withASea.filter { shares[it] == widest }
        return claimed[contenders[XoroshiroRandomSource(seed xor WATERLINE_SALT).nextInt(contenders.size)]]
    }

    /**
     * The terrain half of an Age whose shape is the field tree's — everything the rock decides, gathered so
     * that the rest of [assemble] has one nullable to ask rather than a flag to carry.
     */
    private class OurGround(
        val rock: AgeRock.Ours,
        /** Everywhere the sea is kept out of: the chasm a rift opened, and any underground that stays dry. */
        val chasm: TerrainField?,
        /** Water a landform carries above the waterline, which is the shape's rather than the sea's. */
        val standing: TerrainField?,
    ) {
        /** The land, without whatever shuts it overhead — see [AgeRock.Ours.ground]. */
        val landform: TerrainField get() = rock.landform
    }

    /** [OurGround] for an Age with a landform of its own — the only path that builds a field. */
    private fun ourGround(
        composition: AgeComposition,
        grounds: List<Terrain.Ground>,
        drawnSeam: DrawnSeam,
        territories: Territories,
        window: VerticalWindow,
        seed: Long,
        torn: Double,
        /**
         * **Handed in rather than built here, and that is not only tidiness.** `Instanced` poses every
         * template up front — four cone shapes at four sizes is sixteen whole warped trees — so building
         * the overlay twice builds those twice. It also made the lava bodies the sea carries a *different*
         * object from the one the rock was cut from, which is the exact trap `StandingFluid.named` warns
         * about: a second field is free to disagree with the first.
         */
        volcanic: Overlay,
        dislocation: Dislocation,
    ): OurGround {
        val ground = territories.map
        // Weathering is not applied here at all: a landform that wants wind carries it inside its own
        // field, where the profile and the shape were designed together. There is no Age-wide pass.
        val weathered = territories.divided(grounds.map { it.shape }, keepsFloor = true)
        // The fault comes last, over the finished rock — see [Fault].
        val shape = Dislocated.over(faulted(weathered, drawnSeam, ground, seed, torn), dislocation)
        val riftCut = riftVolume(drawnSeam, ground, torn)
        // **A world shut overhead needs something to shut it.** A template's roof is part of its rock, so
        // naming a landform took it away and left `sealed=always` saying only what the dimension type says
        // — no skylight, and open air to the top of the world (Jonah, 2026-08-14, walked).
        // **Nothing to add where the landform is already the roof**, and adding it anyway would hang the
        // vault's own pendants inside solid rock and pay for a second field to do it. Read off the
        // composition rather than re-derived, so the two cannot disagree about what "roofed" means.
        val lid = if (composition.roofedByItsRock || !Sky.isRoofed(composition)) null
        else CeilingField.over(window, seed)
        // Volcanoes stand *on* whatever landform the Age has rather than replacing it, so they are a layer
        // over the finished rock — the same shape of thing a roof is, and read from the same recipe fact
        // the lava tubes and the danger evaluator read.
        //
        // **The chambers answer their own claim**, so deep magma with no surface expression is a world a
        // writer may ask for. What stands above one is the volcano's business and not theirs.
        //
        // **Built as an [Overlay] rather than folded in here**, so the same statement serves an Age with a
        // landform of ours and one wearing vanilla's rock. This path folds it into the field tree, where it
        // is analytic and free; the generator writes it into the chunk for the other. Before that, a word
        // like `volcano` was taken, charged for and scored, and then produced no mountains at all.
        val cones = volcanic.raises
        // **A sea stands on bedrock** (Jonah, 2026-10-06): one layer of rock along the floor of the world,
        // which the surface's own world floor paints as bedrock, so a sea poured into the void cannot be swum
        // out of the bottom. Under ground it is rock that was already there.
        val floor = if (composition.seas.any { !it.isEmpty }) Slab(lowY = window.minY, highY = window.minY) else null
        val raised = Union(listOfNotNull(shape, cones, lid, floor))
            .takeIf { cones != null || lid != null || floor != null } ?: shape
        // The magma chambers are taken out of everything, cones included: a hollow in a volcano's own root
        // is exactly where one belongs, and the pool poured into it below is the same shape.
        val standingRock = if (volcanic.hollows == null) raised else Subtract(raised, volcanic.hollows)
        return OurGround(
            // The rock the underground was taken out of is **handed to the generator rather than to the
            // sea**. A flat waterline fills any empty space beneath it, so a shape-cut cave or hall comes
            // out flooded to the roof; making it simply *dry* instead would only trade one uniform answer
            // for the other. What that space wants is the same three-way `WaterTable` a carved cave meets.
            AgeRock.Ours(
                field = standingRock,
                hollows = openedBy(hollowedRock(grounds, territories)?.let { Dislocated.over(it, dislocation) }, riftCut),
                // The land kept apart from the lid, since a ceiling is not ground however solid it is. A
                // cone is ground, so it stays in — what this separates is the roof, not everything added.
                ground = Union(listOfNotNull(shape, cones)).takeIf { lid != null },
            ),
            chasm = keptDry(riftCut, grounds, territories),
            standing = withLakes(carriedWater(composition, drawnSeam, territories, seed, torn), grounds, territories),
        )
    }

    private val LAVA = Blocks.LAVA.defaultBlockState()

    /** So where the ground is foreign is decorrelated from everything else this seed decides. */
    private const val DISLOCATION_SALT = 0x464F_5245_4947_4EL

    /** A dial bought in full. */
    private const val ALL_OF_IT = 1.0

    /** A climate read at the size vanilla reads it. */
    private const val UNSTRETCHED = 1.0

    /** So which sea wins is decorrelated from everything else this seed decides. */
    private const val WATERLINE_SALT = 0x5EA_1E7EL

    /**
     * The few Ages that are a whole generator rather than an assembly of parts. Exhaustive, so a new
     * bespoke preset fails to compile rather than quietly falling through to another's world.
     */
    private fun bespoke(server: MinecraftServer, preset: AgePreset): ChunkGenerator = when (preset) {
        AgePreset.VANILLA -> VanillaDelegate.overworld(server)
        AgePreset.VANILLA_BARE -> VanillaDelegate.bareOverworld(server)

        AgePreset.SPIRE, AgePreset.PYRAMIDS, AgePreset.PYRINGS, AgePreset.PYRVARIED, AgePreset.HILLS,
        AgePreset.PILLARS, AgePreset.TUNNELS, AgePreset.ERODED, AgePreset.CANYON,
        AgePreset.CLIFFS, AgePreset.CANYONLANDS, AgePreset.SHATTERED, AgePreset.RIVERLANDS,
        AgePreset.ISLANDS, AgePreset.ISLE, AgePreset.MOUNTAINOUS, AgePreset.CRATERLANDS, AgePreset.INVERSE_CAVES,
        AgePreset.HALLS, AgePreset.FLATLANDS, AgePreset.SOLID, AgePreset.CHAMBERS,
        -> error("'${preset.key}' names a composition, so AgeRecipe.worldFor should never have sent it here")
    }

    /**
     * [rock] with the chasm taken back out of it — **the rift has to be cut from the hollow as well as
     * from the shape**, and forgetting it flooded every rift in the mod.
     *
     * A terrain with noise caves hands over its *uncut* rock as the hollow, because a carved cave meets
     * the water table on its way out and has to answer to one. The rift is opened afterwards, at the Age
     * level, so that hollow still claimed the chasm — and the hollow branch is asked *before* the sea and
     * answers from the aquifer, which takes its substance from the sea. A canyon with dry rims came out
     * filled to the waterline with source blocks, and `SeaFill.dry` was working perfectly the whole time:
     * the water never came through the door it was guarding (Jonah, 2026-08-06, walked).
     *
     * Nothing inside a chasm is rock a cave was cut from. It is the open air of a hole in the world.
     */
    private fun openedBy(rock: TerrainField?, chasm: TerrainField?): TerrainField? =
        if (rock == null || chasm == null) rock else Subtract(rock, chasm)

    /**
     * The rock as it stood **before** its underground was cut, or null where no territory has one.
     *
     * Each terrain declares its own (`Terrain.Ground.hollows`) rather than being inspected for one. Asking
     * the node what kind it is only ever answered for caves — a hall is a plain `Subtract` and would have
     * read as "no underground here", which is the waterline flooding every storey.
     *
     * The declared volume is the very instance the shape is built on, not a rebuilt copy, so it answers
     * from the same cache; building a second would pay for the whole landform twice.
     */
    private fun hollowedRock(grounds: List<Terrain.Ground>, territories: Territories): TerrainField? {
        if (grounds.none { it.hollows != null }) return null
        return territories.divided(grounds.map { it.hollows ?: Union(emptyList()) }, keepsFloor = true)
    }

    /** Each territory's ground, its underground cut into it — the rock everything else divided on its map follows. */
    private fun groundsOf(
        composition: AgeComposition,
        window: VerticalWindow,
        seed: Long,
        table: TerrainTable?,
        sea: Terrain.SeaSetting?,
    ): List<Terrain.Ground> =
        composition.terrains.mapIndexed { member, terrain ->
            terrain.ground(
                composition.underground,
                composition.optionsFor(Aspect.UNDERGROUND, 0),
                composition.optionsFor(Aspect.TERRAIN, member),
                window,
                saltFor(seed, member),
                table,
                sea,
            )
        }

    /**
     * The table this Age's land is laid out by, one for every landform in it — none for an Age written before there
     * was one ([Terrain.FORMER_LAND]), whose land and biomes stay as they were.
     */
    private fun terrainTableOf(composition: AgeComposition, seed: Long): TerrainTable? =
        if (composition.anyMemberSays(Aspect.TERRAIN, Terrain.FORMER_LAND)) null else TerrainTable(seed xor TABLE_SALT)

    /**
     * The terrain's territories laid out by [table], each landform where the land suits it — unless the Age asked for
     * a [Terrain.PATCHWORK], or has no table, when they fall where chance draws them.
     */
    private fun laidOut(map: RegionMap, table: TerrainTable?, composition: AgeComposition, seed: Long): RegionMap {
        val isDrawnByChance = table == null || composition.anyMemberSays(Aspect.TERRAIN, Terrain.PATCHWORK)
        if (isDrawnByChance || map.members <= 1) return map
        val settles = !composition.anyMemberSays(Aspect.TERRAIN, Terrain.FORMER_LAYOUT)
        return map.copy(layout = TableLayout(table, composition.terrains.map { it.home }, map.shares, seed xor TABLE_SALT, settles = settles))
    }

    /**
     * What is divided on the terrain's map: by territory, and where the landforms were reconciled ([Seam.MERGED])
     * eased across each seam after [guides], the rock — so water and hollows move with the ground they lie in. A
     * territory [eases] leaves out keeps a hard seam.
     */
    private class Territories(
        val map: RegionMap,
        private val guides: List<TerrainField>?,
        val table: TerrainTable?,
        val sea: Terrain.SeaSetting?,
        private val eases: List<Boolean> = emptyList(),
    ) {
        fun divided(members: List<TerrainField>, keepsFloor: Boolean = false, staysInItsBanks: Boolean = false): TerrainField =
            if (guides == null) Regions.of(members, map)
            // An Age written before the table eases its water with its rock, as it did.
            else Reconciled.of(members, guides, map, RECONCILED_BLOCKS, keepsFloor, staysInItsBanks && table != null, eases)
    }

    /** How far either side of a seam two reconciled landforms are eased into one another. */
    private const val RECONCILED_BLOCKS = 96.0

    private const val TABLE_SALT = 0x7AB1_E5EEDL

    /**
     * The mountains, the chambers and the lava in both, as one statement (design §7.1.2).
     *
     * **Built here rather than inside the field tree, so both kinds of Age can honour it.** The cones are a
     * layer over whatever ground there is and the chambers are cut out of it, which is the same intent
     * whether the ground came from a field of ours or from vanilla's router — see [Overlay], which exists
     * because expressing it only one way meant a writer could say `volcano` and get no mountains.
     *
     * **The chambers answer their own claim**, so deep magma with no surface expression is a world a writer
     * may ask for. What stands above one is the volcano's business and not theirs.
     */
    /**
     * [cut] taken out under vanilla's ground, its lakes standing in the Age's sea or, where it has none, in water —
     * the chambers carry their own lakes whatever the world around them pours.
     */
    private fun Overlay.cutBeneath(cut: Terrain.UndergroundCut, seaFluid: BlockState): Overlay = copy(
        beneath = cut.void,
        poursBeneath = listOfNotNull(cut.lakes?.let { StandingFluid(it, if (seaFluid.isAir) WATER else seaFluid) }),
    )

    private val WATER = Blocks.WATER.defaultBlockState()

    /** A sea poured under the void of vanilla's rock: a floor at the bottom of the world, and the sea over it. */
    private fun Overlay.withASeaUnderTheVoid(window: VerticalWindow, fluid: BlockState): Overlay =
        alsoRaising(Slab(lowY = window.minY, highY = window.minY))
            .alsoPouring(StandingFluid(Slab(lowY = window.minY + 1, highY = window.minY + SEA_UNDER_THE_VOID - 1), fluid))

    /** How deep a sea laid under a world's void stands, floor included — tangled's and skyborne's own. */
    private const val SEA_UNDER_THE_VOID = 16

    /**
     * The roof a sealed sky needs over vanilla's rock, or null where it needs none: the sky is open, or the rock
     * is its own roof, as the nether's is. Without it a sealed overworld was open air in endless dark, and an
     * arrival took the ground for the roof and dug down through it.
     */
    private fun roofOverVanillasRock(composition: AgeComposition, window: VerticalWindow, seed: Long): TerrainField? =
        if (composition.roofedByItsRock || !Sky.isRoofed(composition)) null else CeilingField.over(window, seed)

    internal fun volcanicOverlay(composition: AgeComposition, seed: Long): Overlay {
        // **The amount, not merely the fact.** Read as a number here so a quantifier reaches the rock: a
        // claim's density scales the scatter that places these, where until 2026-09-11 the terrain asked a
        // yes-or-no question and `teeming volcano` built the same mountains as `volcano`.
        val volcanoes = Volcanoes.amountIn(composition)?.let { VolcanoField.over(seed, it) }
        val chambers = MagmaChambers.amountIn(composition)?.let { MagmaChamberField.chambers(seed, it) }
        // **Named, because both bodies are lava and a feature has to find its own.** See [StandingFluid.named].
        return Overlay(
            raises = volcanoes?.cones,
            hollows = chambers?.cones,
            pours = listOfNotNull(
                volcanoes?.lakes?.let { StandingFluid(it, LAVA, StandingFluid.CRATER_LAKES) },
                chambers?.lakes?.let { StandingFluid(it, LAVA, StandingFluid.CHAMBER_POOLS) },
            ),
        )
    }

    /**
     * Where this Age's abyss begins, or null for one that has none — a sea too shallow, or no sea at all.
     *
     * One place, because two things read it: the biome band and the wreckage below.
     */
    private fun abyssLineOf(seaSurfaceY: Int?, window: VerticalWindow, onVanillasRock: Boolean = false): Int? =
        seaSurfaceY?.let { DeepWater.lineBelow(it, onVanillasRock) }?.takeIf { it > window.minY }

    /**
     * What an abyss gets more of, and why it is structures rather than a biome that carries it.
     *
     * **A biome cannot make a structure commoner** — it only gates whether a candidate the *placement*
     * already proposed is accepted. Density lives in the `StructureSet`'s spacing, and shipping our own
     * `worldgen/structure_set/` override would change every dimension in the game. `StructureDensity`
     * applies that same vanilla lever to one Age at a time.
     *
     * **Inorganic only** (Jonah, 2026-09-10): the floor of an abyss is realistically very plain, and what
     * makes it worth crossing should be things that *sank* rather than things that grew. The whalefall in
     * the abyss biome's own feature list is the other half of the same answer.
     */
    private val WRECKAGE_ON_AN_ABYSS_FLOOR: Map<Identifier, Double> = mapOf(
        // Not the shipwrecks, which the abyss has a set of its own for (`DeepWrecks`), so the Age's other seas keep
        // vanilla's.
        "minecraft:ocean_ruin_cold".asStructureSet() to FOUR_TIMES,
        "minecraft:ocean_ruin_warm".asStructureSet() to FOUR_TIMES,
        // Gently: a monument is enormous and several of them close together stop reading as landmarks.
        "minecraft:ocean_monuments".asStructureSet() to HALF_AGAIN,
    )

    private const val FOUR_TIMES = 4.0


    private const val HALF_AGAIN = 1.5

    private fun String.asStructureSet(): Identifier = Identifier.parse(this)

    private fun abyssBiome(server: MinecraftServer): Holder<Biome> =
        server.registryAccess().lookupOrThrow(Registries.BIOME)
            .getOrThrow(ResourceKey.create(Registries.BIOME, ABYSS_BIOME))

    private val ABYSS_BIOME: Identifier = "abyss".location()

    /** The biome a great hall is, rather than whichever cave biome its climate would otherwise name. */
    private fun greatHallBiome(server: MinecraftServer): Holder<Biome> =
        server.registryAccess().lookupOrThrow(Registries.BIOME)
            .getOrThrow(ResourceKey.create(Registries.BIOME, Biomes.GREAT_HALL_BIOME))

    /**
     * The seed a territory's shape is built from: the Age's own, mixed with which territory it is.
     *
     * Two things depend on this. A terrain preset carries fixed noise seeds, so without a salt every
     * `hills` Age would raise the same hills; and two territories of the same preset in one Age would be
     * identical, leaving nothing for a seam to divide.
     */
    internal fun saltFor(seed: Long, member: Int): Long = seed * TERRITORY_SALT_STRIDE + member

    /** Odd and large, so consecutive members land far apart in the noise rather than adjacent. */
    private val TERRITORY_SALT_STRIDE = 0x9E37_79B9_7F4A_7C15uL.toLong()

    /**
     * **The spawners this Age carries of its own**, for the creatures vanilla's own will not place.
     *
     * Here rather than in either backend, which are otherwise the same file twice: what a level is made of
     * is policy and belongs in common, where opening one is Ephemeris'.
     */
    fun spawnersFor(server: MinecraftServer, recipe: AgeRecipe): List<CustomSpawner> {
        val composition = recipe.composition ?: return emptyList()
        val placing = AgeSpawner.placing(
            composition.optionsFor(Aspect.SPAWNS, 0),
            Vocabulary.of(server).spawning,
        )
        // **The one reward that arrives as an entity**, so it is here rather than among the decoration
        // layers — asked of the recipe by the same gate the survey reads, so a book can promise it before
        // the Age is opened (design §7.7).
        val charged = EarlyGameRareMaterials.growsArcCrystal(composition, recipe.seed, Spending.of(server, recipe))
        return listOfNotNull(placing, DriftingOreSpawner().takeIf { charged })
    }

    /**
     * The dimension type an Age wears — **whether the sky reaches it, and whether there is rock overhead**.
     *
     * It used to be the colour of the air, which is why the Spire had a type of its own; `Atmosphere` says
     * every one of those colours better, so the palette moved to a [co.voik.ephemeris.sky.Look] and
     * what is left here is the two things only a pre-authored file can carry.
     *
     * The band of world is deliberately *not* here. All four types carry the same one —
     * [VerticalWindow.DEFAULT] — so a sky cannot move an Age's ground, which is exactly what it used to do,
     * to any landform reaching below y=0 that drew the Spire's sky by chance.
     */
    fun dimensionType(recipe: AgeRecipe): Identifier = when (val world = recipe.world) {
        is AgeWorld.Composed -> typeFor(world.composition)
        is AgeWorld.Bespoke -> Sky.AGE_DIMENSION_TYPE
    }

    /**
     * **A world wearing vanilla's rock wears that rock's world's type too.**
     *
     * Ours are three re-statements of vanilla's three, and every number in them is one somebody had to
     * write down — which is how an infernal Age came to have fog reaching to an overworld horizon, a flat
     * ambient dark where the nether glows, and a −64..320 band around rock that generates 0..128.
     *
     * **Until the book says otherwise.** The type follows the facts of the Age (`Sky.dimensionType`), so a
     * writer with words enough to unseal a nether-shaped world gets a world the game agrees is open — and
     * that is the case ours are still for. The test is whether the Age's own facts still describe the world
     * it was written over, rather than whether any word was said.
     */
    private fun typeFor(composition: AgeComposition): Identifier {
        val ours = Sky.dimensionType(composition)
        // Only where the rock is vanilla's: a landform of ours generates into our own vertical band, and
        // vanilla's nether is a hundred and twenty-eight blocks tall. **The rock's world, not the
        // template's**, since the band is the rock's to need.
        val rocksWorld = composition.terrains.firstNotNullOfOrNull(AgeTemplate::ofRock) ?: return ours
        val theirs = Sky.dimensionType(rocksWorld.world())
        return if (ours == theirs) rocksWorld.dimensionType.identifier() else ours
    }

    /**
     * **A day of the Age's own**, starting at an hour its seed draws — so linking in can put you at dusk somewhere
     * it is noon at home, and sleeping there moves its morning and nobody else's. Only where it starts: where it
     * stands after that is saved with the level.
     */
    fun clockFor(recipe: AgeRecipe): OwnClock = OwnClock(startsAt = SkyBodies.dayBeginsAt(recipe.seed))

    /**
     * Whether any of this Age's land hangs over the void — which is what keeps its sky blue below the
     * horizon (`RuntimeLevelConfig.horizonAtTheFloor`) and its buried structures unbuilt.
     */
    fun hasNothingBeneathIt(recipe: AgeRecipe): Boolean = when (val world = recipe.world) {
        is AgeWorld.Composed -> world.composition.hasNothingBeneathIt
        is AgeWorld.Bespoke -> false
    }

    /** The sky an Age has, as data the client can be told. A pure function of the recipe. */
    fun skySpec(recipe: AgeRecipe): SkySpec = when (val world = recipe.world) {
        is AgeWorld.Composed -> Sky.specFor(world.composition, recipe.seed)
        is AgeWorld.Bespoke -> SkySpec.VANILLA
    }

    /** The look an Age paints under whatever its sentence asked for — the Spire's palette, or nothing. */
    fun lookUnderTheSentence(recipe: AgeRecipe): Look = when (val world = recipe.world) {
        is AgeWorld.Composed -> Sky.lookUnder(world.composition)
        is AgeWorld.Bespoke -> Look.NOTHING
    }
}
