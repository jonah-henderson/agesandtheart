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
 * Turns an [AgeRecipe] into the generator that builds its world.
 *
 * This is the one place a recipe becomes machinery, and it is deliberately a *pure function of the
 * recipe* (plus the server, for the registries the presets need): an Age is replayable data, so the
 * same recipe must give the same world on every open, restart-replay included.
 *
 * Note how little is left here now that presets are typed by aspect. Assembling a composed Age has no
 * decisions in it at all, because every decision belongs to a aspect preset that owns it. That is the
 * point of §3.1 — combinations are sensible by construction rather than by being written out one at a
 * time, and this function never has to know which ones exist.
 */
object AgeGeneration {
    /**
     * The dimension type a **generated** Age wears when it has a sky vanilla cannot draw. Its `effects` id is
     * `agesandtheart:age`, the marker the client watches to attach the general sky renderer — suns, moons and
     * stars from the Age's own [co.voik.agesandtheart.sky.SkySpec], with no assumptions about anything else.
     */
    val AGE_DIMENSION_TYPE: ResourceLocation = "age".location()

    /**
     * The **Spire's own** dimension type, `effects` id `agesandtheart:spire`.
     *
     * Split off from [AGE_DIMENSION_TYPE] on 2026-07-29 (Jonah, after walking it): the original custom sky was
     * built for the handcrafted Spire and its concepts — *two* cloud decks, stars that fade in only above the
     * upper one — *"should not be part of the assumption for anything else"*. Sharing one marker meant the
     * general renderer inherited them.
     *
     * So Spire keeps its bespoke sky under a marker of its own, and generated Ages get the general one. The
     * cost is accepted rather than regretted: a generated Age cannot express Spire's intricacies, and that is
     * *"part of the excitement of an easter egg"*.
     */
    val AGE_SPIRE_DIMENSION_TYPE: ResourceLocation = "age_spire".location()

    /**
     * The **effects** ids, which are a different namespace of meaning from the dimension-type ids above and must
     * not be confused with them again.
     *
     * A dimension type is named by its file; its `effects` field is a free-form `ResourceLocation` that vanilla
     * never validates, and it is what `DimensionSpecialEffects` is keyed on. The two happen to be the same string
     * for [AGE_DIMENSION_TYPE] — `age.json` sets `effects` to `agesandtheart:age` — and that coincidence let one
     * constant do both jobs for months.
     *
     * **It broke the moment they differed.** `age_spire.json` declares `effects: agesandtheart:spire`, and the
     * client was registering and matching on `agesandtheart:age_spire`, so neither the Spire's atmosphere nor its
     * sky and cloud renderers attached at all: it came out with the wrong sky and no cloud decks. Named
     * separately now so the mismatch cannot recur silently.
     */
    val AGE_EFFECTS: ResourceLocation = "age".location()

    /** See [AGE_EFFECTS]. Matches `age_spire.json`'s `effects` field. */
    val SPIRE_EFFECTS: ResourceLocation = "spire".location()

    /**
     * The plain dimension type — identical layout, but vanilla (`minecraft:overworld`) effects, so it
     * gets the normal sky.
     */
    val AGE_PLAIN_DIMENSION_TYPE: ResourceLocation = "age_plain".location()

    /** The custom biome (green plasma water), registered as a datapack biome at load. */
    val PLASMA_BIOME: ResourceLocation = "plasma".location()

    fun chunkGenerator(server: MinecraftServer, recipe: AgeRecipe): ChunkGenerator = when (val world = recipe.world) {
        is AgeWorld.Composed -> assemble(server, world.composition, recipe)
        is AgeWorld.Bespoke -> bespoke(server, world.preset, recipe.seed)
    }

