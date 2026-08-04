package co.voik.agesandtheart.age

import co.voik.agesandtheart.age.aspect.Biomes
import co.voik.agesandtheart.age.aspect.Sea
import co.voik.agesandtheart.worldgen.field.SeaFill
import co.voik.agesandtheart.worldgen.field.WaterTable
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Options
import co.voik.agesandtheart.age.aspect.Terrain
import co.voik.agesandtheart.sky.SkySpec
import co.voik.agesandtheart.sky.SpireSky
import co.voik.agesandtheart.worldgen.biome.AgeBiomeSource
import co.voik.agesandtheart.worldgen.biome.Grounding
import co.voik.agesandtheart.worldgen.biome.RegionalClimate
import co.voik.agesandtheart.worldgen.biome.Roofed
import co.voik.agesandtheart.worldgen.field.Fault
import co.voik.agesandtheart.worldgen.field.SurfacingStrategy
import co.voik.agesandtheart.worldgen.field.RegionMap
import co.voik.agesandtheart.worldgen.field.Regions
import co.voik.agesandtheart.worldgen.field.Ridge
import co.voik.agesandtheart.worldgen.field.Rift
import co.voik.agesandtheart.worldgen.field.TerrainField
import co.voik.agesandtheart.worldgen.field.Union
import co.voik.agesandtheart.worldgen.field.Weathered
import co.voik.agesandtheart.worldgen.field.TerrainFill
import co.voik.agesandtheart.location
import co.voik.agesandtheart.worldgen.AgeChunkGenerator
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
import co.voik.agesandtheart.age.aspect.Structures
import co.voik.agesandtheart.age.aspect.Surface

/**
 * Turns an [AgeRecipe] into the generator that builds its world — a pure function of the recipe (plus the
 * server, for registries), because an Age must rebuild identically on every open.
 */
object AgeGeneration {
    /** The dimension type every generated Age wears. */
    val AGE_DIMENSION_TYPE: Identifier = "age".location()

