package co.voik.agesandtheart.age

import co.voik.agesandtheart.age.aspect.Biomes
import co.voik.agesandtheart.age.aspect.Sea
import co.voik.agesandtheart.worldgen.field.SeaFill
import co.voik.agesandtheart.worldgen.field.WaterTable
import kotlin.math.pow
import co.voik.agesandtheart.age.consequence.Collapse
import co.voik.agesandtheart.age.consequence.Tearing
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Options
import co.voik.agesandtheart.age.aspect.Terrain
import co.voik.ephemeris.sky.Look
import co.voik.ephemeris.sky.SkySpec
import co.voik.agesandtheart.sky.SpireSky
import co.voik.agesandtheart.worldgen.biome.AgeBiomeSource
import co.voik.agesandtheart.worldgen.biome.Grounding
import co.voik.agesandtheart.worldgen.biome.RegionalClimate
import co.voik.agesandtheart.content.DeepWater
import co.voik.agesandtheart.worldgen.biome.BiomeBand
import co.voik.agesandtheart.worldgen.field.Fault
import co.voik.agesandtheart.worldgen.field.SurfacingStrategy
import co.voik.agesandtheart.worldgen.field.RegionMap
import co.voik.agesandtheart.worldgen.field.Regions
import co.voik.agesandtheart.worldgen.field.Ridge
import co.voik.agesandtheart.worldgen.field.Rift
import co.voik.agesandtheart.worldgen.field.TerrainField
import co.voik.agesandtheart.worldgen.field.Subtract
import co.voik.agesandtheart.age.aspect.MagmaChambers
import co.voik.agesandtheart.worldgen.field.StandingFluid
import co.voik.agesandtheart.worldgen.field.Union
import co.voik.agesandtheart.worldgen.field.Weathered
import co.voik.agesandtheart.worldgen.field.TerrainFill
import net.minecraft.world.level.block.Blocks
import co.voik.agesandtheart.location
import co.voik.agesandtheart.worldgen.AgeChunkGenerator
import co.voik.agesandtheart.worldgen.AgeRock
import co.voik.agesandtheart.worldgen.CeilingField
import co.voik.agesandtheart.worldgen.Overlay
import co.voik.agesandtheart.worldgen.VolcanoField
import co.voik.agesandtheart.worldgen.SpireChunkGenerator
import co.voik.agesandtheart.worldgen.VerticalWindow
import co.voik.agesandtheart.worldgen.VanillaDelegate
import net.minecraft.core.Holder
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.biome.Biome
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.biome.FixedBiomeSource
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings
import net.minecraft.world.level.levelgen.XoroshiroRandomSource
import co.voik.agesandtheart.age.aspect.Features
import co.voik.agesandtheart.age.reward.EarlyGameRareMaterials
import co.voik.agesandtheart.age.reward.Craters
import co.voik.agesandtheart.age.reward.Danger
import co.voik.agesandtheart.age.reward.Decoration
import co.voik.agesandtheart.age.reward.Deposits
import co.voik.agesandtheart.age.aspect.AgeSpawner
import co.voik.agesandtheart.age.phenomena.DriftingOreSpawner
import net.minecraft.world.level.CustomSpawner
import co.voik.agesandtheart.age.aspect.Spawns
import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.aspect.Structures
import co.voik.agesandtheart.age.aspect.Sky
import co.voik.agesandtheart.age.aspect.Volcanoes
import co.voik.agesandtheart.age.aspect.Surface

/**
 * Turns an [AgeRecipe] into the generator that builds its world — a pure function of the recipe (plus the
 * server, for registries), because an Age must rebuild identically on every open.
 */
object AgeGeneration {
    /**
     * The three types an Age with rock of its own may wear — `Sky.SKYLIGHT` and `Sky.ROOF`, spelled out.
     *
     * A composed `DimensionType` cannot be encoded in the join packet, so every combination is a file, and
     * each further switch would double them. All three declare [VerticalWindow.DEFAULT], which is the band
     * a field tree builds into; an Age wearing a template's rock wears that world's own type instead, and
     * its band with it (see [typeFor]).
     */
    val AGE_DIMENSION_TYPE: Identifier = "age".location()
    val AGE_LIGHTLESS_DIMENSION_TYPE: Identifier = "age_lightless".location()
    val AGE_LIGHTLESS_ROOFED_DIMENSION_TYPE: Identifier = "age_lightless_roofed".location()