    /**
     * One preset per aspect, each answering for its own part of the world — and where a aspect holds
     * several, the territories they divide it into.
     *
     * Note that three separate things consult a territory map here: the field lays the rock, the surface
     * rule paints it, and the biome source decides what the place *is*. They read [AgeCharacter.mapFor],
     * so the seams agree to the column — three maps drawn independently would put one boundary in three
     * nearly-identical places and read as three faults rather than one edge.
     */
    private fun assemble(server: MinecraftServer, composition: AgeComposition, recipe: AgeRecipe): ChunkGenerator {
        val seed = recipe.seed
        // Per territory, not per aspect: "copper spires and andesite hills" names one parameter twice, and a
        // aspect-wide answer has nowhere to put the second (see [AspectOptions]).
        fun terrainOptions(member: Int) = composition.optionsFor(Aspect.TERRAIN, member)

        // **A seam a recipe pinned replaces the one the Age drew, in the character itself.** Substituting it
        // here rather than threading a second seam through everything below is what keeps `mapFor` a question
        // about the character alone — and the pin has to reach the *map*, not only the shape, because the
        // fuzzed form is a blend width where the other two are displacements (see [Seam]).
        val character = recipe.character.copy(
            seam = composition.terrains.first().seamIn(terrainOptions(0), recipe.character.seam),
        )

        // Derived from the dimension type rather than chosen alongside it, so the band this generator fills and
        // the band the level admits cannot disagree — see [VerticalWindow] and [windowFor].
        val window = windowFor(dimensionType(recipe))

        val ground = character.mapFor(Aspect.TERRAIN, composition.sharesOf(Aspect.TERRAIN), seed)
        val unweathered = Regions.of(
            composition.terrains.mapIndexed { member, terrain -> terrain.field(terrainOptions(member), window) },
            ground,
        )
        // **Erosion is part of the shape, not a carving pass** (2026-07-29). It used to be a `RuleCarver` at
        // `ChunkStatus.CARVERS`, which was the wrong slot four ways over — see `Weathered`. Subtracting it here
        // means `getBaseHeight` answers from the *eroded* rock, so structures and arrival footing stop being
        // placed against stone that erosion later removes; the surface system paints what the wind left, since
        // `SURFACE` follows `NOISE`; and there is no `replaceable` tag to keep in step with what a writer can put
        // in the ground, which is what silently stopped the Spire eroding at all.
        //
        // Age-wide when *any* seated carving weathers, which matches how the rest of carving already unions
        // (design §3.4). A weathering per territory would want a `Regions` of its own and nothing asks for one.
        // The node **wraps** the shape rather than describing a cut to subtract, because it has to see the rock
        // to spare an island's middle — see `Weathered`.
        //
        // Raised by the **first** territory's lift, for the same reason the substance takes the first's mingling:
        // erosion's keel and band are absolute heights and there is one profile for the Age, so a shape that
        // floated higher in one territory than the next could not be weathered correctly in both.
        val weathers = composition.carvers.any { it.weathering() != null }
        val lift = composition.terrains.first().lift(terrainOptions(0), window)
        val weathered = if (weathers) Weathered.spire(unweathered, lift) else unweathered

        // **The fault comes last, over the finished rock, and the ordering is the decision.** A scarp
        // displaces rock that has already been shaped and worn, which is both what a real fault does and the
        // only place it can go: erosion's keel and band are absolute heights, so a territory lifted before
        // the wind reached it would be weathered by a profile aimed at where it used to be. That is why this
        // is a node and not a `Raised` inside each member — see [Fault].
        val shape = faulted(weathered, character.seam, ground, seed)

        val flow = character.mapFor(Aspect.SEA, composition.sharesOf(Aspect.SEA), seed)
        val seaFill = Sea.pour(
            composition.seas,
            waterlineOf(composition, seed),
            // The first territory's, deliberately: `depth` shifts the **waterline**, which is one number for
            // the whole Age, so a sea cannot be deep in one territory and shallow in the next however the
            // sentence is aimed. The substance divides; the level does not.
            composition.optionsFor(Aspect.SEA, 0),
            flow,
        )

        // What the rock *is*, on the **terrain's** own map — copper follows the spires that are made of it, not
        // whichever dressing happens to cover them. Laid by the fill rather than painted by a rule since step 4,
        // which is what lets vanilla's surface tree keep its skin over our substance (see [Substance]).
        val substance = Substance(
            composition.terrains.mapIndexed { member, terrain ->
                terrain.substance(terrainOptions(member)).ifEmpty { listOf(Substance.STONE) }
            },
            ground,
            // The first territory's, for the same reason `Sea.DEPTH` takes the first: the mingling noise is one
            // field over the whole Age, so it cannot be fine in one territory and blotchy in the next. What
            // divides is *which* blocks; how finely they speckle does not.
            composition.terrains.first().mingling(terrainOptions(0)),
        )
        val below = character.mapFor(Aspect.CARVERS, composition.sharesOf(Aspect.CARVERS), seed)
        // Climate divides on a map of its own, independent of the dressing's: *which climate* a column has is a
        // different question from which dressing paints it, and a writer who said "hot and cold" was talking
        // about the climate. One climate needs no map and gets `whole` (see [RegionalClimate]).
        val climate = RegionalClimate(
            composition.climates.mapIndexed { member, weather ->
                weather.biasIn(composition.optionsFor(Aspect.CLIMATE, member))
            },
            character.mapFor(Aspect.CLIMATE, composition.sharesOf(Aspect.CLIMATE), seed),
        )
        // **One biome source for the whole Age, and no region map at all.** §3.1 predicted this: a
        // `RegionBiomeSource` existed only because the dressing divided, and one climate table spans the world
        // however many terrains carve it up. With the dressing deleted the prediction came true, and the class
        // was finally deleted in step 7 — step 6 stopped using it but left it registered, and this comment
        // claimed it was gone for a day before anyone noticed it was not.
        val biomeOptions = composition.optionsFor(Aspect.BIOMES, 0)
        return AgeChunkGenerator(
            AgeBiomeSource.vanillaOverworld(server, seed)
                .told(climate, composition.biomes.preferencesIn(biomeOptions), composition.biomes.keepsOnlyNamed(biomeOptions))
                .groundedIn(shape),
            shape,
            seaFill,
            // **The Biomes aspect's choice, not a constant.** This was `Palette.VANILLA_OVERWORLD` outright, on
            // the reasoning that the barren dressings with palettes of their own were deleted and the rock
            // travels through [Substance] now. True, and it still left no way to say "no skin" — and vanilla's
            // tree paints grass over dirt above water *without* consulting the biome, so even an Age pinned to a
            // featureless custom biome came out grassy. See [Biomes.paletteIn].
            composition.biomes.paletteIn(biomeOptions),
            composition.carvers.map { it.configuredCarvers(server) },
            below,
            waterTablesOf(composition, seaFill, seed),
            // One answer for the whole dimension, which is what an aspect that never divides means: vanilla
            // places structures against the level, and its own biome predicates already keep a village out
            // of ground that has no villages in it.
            composition.structures.structureSets(server, composition.optionsFor(Aspect.STRUCTURES, 0)),
            // A composed Age always has a climate: even a dressing flattened to one biome can have biomes
            // named into it, and the table is asked at a climate position either way.
            server.registryAccess().lookupOrThrow(Registries.NOISE_SETTINGS)
                .getOrThrow(NoiseGeneratorSettings.OVERWORLD),
            substance,
            window,
        )
    }

