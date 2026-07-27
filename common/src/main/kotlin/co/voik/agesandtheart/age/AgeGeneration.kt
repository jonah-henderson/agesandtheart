package co.voik.agesandtheart.age

import co.voik.agesandtheart.age.slot.Slot
import co.voik.agesandtheart.location
import co.voik.agesandtheart.worldgen.FieldChunkGenerator
import co.voik.agesandtheart.worldgen.SpireChunkGenerator
import co.voik.agesandtheart.worldgen.VanillaDelegate
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.biome.FixedBiomeSource
import net.minecraft.world.level.chunk.ChunkGenerator

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
        is AgeWorld.Composed -> assemble(server, world.composition, recipe.seed)
        is AgeWorld.Bespoke -> bespoke(server, world.preset, recipe.seed)
    }

    /** One preset per slot, each answering for its own part of the world. */
    private fun assemble(server: MinecraftServer, composition: AgeComposition, seed: Long): ChunkGenerator {
        val landform = composition.landform.field(composition.options.of(Slot.LANDFORM))
        val ambient = composition.medium.over(composition.landform, composition.options.of(Slot.MEDIUM))
        val dressing = composition.dressing
        return FieldChunkGenerator(
            dressing.biomes(server, landform, seed),
            landform,
            ambient,
            dressing.palette(),
            composition.subsurface.carvers(server),
            composition.subsurface.waterTable(ambient, seed),
            dressing.structures(server, composition.options.of(Slot.DRESSING)),
        )
    }

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
