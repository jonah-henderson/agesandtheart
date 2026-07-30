package co.voik.agesandtheart.age

import co.voik.agesandtheart.age.aspect.Sea
import co.voik.agesandtheart.worldgen.field.SeaFill
import co.voik.agesandtheart.worldgen.field.WaterTable
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Terrain
import co.voik.agesandtheart.sky.SkySpec
import co.voik.agesandtheart.worldgen.biome.AgeBiomeSource
import co.voik.agesandtheart.worldgen.biome.RegionalClimate
import co.voik.agesandtheart.worldgen.field.Fault
import co.voik.agesandtheart.worldgen.field.Palette
import co.voik.agesandtheart.worldgen.field.RegionMap
import co.voik.agesandtheart.worldgen.field.Regions
import co.voik.agesandtheart.worldgen.field.Rift
import co.voik.agesandtheart.worldgen.field.TerrainField
import co.voik.agesandtheart.worldgen.field.Weathered
import co.voik.agesandtheart.worldgen.field.Substance
import co.voik.agesandtheart.location
import co.voik.agesandtheart.worldgen.AgeChunkGenerator
import co.voik.agesandtheart.worldgen.SpireChunkGenerator
import co.voik.agesandtheart.worldgen.VerticalWindow
import co.voik.agesandtheart.worldgen.VanillaDelegate
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.biome.FixedBiomeSource
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings
import net.minecraft.world.level.levelgen.XoroshiroRandomSource

/**
 * Turns an [AgeRecipe] into the generator that builds its world — a pure function of the recipe (plus the
 * server, for registries), because an Age must rebuild identically on every open.
 */
object AgeGeneration {
    /** The dimension type a generated Age wears when it has a sky vanilla cannot draw. */
    val AGE_DIMENSION_TYPE: ResourceLocation = "age".location()

    /** The Spire's own dimension type, so its bespoke sky cannot leak into generated Ages. */
    val AGE_SPIRE_DIMENSION_TYPE: ResourceLocation = "age_spire".location()

    /**
     * The **effects** ids — a different namespace from the dimension-type ids above, and not to be
     * confused with them.
     *
     * A dimension type is named by its file; its `effects` field is free-form, unvalidated, and what
     * `DimensionSpecialEffects` is keyed on. They happen to match for [AGE_DIMENSION_TYPE] and do not for
     * the Spire, which is why they are separate constants.
     */
    val AGE_EFFECTS: ResourceLocation = "age".location()

    /** See [AGE_EFFECTS]. Matches `age_spire.json`'s `effects` field. */
    val SPIRE_EFFECTS: ResourceLocation = "spire".location()

    /** Identical layout, but vanilla (`minecraft:overworld`) effects, so it gets the normal sky. */
    val AGE_PLAIN_DIMENSION_TYPE: ResourceLocation = "age_plain".location()

    /** The custom biome (green plasma water), registered as a datapack biome at load. */
    val PLASMA_BIOME: ResourceLocation = "plasma".location()

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

        // Derived from the dimension type, never chosen beside it: the band this generator fills and the
        // band the level admits must not disagree. See [windowFor].
        val window = windowFor(dimensionType(recipe))

        val ground = character.mapFor(Aspect.TERRAIN, composition.sharesOf(Aspect.TERRAIN), seed)
        val unweathered = Regions.of(
            composition.terrains.mapIndexed { member, terrain -> terrain.field(terrainOptions(member), window) },
            ground,
        )
        // Erosion is part of the shape rather than a carving pass, so `getBaseHeight` answers from the eroded
        // rock and the surface system paints what the wind left. See [Weathered].
        //
        // Age-wide when any seated carving weathers, and raised by the *first* territory's lift: erosion's
        // keel and band are absolute heights and there is one profile for the Age.
        val weathers = composition.carvers.any { it.weathering() != null }
        val lift = composition.terrains.first().lift(terrainOptions(0), window)
        val weathered = if (weathers) Weathered.spire(unweathered, lift) else unweathered

        // The fault comes last, over the finished rock. A territory lifted before the wind reached it would
        // be weathered by a profile aimed at where it used to be — see [Fault].
        val shape = faulted(weathered, character.seam, ground, seed)

        val flow = character.mapFor(Aspect.SEA, composition.sharesOf(Aspect.SEA), seed)
        val seaFill = Sea.pour(
            composition.seas,
            waterlineOf(composition, seed),
            // The first territory's: `depth` shifts the waterline, which is one number for the whole Age.
            // The substance divides; the level does not.
            composition.optionsFor(Aspect.SEA, 0),
            flow,
        )