    /**
     * [rock] with this Age's [seam] made visible along the terrain's own boundaries — a cliff, a chasm, or
     * nothing, since the third form is a blend width and acts on the map instead (design §3.4).
     *
     * **Only the terrain's seams**, deliberately, and the asymmetry is worth knowing: a displacement needs
     * rock to displace, so an Age divided in its *sea* or its *climate* alone has no scarp to throw however
     * its character drew — while [Seam.FUZZED], being a width, frays every aspect's boundary as it always
     * has. The design's answer for a climate-driven fault is a second [Fault] reading the climate's map, which
     * composes with this one and needs no new mechanism; it waits on Phase 6 step 0 along with magnitude.
     *
     * Exhaustive over [Seam] rather than `else`-terminated, so a fourth form cannot be added without this
     * function being told what it looks like.
     */
    private fun faulted(rock: TerrainField, seam: Seam, ground: RegionMap, seed: Long): TerrainField = when (seam) {
        // Nothing to build. `SHEARED` asks for no fault; `FUZZED` already happened, in the width `mapFor`
        // took off the same seam — two shapes interlocking is not something a node over the shape can say.
        Seam.SHEARED, Seam.FUZZED -> rock
        Seam.SCARP -> Fault.of(rock, ground, Fault.alternatingThrows(ground.members, Terrain.SCARP_THROW, seed))
        Seam.RIFT -> Rift.opened(rock, ground, Terrain.RIFT_FLOOR)
    }