    /** The Spire's own dimension type, which differs only in the storm-grey it paints the air. */
    val AGE_SPIRE_DIMENSION_TYPE: Identifier = "age_spire".location()

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
     * The field, the surface rule and the biome source all read [AgeCharacter.mapFor], so their seams agree
     * to the column; three maps drawn independently would read as three faults rather than one edge.
     */
    private fun assemble(server: MinecraftServer, composition: AgeComposition, recipe: AgeRecipe): ChunkGenerator {
        val seed = recipe.seed
        // Per territory, not per aspect — see [AspectOptions].
        fun terrainOptions(member: Int) = composition.optionsFor(Aspect.TERRAIN, member)

        // A pinned seam replaces the drawn one in the character itself, so `mapFor` stays a question about
        // the character alone. The pin has to reach the map, not only the shape, because the fuzzed form is
        // a blend width where the other two are displacements (see [Seam]).
        val character = recipe.character.copy(
            seam = composition.terrains.first().seamIn(terrainOptions(0), recipe.character.seam),
        )

        // One band for every Age, and the same one every dimension type admits — see [VerticalWindow].
        val window = VerticalWindow.DEFAULT

        val ground = character.mapFor(Aspect.TERRAIN, composition.sharesOf(Aspect.TERRAIN), seed)
        val grounds = composition.terrains.mapIndexed { member, terrain ->
            terrain.ground(terrainOptions(member), window, saltFor(seed, member))
        }
        // Weathering is not applied here at all: a landform that wants wind carries it inside its own
        // field, where the profile and the shape were designed together. There is no Age-wide pass.
        val weathered = Regions.of(grounds.map { it.shape }, ground)

        // The fault comes last, over the finished rock — see [Fault].
        val shape = faulted(weathered, character.seam, ground, seed)
        // Everywhere the sea is kept out of: the chasm a rift opened, and any underground that answers
        // "never wet" rather than to a water table — see [Terrain.Ground].
        val chasm = keptDry(riftVolume(character.seam, ground), grounds, ground)
        // And the rock the underground was taken out of — **handed to the generator rather than to the
        // sea**. A flat waterline fills any empty space beneath it, so a shape-cut cave or hall comes out
        // flooded to the roof; making it simply *dry* instead would only trade one uniform answer for the
        // other. What that space wants is the same three-way `WaterTable` a carved cave already meets.
        val hollows = hollowedRock(grounds, ground)

        val standing = carriedWater(composition, character, ground, seed)
        val flow = character.mapFor(Aspect.SEA, composition.sharesOf(Aspect.SEA), seed)
        val seaFill = Sea.pour(
            composition.seas,
            waterlineOf(composition, seed),
            // The first territory's: `depth` shifts the waterline, which is one number for the whole Age.
            // The sea's substance divides; the level does not.
            composition.optionsFor(Aspect.SEA, 0),
            flow,
            seed,
        ).copy(dry = chasm, wet = standing)

        // What the rock *is*, on the terrain's own map, laid by the fill rather than painted by a rule — which
        // is what lets vanilla's surface tree keep its skin over our fill (see [TerrainFill]).
        val fill = TerrainFill(
            composition.terrains.mapIndexed { member, terrain ->
                terrain.fillBlocks(terrainOptions(member)).ifEmpty { listOf(TerrainFill.STONE) }
            },
            ground,
            // The first territory's, like `Sea.DEPTH`: the mingling noise is one field over the whole Age.
            composition.terrains.first().mingling(terrainOptions(0), seed),
        )
        val below = character.mapFor(Aspect.CARVERS, composition.sharesOf(Aspect.CARVERS), seed)
        // Climate divides on a map of its own: which climate a column has is a different question from what
        // paints it. One climate needs no map and gets `whole` (see [RegionalClimate]).
        // Read straight off the composition: a climate's answer *is* its spans, so there is nothing to
        // derive from options any more (see [AgeComposition.climates]).
        val climate = RegionalClimate(
            composition.climates,
            character.mapFor(Aspect.CLIMATE, composition.sharesOf(Aspect.CLIMATE), seed),
        )
        // One biome source for the whole Age, and no region map: one climate table spans the world however
        // many terrains carve it up (design §3.1).
        val biomeOptions = composition.optionsFor(Aspect.BIOMES, 0)
        return AgeChunkGenerator(
            AgeBiomeSource.vanillaOverworld(server, seed)
                .told(climate, Biomes.preferencesIn(biomeOptions), Biomes.keepsOnlyNamed(biomeOptions))
                .groundedIn(shape)
                // Only where the Age asked for it: biomes disagreeing with the shape is the default, and
                // a lever rather than a defect. See [Grounding] and [Biomes.FOOTING].
                .suitedTo(
                    if (!Biomes.groundsBiomes(biomeOptions)) null
                    // A shore is where the Age's one sea meets whichever territory reaches it, so a single
                    // island territory is enough to make the coast sand — the level it stands at is already
                    // Age-wide.
                    else Grounding(
                        shape,
                        seaFill.level,
                        standing,
                        Grounding.Declared.of(composition.terrains.map { it.grounding() }),
                    ),
                )
                // Age-wide like the shore and the treeline: the band is a pair of heights, and an Age has
                // one set of those however many territories divide it.
                .roofedBy(
                    composition.terrains
                        .withIndex()
                        .firstNotNullOfOrNull { (member, terrain) ->
                            terrain.undergroundBand(terrainOptions(member), window)
                        }
                        ?.let { band -> Roofed(greatHallBiome(server), band.first, band.last) },
                ),
            shape,
            seaFill,
            // The Surface aspect's answer, not a constant: vanilla's tree paints grass over dirt above
            // water without consulting the biome, so there has to be a way to say "no skin" and a way to
            // lay something else. See [Surface.ruleFor].
            Surface.ruleFor(composition.optionsFor(Aspect.SURFACE, 0), shape),
            composition.carvers.map { it.configuredCarvers(server) },
            below,
            waterTablesOf(composition, seaFill, seed),
            // One answer for the whole dimension — vanilla places structures against the level.
            Structures.structureSets(server, composition.optionsFor(Aspect.STRUCTURES, 0)),
            server.registryAccess().lookupOrThrow(Registries.NOISE_SETTINGS)
                .getOrThrow(NoiseGeneratorSettings.OVERWORLD),
            fill,
            window,
            hollows,
        )
    }