        // What the rock *is*, on the terrain's own map, laid by the fill rather than painted by a rule — which
        // is what lets vanilla's surface tree keep its skin over our substance (see [Substance]).
        val substance = Substance(
            composition.terrains.mapIndexed { member, terrain ->
                terrain.substance(terrainOptions(member)).ifEmpty { listOf(Substance.STONE) }
            },
            ground,
            // The first territory's, like `Sea.DEPTH`: the mingling noise is one field over the whole Age.
            composition.terrains.first().mingling(terrainOptions(0)),
        )
        val below = character.mapFor(Aspect.CARVERS, composition.sharesOf(Aspect.CARVERS), seed)
        // Climate divides on a map of its own: which climate a column has is a different question from what
        // paints it. One climate needs no map and gets `whole` (see [RegionalClimate]).
        val climate = RegionalClimate(
            composition.climates.mapIndexed { member, weather ->
                weather.biasIn(composition.optionsFor(Aspect.CLIMATE, member))
            },
            character.mapFor(Aspect.CLIMATE, composition.sharesOf(Aspect.CLIMATE), seed),
        )
        // One biome source for the whole Age, and no region map: one climate table spans the world however
        // many terrains carve it up (design §3.1).
        val biomeOptions = composition.optionsFor(Aspect.BIOMES, 0)
        return AgeChunkGenerator(
            AgeBiomeSource.vanillaOverworld(server, seed)
                .told(climate, composition.biomes.preferencesIn(biomeOptions), composition.biomes.keepsOnlyNamed(biomeOptions))
                .groundedIn(shape),
            shape,
            seaFill,
            // The Biomes aspect's choice, not a constant: vanilla's tree paints grass over dirt above water
            // without consulting the biome, so there has to be a way to say "no skin". See [Biomes.paletteIn].
            composition.biomes.paletteIn(biomeOptions),
            composition.carvers.map { it.configuredCarvers(server) },
            below,
            waterTablesOf(composition, seaFill, seed),
            // One answer for the whole dimension — vanilla places structures against the level.
            composition.structures.structureSets(server, composition.optionsFor(Aspect.STRUCTURES, 0)),
            server.registryAccess().lookupOrThrow(Registries.NOISE_SETTINGS)
                .getOrThrow(NoiseGeneratorSettings.OVERWORLD),
            substance,
            window,
        )
    }

    /**
     * [rock] with this Age's [seam] made visible along the terrain's own boundaries — a cliff, a chasm, or
     * nothing, since the fuzzed form is a blend width and acts on the map instead (design §3.4).
     *
     * Only the terrain's seams: a displacement needs rock to displace, so an Age divided in its sea or its
     * climate alone has no scarp to throw however its character drew.
     */
    private fun faulted(rock: TerrainField, seam: Seam, ground: RegionMap, seed: Long): TerrainField = when (seam) {
        // `SHEARED` asks for no fault; `FUZZED` already happened, in the width `mapFor` took off the seam.
        Seam.SHEARED, Seam.FUZZED -> rock
        Seam.SCARP -> Fault.of(rock, ground, Fault.alternatingThrows(ground.members, Terrain.SCARP_THROW, seed))
        Seam.RIFT -> Rift.opened(rock, ground, Terrain.RIFT_FLOOR)
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
        val widest = shares.maxOf { it.weight }
        val contenders = claimed.indices.filter { shares[it].weight == widest }
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
        AgePreset.SHAPES, AgePreset.PILLARS, AgePreset.CAVERNS, AgePreset.ERODED,
        -> error("'${preset.key}' names a composition, so AgeRecipe.worldFor should never have sent it here")
    }

    private fun plasmaBiome(server: MinecraftServer) = FixedBiomeSource(
        server.registryAccess().lookupOrThrow(Registries.BIOME)
            .getOrThrow(ResourceKey.create(Registries.BIOME, PLASMA_BIOME)),
    )

    /**
     * The dimension type — and so whether the client attaches our sky renderer — an Age wears. A composed
     * Age decides from what its sky turned out to be, not from its preset. See [Sky.dimensionType].
     */
    fun dimensionType(recipe: AgeRecipe): ResourceLocation = when (val world = recipe.world) {
        is AgeWorld.Composed -> world.composition.sky.dimensionType(skySpec(recipe))
        is AgeWorld.Bespoke -> if (world.preset == AgePreset.SPIRE) AGE_SPIRE_DIMENSION_TYPE else AGE_PLAIN_DIMENSION_TYPE
    }

    /**
     * The band of world an Age generates into, read off the dimension type it will wear.
     *
     * Derived, never chosen: the generator fills `minY..<topY` while the level admits blocks by its
     * dimension type's JSON, and nothing in vanilla checks the two agree — a mismatch is silently dropped
     * blocks. Adding a band means adding both halves together.
     */
    fun windowFor(dimensionType: ResourceLocation): VerticalWindow =
        if (dimensionType == AGE_SPIRE_DIMENSION_TYPE) VerticalWindow.LIFTED else VerticalWindow.DEFAULT

    /** The sky an Age has, as data the client can be told. A pure function of the recipe. */
    fun skySpec(recipe: AgeRecipe): SkySpec = when (val world = recipe.world) {
        is AgeWorld.Composed -> world.composition.sky.specFor(
            world.composition.optionsFor(Aspect.SKY, 0),
            recipe.seed,
        )
        is AgeWorld.Bespoke -> SkySpec.VANILLA
    }
}
