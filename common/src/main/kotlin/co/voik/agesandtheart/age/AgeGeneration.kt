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
        val landformOptions = composition.options.of(Slot.LANDFORM)
        val dressingOptions = composition.options.of(Slot.DRESSING)

        val ground = character.mapFor(Slot.LANDFORM, composition.sharesOf(Slot.LANDFORM), seed)
        val shape = Regions.of(composition.landforms.map { it.field(landformOptions) }, ground)

        val flow = character.mapFor(Slot.MEDIUM, composition.sharesOf(Slot.MEDIUM), seed)
        val ambient = Medium.pour(
            composition.mediums,
            waterlineOf(composition, seed),
            composition.options.of(Slot.MEDIUM),
            flow,
        )

        val cover = character.mapFor(Slot.DRESSING, composition.sharesOf(Slot.DRESSING), seed)
        val below = character.mapFor(Slot.SUBSURFACE, composition.sharesOf(Slot.SUBSURFACE), seed)
        return FieldChunkGenerator(
            RegionBiomeSource.of(composition.dressings.map { it.biomes(server, shape, seed, dressingOptions) }, cover),
            shape,
            ambient,
            RegionRule.of(composition.dressings.map { it.palette(dressingOptions) }, cover),
            composition.subsurfaces.map { it.carvers(server) },
            below,
            waterTableOf(composition, ambient, seed),
            // Union, not per-territory: vanilla places structures against the whole dimension, and its
            // own biome predicates already keep a village out of the territory that has no villages in it.
            HolderSet.direct(
                composition.dressings.flatMap { it.structures(server, dressingOptions).toList() }.distinct(),
            ),
        )
    }

    /**
     * Where water stands in this Age's rock — **one answer for the whole Age**, drawn from the seed
     * where its subsurfaces disagree.
     *
     * The one place a set-valued slot does *not* divide, and deliberately. What has been cut out of the
     * rock is visible and belongs to its territory; where the water table sits is invisible detail, and
     * a table that stepped up and down across a boundary would not read as impossible geometry — it
     * would read as a bug, because water finding its own level is the one thing everyone expects it to
     * do. Carving splits; hydrology does not.
     */
    private fun waterTableOf(composition: AgeComposition, ambient: AmbientMedium, seed: Long): WaterTable? {
        val choices = composition.subsurfaces
        val chosen = choices.singleOrNull()
            ?: choices[XoroshiroRandomSource(seed xor TABLE_SALT).nextInt(choices.size)]
        return chosen.waterTable(ambient, seed)
    }

    // So which subsurface's hydrology wins is decorrelated from everything else this seed decides.
    private const val TABLE_SALT = 0x7AB_1E5L

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