    /**
     * Where water stands in this Age's rock — **one table per carving**, each answering for its own
     * territory (Jonah's call, design §3.4, reversing an earlier decision).
     *
     * This used to draw a single table from the seed, on the grounds that a stepped water level would read
     * as a bug rather than as impossible geometry. What that actually produced was `caves` beside
     * `flooded_caves` dividing into territories identical by construction — the resolver believed it had
     * split the world and the ground was uniform. See [WaterTable.aquiferFor] for why the original argument
     * no longer holds.
     *
     * A carving with no table of its own contributes the sea's own, so the list always lines up with
     * the territory map index for index.
     */
    private fun waterTablesOf(composition: AgeComposition, seaFill: SeaFill, seed: Long): List<WaterTable> =
        composition.carvers.map { carving ->
            carving.waterTable(seaFill, seed) ?: WaterTable.matching(seaFill, seaFill.level, seed)
        }

    /**
     * Where this Age's sea sits when its terrains disagree about it — or whether there is one at all.
     *
     * Each shape declares its own waterline, or none for one that stands in open air, and **the shape that
     * covers the most ground wins**: it is its coastline that most of the world has, so it is the sea most
     * of the world should be at. A pyramid field half-drowned by the sea its dominant neighbour brought is
     * precisely the sort of thing a set-valued terrain exists to make possible, and a shape that wanted no
     * sea can win too, leaving the others standing dry above a floor that expected water.
     *
     * The seed decides only where shares tie, which is the ordinary case of two equal claims (§3.5).
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
     * The few Ages that are a whole generator rather than an assembly of parts.
     *
     * Exhaustive rather than `else`-terminated, and deliberately: [AgeRecipe.worldFor] is what decides
     * which arm a preset lands in, and the two would otherwise be free to disagree in silence — a new
     * bespoke preset would have quietly generated Spire's world. Now it fails to compile.
     */
    private fun bespoke(server: MinecraftServer, preset: AgePreset, seed: Long): ChunkGenerator = when (preset) {
        AgePreset.VANILLA -> VanillaDelegate.overworld(server)
        AgePreset.VANILLA_BARE -> VanillaDelegate.bareOverworld(server)
        // Spire wears its own green plasma biome, since that world is what its whole look was designed
        // around. Everything else that once lived here is a composition now.
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
     * The dimension type — and so *whether the client attaches our sky renderer* — an Age wears.
     *
     * A composed Age decides from what its sky actually turned out to be rather than from its preset alone: an
     * ordinary sky keeps vanilla's own `effects` and vanilla draws it, and anything vanilla cannot draw switches
     * the Age over. See [Sky.dimensionType]. The bespoke Spire keeps the custom sky it was written for.
     */
    fun dimensionType(recipe: AgeRecipe): ResourceLocation = when (val world = recipe.world) {
        is AgeWorld.Composed -> world.composition.sky.dimensionType(skySpec(recipe))
        // The Spire's marker is its own, so its bespoke sky cannot leak into generated Ages and they cannot
        // dilute it. Every other bespoke preset keeps vanilla's.
        is AgeWorld.Bespoke -> if (world.preset == AgePreset.SPIRE) AGE_SPIRE_DIMENSION_TYPE else AGE_PLAIN_DIMENSION_TYPE
    }

    /**
     * The band of world an Age generates into, read off the dimension type it is going to wear.
     *
     * **Derived rather than chosen**, which is the whole point: the generator fills `minY..<topY` itself while the
     * level admits blocks according to its dimension type's JSON, and nothing in vanilla checks that the two
     * agree — a mismatch is silently dropped blocks. Taking one from the other makes them the same decision.
     *
     * So a band belongs to a dimension type, and adding one means adding both halves together.
     */
    fun windowFor(dimensionType: ResourceLocation): VerticalWindow =
        if (dimensionType == AGE_SPIRE_DIMENSION_TYPE) VerticalWindow.LIFTED else VerticalWindow.DEFAULT

    /**
     * The sky an Age has, as data the client can be told.
     *
     * A pure function of the recipe, like everything else here, so the sky an Age shows is the same on every open
     * — and so the server can hand the client a spec without either of them having to agree about anything but
     * the recipe. The bespoke presets keep vanilla's sky; only the Spire has a bespoke one, and its look lives in
     * its own renderer rather than in a spec.
     */
    fun skySpec(recipe: AgeRecipe): SkySpec = when (val world = recipe.world) {
        is AgeWorld.Composed -> world.composition.sky.specFor(
            world.composition.optionsFor(Aspect.SKY, 0),
            recipe.seed,
        )
        is AgeWorld.Bespoke -> SkySpec.VANILLA
    }
}
