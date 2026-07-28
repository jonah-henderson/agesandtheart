package co.voik.agesandtheart.age

import co.voik.agesandtheart.age.slot.Medium
import co.voik.agesandtheart.worldgen.field.AmbientMedium
import co.voik.agesandtheart.worldgen.field.WaterTable
import co.voik.agesandtheart.age.slot.Slot
import co.voik.agesandtheart.worldgen.biome.RegionBiomeSource
import co.voik.agesandtheart.worldgen.field.RegionRule
import co.voik.agesandtheart.worldgen.field.Regions
import co.voik.agesandtheart.location
import co.voik.agesandtheart.worldgen.FieldChunkGenerator
import co.voik.agesandtheart.worldgen.SpireChunkGenerator
import co.voik.agesandtheart.worldgen.VanillaDelegate
import net.minecraft.core.HolderSet
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.biome.FixedBiomeSource
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.XoroshiroRandomSource

/**
 * Turns an [AgeRecipe] into the generator that builds its world.
 *
 * This is the one place a recipe becomes machinery, and it is deliberately a *pure function of the
 * recipe* (plus the server, for the registries the presets need): an Age is replayable data, so the
 * same recipe must give the same world on every open, restart-replay included.
 *
 * Note how little is left here now that presets are typed by slot. Assembling a composed Age has no
 * decisions in it at all, because every decision belongs to a slot preset that owns it. That is the
 * point of §3.1 — combinations are sensible by construction rather than by being written out one at a
 * time, and this function never has to know which ones exist.
 */
object AgeGeneration {
    /**
     * The Spire dimension type (registered as a datapack dimension-type at load). Its `effects` id is
     * `agesandtheart:age`, the marker the client watches to attach the custom Spire sky renderer.
     */
    val AGE_DIMENSION_TYPE: ResourceLocation = "age".location()

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
     * One preset per slot, each answering for its own part of the world — and where a slot holds
     * several, the territories they divide it into.
     *
     * Note that three separate things consult a territory map here: the field lays the rock, the surface
     * rule paints it, and the biome source decides what the place *is*. They read [AgeCharacter.mapFor],
     * so the seams agree to the column — three maps drawn independently would put one boundary in three
     * nearly-identical places and read as three faults rather than one edge.
     */
    private fun assemble(server: MinecraftServer, composition: AgeComposition, recipe: AgeRecipe): ChunkGenerator {
        val seed = recipe.seed
        val character = recipe.character
        // Per territory, not per slot: "copper spires and andesite hills" names one parameter twice, and a
        // slot-wide answer has nowhere to put the second (see [SlotOptions]).
        fun landformOptions(member: Int) = composition.optionsFor(Slot.LANDFORM, member)
        fun dressingOptions(member: Int) = composition.optionsFor(Slot.DRESSING, member)

        val ground = character.mapFor(Slot.LANDFORM, composition.sharesOf(Slot.LANDFORM), seed)
        val shape = Regions.of(
            composition.landforms.mapIndexed { member, landform -> landform.field(landformOptions(member)) },
            ground,
        )

        val flow = character.mapFor(Slot.MEDIUM, composition.sharesOf(Slot.MEDIUM), seed)
        val ambient = Medium.pour(
            composition.mediums,
            waterlineOf(composition, seed),
            // The first territory's, deliberately: `depth` shifts the **waterline**, which is one number for
            // the whole Age, so a sea cannot be deep in one territory and shallow in the next however the
            // sentence is aimed. The substance divides; the level does not.
            composition.optionsFor(Slot.MEDIUM, 0),
            flow,
        )

        val cover = character.mapFor(Slot.DRESSING, composition.sharesOf(Slot.DRESSING), seed)
        val below = character.mapFor(Slot.SUBSURFACE, composition.sharesOf(Slot.SUBSURFACE), seed)
        return FieldChunkGenerator(
            RegionBiomeSource.of(
                composition.dressings.mapIndexed { member, dressing ->
                    dressing.biomes(server, shape, seed, dressingOptions(member))
                },
                cover,
            ),
            shape,
            ambient,
            RegionRule.of(
                composition.dressings.mapIndexed { member, dressing -> dressing.palette(dressingOptions(member)) },
                cover,
            ),
            composition.subsurfaces.map { it.carvers(server) },
            below,
            waterTablesOf(composition, ambient, seed),
            // Union, not per-territory: vanilla places structures against the whole dimension, and its
            // own biome predicates already keep a village out of the territory that has no villages in it.
            HolderSet.direct(
                composition.dressings
                    .flatMapIndexed { member, dressing -> dressing.structures(server, dressingOptions(member)).toList() }
                    .distinct(),
            ),
        )
    }

    /**
     * Where water stands in this Age's rock — **one table per subsurface**, each answering for its own
     * territory (Jonah's call, design §3.4, reversing an earlier decision).
     *
     * This used to draw a single table from the seed, on the grounds that a stepped water level would read
     * as a bug rather than as impossible geometry. What that actually produced was `caves` beside
     * `flooded_caves` dividing into territories identical by construction — the resolver believed it had
     * split the world and the ground was uniform. See [WaterTable.aquiferFor] for why the original argument
     * no longer holds.
     *
     * A subsurface with no table of its own contributes the ambient one, so the list always lines up with
     * the territory map index for index.
     */
    private fun waterTablesOf(composition: AgeComposition, ambient: AmbientMedium, seed: Long): List<WaterTable> =
        composition.subsurfaces.map { subsurface ->
            subsurface.waterTable(ambient, seed) ?: WaterTable.matching(ambient, ambient.level, seed)
        }

    /**
     * Where this Age's sea sits when its landforms disagree about it — or whether there is one at all.
     *
     * Each shape declares its own waterline, or none for one that stands in open air, and **the shape that
     * covers the most ground wins**: it is its coastline that most of the world has, so it is the sea most
     * of the world should be at. A pyramid field half-drowned by the sea its dominant neighbour brought is
     * precisely the sort of thing a set-valued landform exists to make possible, and a shape that wanted no
     * sea can win too, leaving the others standing dry above a floor that expected water.
     *
     * The seed decides only where shares tie, which is the ordinary case of two equal claims (§3.5).
     */
    private fun waterlineOf(composition: AgeComposition, seed: Long): Int? {
        val claimed = composition.landforms.map { it.waterline }
        if (claimed.size == 1) return claimed.first()
        val shares = composition.sharesOf(Slot.LANDFORM)
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
     * The dimension type — and so the sky — an Age wears.
     *
     * A composed Age names it outright; the bespoke Spire keeps the custom sky it was written for.
     */
    fun dimensionType(recipe: AgeRecipe): ResourceLocation = when (val world = recipe.world) {
        is AgeWorld.Composed -> world.composition.sky.dimensionType
        is AgeWorld.Bespoke -> if (world.preset == AgePreset.SPIRE) AGE_DIMENSION_TYPE else AGE_PLAIN_DIMENSION_TYPE
    }
}