    /** The custom biome (green plasma water), registered as a datapack biome at load. */
    val PLASMA_BIOME: Identifier = "plasma".location()

    /** The biome an Age's great halls are, carrying their own dark and their own sound. */
    val GREAT_HALL_BIOME: Identifier = "great_hall".location()

    fun chunkGenerator(server: MinecraftServer, recipe: AgeRecipe): ChunkGenerator = when (val world = recipe.world) {
        is AgeWorld.Composed -> assemble(server, world.composition, recipe)
        is AgeWorld.Bespoke -> bespoke(server, world.preset, recipe.seed)
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
        val prices = Price.list(server)
        val spending = Spending.of(server, recipe)
        val torn = spending.reach(Manifestation.TORN_SEAMS, prices)
        // Each step bought multiplies how many open, so the register climbs from "half the chunks hold
        // one" to "the world is holed through" over the range a badly written Age can reach.
        val wounds = Tearing.writtenDensityAt(spending.bought(Manifestation.WOUNDS))
        // And how much worse each of the Age's days makes it. The generator reads the clock itself, so a
        // chunk generated a week in comes out as torn as its neighbours rather than as the book left it.
        val worsening = Tearing.woundsPerDayAt(spending.bought(Manifestation.WORSENING_WOUNDS))
        // And how fast the floor gives way, for the few Ages that were written past saving.
        val collapse = Collapse.tearsPerCellAt(spending.bought(Manifestation.COLLAPSE))

        // One band for every Age, and the same one every dimension type admits — see [VerticalWindow].
        val window = VerticalWindow.DEFAULT

        val ground = character.mapFor(Aspect.TERRAIN, landmass, seed, torn)
        // **Null where the rock is vanilla's, and this is the one place that is decided**: no field is
        // built, no biome is grounded in one, nothing is hollowed under it, and the generator hands
        // `fillFromNoise` and its companions to the base class. Named at all rather than named alone —
        // `/age compose` refuses the mixture, and a hand-written recipe that says it anyway gets vanilla's
        // rock rather than a landform asked for a field it has none of.
        val ourGround = if (Terrain.VANILLA in composition.terrains) null
        else ourGround(composition, landmass, ground, window, seed, torn)
        // **Built whether or not there is a landform of ours**, which is the whole point of it: an Age
        // wearing vanilla's rock still gets its mountains, written into the chunk after vanilla's own fill.
        // `ourGround` has already folded this same object into its field tree, so only the other path reads
        // it from here — see [Overlay].
        val overlay = volcanicOverlay(composition, seed)

        val chasm = ourGround?.chasm
        val standing = ourGround?.standing
        val flow = character.mapFor(Aspect.SEA, composition.spreadOf(Aspect.SEA), seed)
        val seaFill = Sea.pour(
            composition.seas,
            waterlineOf(composition, seed),
            // The first territory's: `depth` shifts the waterline, which is one number for the whole Age.
            // The sea's substance divides; the level does not.
            composition.optionsFor(Aspect.SEA, 0),
            flow,
            seed,
            // **The bodies are carried whichever path built them**, because `VolcanoVents` finds its lava
            // through this and would otherwise seat no vents at all in a vanilla-rock Age.
        ).copy(dry = chasm, wet = standing, carried = overlay.pours)

        // What the rock *is*, on the terrain's own map, laid by the fill rather than painted by a rule — which
        // is what lets vanilla's surface tree keep its skin over our fill (see [TerrainFill]).
        //
        // **What a terrain nobody named a material for is made of is the template's own rock**, and it was
        // stone for every Age. A surface tree paints *patches* — nylium, soul soil, gravel — and leaves the
        // rest to the world's default block, so an infernal Age's hills came out bare grey stone under the
        // nether's own dressing, which then had nothing it recognised to dress (Jonah, 2026-08-14, walked).
        val theirRockSettings = server.registryAccess().lookupOrThrow(Registries.NOISE_SETTINGS)
            .getOrThrow(recipe.template.rock)
        val theirRock = theirRockSettings.value().defaultBlock()
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
        // many terrains carve it up (design §3.1).
        val biomeOptions = composition.optionsFor(Aspect.BIOMES, 0)
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
                        declared = Grounding.Declared.of(composition.terrains.map { it.grounding() }),
                    ),
                )
                // Age-wide like the shore and the treeline: the band is a pair of heights, and an Age has
                // one set of those however many territories divide it.
                .banded(
                    listOfNotNull(
                        // Age-wide like the shore and the treeline: the band is a pair of heights, and an
                        // Age has one set of those however many territories divide it.
                        if (ourGround == null) null else composition.terrains
                            .withIndex()
                            .firstNotNullOfOrNull { (member, terrain) ->
                                terrain.undergroundBand(composition.underground, window)
                            }
                            ?.let { band -> BiomeBand(greatHallBiome(server), band.first, band.last) },
                        // And the abyss, which is the same mechanism for the same reason: eighty blocks of
                        // sea is a place, and no climate axis means it. Its biome is what keeps anything
                        // from growing down there — a feature list is a biome's, so an empty one is a rule.
                        // **Above the sea bed only**, or every cave under the floor comes out as abyss and
                        // the ordinary cave biomes are clobbered — see [BiomeBand.above].
                        abyssLineOf(seaFill, window)
                            ?.takeIf { ourGround != null }
                            ?.let { line ->
                                BiomeBand(abyssBiome(server), window.minY, line, ourGround?.rock?.landform)
                            },
                    ),
                ),
            ourGround?.rock ?: AgeRock.Vanillas(Holder.direct(vanillasRockFor(theirRockSettings.value(), composition, fill))),
            seaFill,
            // The Surface aspect's answer, not a constant: vanilla's tree paints grass over dirt above
            // water without consulting the biome, so there has to be a way to say "no skin" and a way to
            // lay something else. See [Surface.ruleFor].
            // Only where the rock is ours: a rule delegating to the biomes does so *through* the field
            // tree, and an Age wearing vanilla's rock has none to delegate through. `vanillasRockFor`
            // carries that Age's skin instead.
            ourGround?.let {
                val skin = Surface.ruleFor(composition.optionsFor(Aspect.SURFACE, 0), it.rock, recipe.template)
                // A landform that is its own roof closes it with bedrock, as vanilla closes the nether's.
                if (composition.roofedByItsRock) SurfacingStrategy.shutOverhead(skin) else skin
            }
                ?: SurfacingStrategy.SUPPRESSED,
            composition.carvers.map { it.configuredCarvers(server) },
            below,
            waterTablesOf(composition, seaFill, seed),
            // One answer for the whole dimension — vanilla places structures against the level, and what
            // it starts from is the template's: the overworld's sets in the nether was the same bug the
            // biomes had.
            Structures.structureSets(
                server,
                composition.optionsFor(Aspect.STRUCTURES, 0),
                recipe.template.standingStructures,
                if (abyssLineOf(seaFill, window) == null) emptyMap() else WRECKAGE_ON_AN_ABYSS_FLOOR,
            ),
            // **The climate the biomes are looked up by, and it comes from the same world they do.** This
            // was the overworld's for every Age, so a landform of ours over the infernal template chose
            // nether biomes with overworld noise — and over the dark void, where the End picks by distance
            // from the centre and reads that off `erosion`, it scattered the islands' biomes at random.
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
                Features.placedIn(server, composition.optionsFor(Aspect.FEATURES, 0), seed, rockBlocks),
                listOfNotNull(
                    Deposits.layer(Danger.of(server, recipe), rockBlocks),
                    Volcanoes.layer(composition),
                    Craters.layer(composition, seed),
                    EarlyGameRareMaterials.layer(
                        EarlyGameRareMaterials.grownIn(composition, seed, spending, prices),
                    ),
                ),
            ),
            // What lives here, narrowing what vanilla resolves per biome and per structure.
            Spawns.livingIn(composition.optionsFor(Aspect.SPAWNS, 0), Vocabulary.of(server).spawning),
            woundsPerChunk = wounds,
            woundsPerDay = worsening,
            collapseTears = collapse,
            writtenAt = recipe.writtenAt,
            // Read only where the rock is vanilla's; a landform of ours folded the same object in already.
            overlay = if (ourGround == null) overlay else Overlay.NONE,
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
    ): NoiseGeneratorSettings {
        // Only a skin the writer actually named: `Surface.ruleFor` would otherwise delegate to the biomes
        // *through the field tree*, and there is none here to delegate through. Silence means vanilla's own
        // rule, which is what a nether floor of netherrack is.
        val named = composition.optionsFor(Aspect.SURFACE, 0).materialsOf(Surface.MATERIAL)
        val skin = when {
            // **Patches over the rock, not instead of it** — see [SurfacingStrategy.asPatchesOver]. The
            // nether's tree and the End's each end in an unconditional arm that would repaint whatever
            // block was substituted below, and removing it changes nothing until one has been.
            named.isEmpty() -> SurfacingStrategy.asPatchesOver(theirs.surfaceRule())
            named.all { it.isAir } -> SurfacingStrategy.SUPPRESSED
            else -> SurfacingStrategy.laidOnVanilla(named)
        }
        // **The substance alone, read off the book rather than off the fill.** A `SeaFill` answers where a
        // sea of *ours* is poured, and `Terrain.VANILLA` declares no waterline at all — vanilla's own
        // router places its fluid, so there is nothing here for a fill to pour. That made the fill `NONE`
        // for every Age wearing this rock however the book was written, and reading the sea off it dropped
        // every `sea=` one of them ever named: lava asked for over the overworld came out water, and water
        // asked for over the nether came out lava (Jonah, 2026-08-25, walked).
        val sea = composition.seas.firstOrNull()?.substance() ?: theirs.defaultFluid()
        return NoiseGeneratorSettings(
            theirs.noiseSettings(),
            fill.representative.takeUnless { fill == TerrainFill.PLAIN } ?: theirs.defaultBlock(),
            sea,
            theirs.noiseRouter(),
            skin,
            theirs.spawnTarget(),
            theirs.seaLevel(),
            theirs.disableMobGeneration(),
            /* aquifersEnabled = */ true,
            /* oreVeinsEnabled = */ true,
            theirs.getRandomSource() == net.minecraft.world.level.levelgen.WorldgenRandom.Algorithm.LEGACY,
        )
    }

    /**
     * The water an Age's terrains carry themselves, divided on the terrain's own map — so a territory
     * whose shape has no water of its own contributes none, rather than the whole Age being wet wherever
     * one of them has a river.
     *
     * **Only a scarp reaches it.** A rift is already kept out by `SeaFill.dry`, and a wall *adds* rock,
     * which must not put a wall of water up alongside it.
     */
    private fun carriedWater(
        composition: AgeComposition,
        seam: Seam,
        ground: RegionMap,
        seed: Long,
        torn: Double,
    ): TerrainField? {
        val carried = composition.terrains.mapIndexed { member, terrain ->
            terrain.standingWater(saltFor(seed, member))
        }
        if (carried.all { it == null }) return null
        val divided = Regions.of(carried.map { it ?: Union(emptyList()) }, ground)
        // Exhaustive rather than a test for one form, so a new [Seam] breaks the build here instead of
        // silently taking the wrong branch.
        return when (seam) {
            Seam.SCARP -> faulted(divided, seam, ground, seed, torn)
            Seam.SHEARED, Seam.FUZZED, Seam.RIFT, Seam.WALL -> divided
        }
    }

    /**
     * [rock] with the terrain's own [seam] made visible along its boundaries — a cliff, a chasm, a wall, or
     * nothing, since the fuzzed form is a blend width and acted on the map instead (design §3.4).
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
        seam: Seam,
        ground: RegionMap,
        seed: Long,
        torn: Double,
    ): TerrainField = when (seam) {
        // `SHEARED` asks for no fault; `FUZZED` already happened, in the width `mapFor` took off the seam.
        Seam.SHEARED, Seam.FUZZED -> rock
        Seam.SCARP -> Fault.of(rock, ground, Fault.alternatingThrows(ground.members, Seam.scarpThrow(torn), seed))
        Seam.RIFT -> Rift.opened(rock, ground, Seam.riftFloor(torn), Seam.RIFT_RIM)
        Seam.WALL -> Ridge.raised(rock, ground, Seam.WALL_FOOTING, Seam.wallCrest(torn))
    }

    /**
     * The volume a rift took out, for the sea to be kept out of — and nothing for any other seam.
     *
     * Built from the same numbers [faulted] cuts with, so the dry space and the chasm are the same shape
     * by construction rather than by two constants agreeing.
     */
    private fun riftVolume(seam: Seam, ground: RegionMap, torn: Double): TerrainField? =
        if (seam != Seam.RIFT || ground.members <= 1) null
        else Rift(ground, Rift.DEFAULT_HALF_WIDTH, Seam.riftFloor(torn), Seam.RIFT_RIM)

    /**
     * [chasm] and every territory's own dry underground, as one volume the sea is kept out of.
     *
     * Divided on the terrain's map like the rock itself, so a territory that has halls keeps its own dry
     * and a neighbour that does not is unaffected — an Age is allowed to be wet next door to dry, so long
     * as the boundary is a wall of rock rather than of water.
     */
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
        ground: RegionMap,
    ): TerrainField? {
        if (grounds.none { it.wet != null }) return carried
        val perTerritory = Regions.of(grounds.map { it.wet ?: Union(emptyList()) }, ground)
        return if (carried == null) perTerritory else Union(listOf(carried, perTerritory))
    }

    private fun keptDry(
        chasm: TerrainField?,
        grounds: List<Terrain.Ground>,
        ground: RegionMap,
    ): TerrainField? {
        if (grounds.none { it.dry != null }) return chasm
        val perTerritory = Regions.of(grounds.map { it.dry ?: Union(emptyList()) }, ground)
        return if (chasm == null) perTerritory else Union(listOf(chasm, perTerritory))
    }

    /**
     * Where water stands in this Age's rock — one table per carving, each answering for its own territory
     * (design §3.4). A carving with no table of its own contributes the sea's, so the list lines up with
     * the territory map index for index.
     */
    private fun waterTablesOf(composition: AgeComposition, seaFill: SeaFill, seed: Long): List<WaterTable> =
        composition.carvers.map { carving ->
            carving.waterTable(seaFill, seed) ?: WaterTable.matching(seaFill, seaFill.level, seed)
        }

    /**
     * Where this Age's sea sits when its terrains disagree about it — or whether there is one at all.
     *
     * The shape covering the most ground wins, since it is its coastline most of the world has. The seed
     * decides only where shares tie (design §3.5).
     */
    private fun waterlineOf(composition: AgeComposition, seed: Long): Int? {
        val claimed = composition.terrains.map { it.waterline }
        if (claimed.size == 1) return claimed.first()
        val shares = composition.spreadOf(Aspect.TERRAIN).shares
        val widest = shares.max()
        val contenders = claimed.indices.filter { shares[it] == widest }
        return claimed[contenders[XoroshiroRandomSource(seed xor WATERLINE_SALT).nextInt(contenders.size)]]
    }

    // So which sea wins is decorrelated from everything else this seed decides.
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
        /** And bodies made of something else entirely — a caldera's lava. See [StandingFluid]. */
        val lakes: List<StandingFluid> = emptyList(),
    ) {
        /** The land, without whatever shuts it overhead — see [AgeRock.Ours.ground]. */
        val landform: TerrainField get() = rock.landform
    }

    /** [OurGround] for an Age with a landform of its own — the only path that builds a field. */
    private fun ourGround(
        composition: AgeComposition,
        landmass: Spread,
        ground: RegionMap,
        window: VerticalWindow,
        seed: Long,
        torn: Double,
    ): OurGround {
        val grounds = composition.terrains.mapIndexed { member, terrain ->
            terrain.ground(
                composition.underground,
                composition.optionsFor(Aspect.UNDERGROUND, 0),
                composition.optionsFor(Aspect.TERRAIN, member),
                window,
                saltFor(seed, member),
            )
        }
        // Weathering is not applied here at all: a landform that wants wind carries it inside its own
        // field, where the profile and the shape were designed together. There is no Age-wide pass.
        val weathered = Regions.of(grounds.map { it.shape }, ground)
        // The fault comes last, over the finished rock — see [Fault].
        val shape = faulted(weathered, landmass.seam, ground, seed, torn)
        val riftCut = riftVolume(landmass.seam, ground, torn)
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
        val volcanic = volcanicOverlay(composition, seed)
        val cones = volcanic.raises
        val raised = Union(listOfNotNull(shape, cones, lid)).takeIf { cones != null || lid != null } ?: shape
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
                hollows = openedBy(hollowedRock(grounds, ground), riftCut),
                // The land kept apart from the lid, since a ceiling is not ground however solid it is. A
                // cone is ground, so it stays in — what this separates is the roof, not everything added.
                ground = Union(listOfNotNull(shape, cones)).takeIf { lid != null },
            ),
            chasm = keptDry(riftCut, grounds, ground),
            standing = withLakes(carriedWater(composition, landmass.seam, ground, seed, torn), grounds, ground),
            // A caldera arrives flooded, and the shape is what floods it. Nothing at runtime can lay a
            // level lake — where fluid may stand is vanilla's fluid to know, and it only knows one block
            // at a time — where the field already knows the crater's floor, its walls and its rim.
            // **Named, because both are lava and a feature has to find its own.** See [StandingFluid.named].
            lakes = volcanic.pours,
        )
    }

    private val LAVA = Blocks.LAVA.defaultBlockState()

    private const val WATERLINE_SALT = 0x5EA_1E7EL

    /**
     * The few Ages that are a whole generator rather than an assembly of parts. Exhaustive, so a new
     * bespoke preset cannot quietly fall through to Spire's world — it fails to compile instead.
     */
    private fun bespoke(server: MinecraftServer, preset: AgePreset, seed: Long): ChunkGenerator = when (preset) {
        AgePreset.VANILLA -> VanillaDelegate.overworld(server)
        AgePreset.VANILLA_BARE -> VanillaDelegate.bareOverworld(server)
        AgePreset.SPIRE -> SpireChunkGenerator(plasmaBiome(server), seed)

        AgePreset.FIELD, AgePreset.PYRAMIDS, AgePreset.PYRINGS, AgePreset.PYRVARIED, AgePreset.HILLS,
        AgePreset.SHAPES, AgePreset.PILLARS, AgePreset.CAVERNS, AgePreset.ERODED, AgePreset.CANYON,
        AgePreset.CLIFFS, AgePreset.CANYONLANDS, AgePreset.SHATTERED, AgePreset.RIVERLANDS,
        AgePreset.ISLANDS, AgePreset.ISLE, AgePreset.ALPS, AgePreset.CRATERLANDS, AgePreset.INVERSE_CAVES,
        AgePreset.HALLS, AgePreset.FLATLANDS, AgePreset.SOLID, AgePreset.CHAMBERS,
        -> error("'${preset.key}' names a composition, so AgeRecipe.worldFor should never have sent it here")
    }

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

    private fun hollowedRock(grounds: List<Terrain.Ground>, ground: RegionMap): TerrainField? {
        if (grounds.none { it.hollows != null }) return null
        return Regions.of(grounds.map { it.hollows ?: Union(emptyList()) }, ground)
    }

    private fun plasmaBiome(server: MinecraftServer) = FixedBiomeSource(
        server.registryAccess().lookupOrThrow(Registries.BIOME)
            .getOrThrow(ResourceKey.create(Registries.BIOME, PLASMA_BIOME)),
    )

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
    internal fun volcanicOverlay(composition: AgeComposition, seed: Long): Overlay {
        val volcanoes = if (Volcanoes.askedFor(composition)) VolcanoField.over(seed) else null
        val chambers = if (MagmaChambers.askedFor(composition)) VolcanoField.chambers(seed) else null
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
    private fun abyssLineOf(seaFill: SeaFill, window: VerticalWindow): Int? =
        seaFill.surfaceY?.let(DeepWater::lineBelow)?.takeIf { it > window.minY }

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
        "minecraft:shipwrecks".asStructureSet() to FOUR_TIMES,
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
            .getOrThrow(ResourceKey.create(Registries.BIOME, GREAT_HALL_BIOME))

    /**
     * The seed a territory's shape is built from: the Age's own, mixed with which territory it is.
     *
     * Two things depend on this. A terrain preset carries fixed noise seeds, so without a salt every
     * `hills` Age would raise the same hills; and two territories of the same preset in one Age would be
     * identical, leaving nothing for a seam to divide.
     */
    private fun saltFor(seed: Long, member: Int): Long = seed * TERRITORY_SALT_STRIDE + member

    /** Odd and large, so consecutive members land far apart in the noise rather than adjacent. */
    private val TERRITORY_SALT_STRIDE = 0x9E37_79B9_7F4A_7C15uL.toLong()

    /**
     * The dimension type an Age wears — **whether the sky reaches it, and whether there is rock overhead**.
     *
     * It used to be the colour of the air, which is why the Spire had a type of its own; `Atmosphere` says
     * every one of those colours better, so the palette moved to a [co.voik.agesandtheart.sky.Look] and
     * what is left here is the two things only a pre-authored file can carry.
     *
     * The band of world is deliberately *not* here. All four types declare [VerticalWindow.DEFAULT], so a
     * sky cannot move an Age's floor — which is exactly what it used to do, to any landform reaching below
     * y=0 that drew the Spire's sky by chance.
     */
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
        val charged = EarlyGameRareMaterials.growsArcCrystal(
            composition,
            recipe.seed,
            Spending.of(server, recipe),
            Price.list(server),
        )
        return listOfNotNull(placing, DriftingOreSpawner().takeIf { charged })
    }

    fun dimensionType(recipe: AgeRecipe): Identifier = when (val world = recipe.world) {
        is AgeWorld.Composed -> typeFor(world.composition, recipe.template)
        is AgeWorld.Bespoke -> AGE_DIMENSION_TYPE
    }

    /**
     * **A world wearing another's rock wears its type too.**
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
    private fun typeFor(composition: AgeComposition, template: AgeTemplate): Identifier {
        val ours = Sky.dimensionType(composition)
        // Only where the rock is the template's: a landform of ours generates into our own vertical band,
        // and vanilla's nether is a hundred and twenty-eight blocks tall.
        if (Terrain.VANILLA !in composition.terrains) return ours
        val world = template.world()
        val theirs = Sky.dimensionType(world)
        return if (ours == theirs) template.dimensionType.identifier() else ours
    }

    /**
     * The sky an Age has, as data the client can be told. A pure function of the recipe.
     *
     * The Spire is reached here as well as through [Sky.SPIRE], because the handcrafted Age is bespoke and
     * never passes through a composition. Both answer with the same [SpireSky.SPEC].
     */
    fun skySpec(recipe: AgeRecipe): SkySpec = when (val world = recipe.world) {
        is AgeWorld.Composed -> world.composition.sky.specFor(world.composition, recipe.seed)
        is AgeWorld.Bespoke -> if (world.preset == AgePreset.SPIRE) SpireSky.SPEC else SkySpec.VANILLA
    }

    /**
     * The look an Age's sky preset paints under whatever its sentence asked for. Reached the same two ways
     * [skySpec] is, and for the same reason: the handcrafted Age never passes through a composition.
     */
    fun presetLook(recipe: AgeRecipe): Look = when (val world = recipe.world) {
        is AgeWorld.Composed -> world.composition.sky.look()
        is AgeWorld.Bespoke -> if (world.preset == AgePreset.SPIRE) SpireSky.LOOK else Look.NOTHING
    }
}