    /**
     * [rock] with this Age's [seam] made visible along the terrain's own boundaries — a cliff, a chasm, or
     * nothing, since the fuzzed form is a blend width and acts on the map instead (design §3.4).
     *
     * Only the terrain's seams: a displacement needs rock to displace, so an Age divided in its sea or its
     * climate alone has no scarp to throw however its character drew.
     */
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
        character: AgeCharacter,
        ground: RegionMap,
        seed: Long,
    ): TerrainField? {
        val carried = composition.terrains.mapIndexed { member, terrain ->
            terrain.standingWater(saltFor(seed, member))
        }
        if (carried.all { it == null }) return null
        val divided = Regions.of(carried.map { it ?: Union(emptyList()) }, ground)
        // Exhaustive rather than a test for one form, so a new [Seam] breaks the build here instead of
        // silently taking the wrong branch.
        return when (character.seam) {
            Seam.SCARP -> faulted(divided, character.seam, ground, seed)
            Seam.SHEARED, Seam.FUZZED, Seam.RIFT, Seam.WALL -> divided
        }
    }

    private fun faulted(rock: TerrainField, seam: Seam, ground: RegionMap, seed: Long): TerrainField = when (seam) {
        // `SHEARED` asks for no fault; `FUZZED` already happened, in the width `mapFor` took off the seam.
        Seam.SHEARED, Seam.FUZZED -> rock
        Seam.SCARP -> Fault.of(rock, ground, Fault.alternatingThrows(ground.members, Seam.SCARP_THROW, seed))
        Seam.RIFT -> Rift.opened(rock, ground, Seam.RIFT_FLOOR, Seam.RIFT_RIM)
        Seam.WALL -> Ridge.raised(rock, ground, Seam.WALL_FOOTING, Seam.WALL_CREST)
    }

    /**
     * The volume a rift took out, for the sea to be kept out of — and nothing for any other seam.
     *
     * Built from the same numbers [faulted] cuts with, so the dry space and the chasm are the same shape
     * by construction rather than by two constants agreeing.
     */
    private fun riftVolume(seam: Seam, ground: RegionMap): TerrainField? =
        if (seam != Seam.RIFT || ground.members <= 1) null
        else Rift(ground, Rift.DEFAULT_HALF_WIDTH, Seam.RIFT_FLOOR, Seam.RIFT_RIM)

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
        val shares = composition.sharesOf(Aspect.TERRAIN)
        val widest = shares.max()
        val contenders = claimed.indices.filter { shares[it] == widest }
        return claimed[contenders[XoroshiroRandomSource(seed xor WATERLINE_SALT).nextInt(contenders.size)]]
    }

    // So which sea wins is decorrelated from everything else this seed decides.
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
        AgePreset.ISLANDS, AgePreset.ALPS, AgePreset.CRATERLANDS, AgePreset.INVERSE_CAVES, AgePreset.HALLS,
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
    private fun hollowedRock(grounds: List<Terrain.Ground>, ground: RegionMap): TerrainField? {
        if (grounds.none { it.hollows != null }) return null
        return Regions.of(grounds.map { it.hollows ?: Union(emptyList()) }, ground)
    }

    private fun plasmaBiome(server: MinecraftServer) = FixedBiomeSource(
        server.registryAccess().lookupOrThrow(Registries.BIOME)
            .getOrThrow(ResourceKey.create(Registries.BIOME, PLASMA_BIOME)),
    )

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
     * The dimension type an Age wears — **the colour of its air, and nothing else**. What is drawn overhead
     * is the Age's [co.voik.agesandtheart.sky.SkySpec], read per frame by the renderer, but a [SkySpec]
     * carries no sky, fog or cloud colour and those are still dimension-type data.
     *
     * The band of world is deliberately *not* here. Both types declare [VerticalWindow.DEFAULT], so a sky
     * cannot move an Age's floor — which is exactly what it used to do, to any landform reaching below y=0
     * that drew the Spire's sky by chance.
     */
    fun dimensionType(recipe: AgeRecipe): Identifier = when (val world = recipe.world) {
        is AgeWorld.Composed -> world.composition.sky.dimensionType()
        is AgeWorld.Bespoke -> if (world.preset == AgePreset.SPIRE) AGE_SPIRE_DIMENSION_TYPE else AGE_DIMENSION_TYPE
    }

    /**
     * The sky an Age has, as data the client can be told. A pure function of the recipe.
     *
     * The Spire is reached here as well as through [Sky.SPIRE], because the handcrafted Age is bespoke and
     * never passes through a composition. Both answer with the same [SpireSky.SPEC].
     */
    fun skySpec(recipe: AgeRecipe): SkySpec = when (val world = recipe.world) {
        is AgeWorld.Composed -> world.composition.sky.specFor(
            world.composition.optionsFor(Aspect.SKY, 0),
            recipe.seed,
        )
        is AgeWorld.Bespoke -> if (world.preset == AgePreset.SPIRE) SpireSky.SPEC else SkySpec.VANILLA
    }
}
