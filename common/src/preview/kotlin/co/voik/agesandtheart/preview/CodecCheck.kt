package co.voik.agesandtheart.preview

import co.voik.agesandtheart.worldgen.FieldChunkGenerator
import co.voik.agesandtheart.worldgen.SpireChunkGenerator
import co.voik.agesandtheart.worldgen.biome.AgeBiomeSource
import co.voik.agesandtheart.worldgen.biome.RegionBiomeSource
import co.voik.agesandtheart.worldgen.field.RegionRule
import co.voik.agesandtheart.worldgen.field.TerrainField
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap

/**
 * Builds every codec the mod registers, and nothing else.
 *
 * It exists because of a specific bug that reached a server boot. A codec lives in a companion object,
 * a companion initialises top to bottom, and a field declared *below* one that reads it is simply null
 * at that moment — so `FieldChunkGenerator.CODEC` came out referencing a null helper and the game
 * refused to start. Nothing caught it earlier because no offline check had reason to load those
 * classes: [RecipeCheck] deliberately stays registry-free and so never touches a generator.
 *
 * **Loading the class is the test.** The assertions below exist only to stop the compiler eliding the
 * loads; if one of these companions is misordered, the check dies on the way in rather than failing an
 * assertion.
 *
 * Two things it deliberately does not do. It does not bootstrap *lightly* — a codec that mentions a
 * block needs the registries, so this pays a couple of seconds where the other checks pay
 * milliseconds, which is still far cheaper than finding out from a server. And it does not iterate
 * `AgeContent`, because that object eagerly constructs an `Item` and an item cannot be built once the
 * registries have frozen. The cost is that this list is maintained by hand: **add a codec, add it here.**
 */
fun main() {
    // Version detection first, or the bootstrap trips over DataFixerUpper's schemas: block states build
    // collision shapes, which reach items, which reach entity types, which want a data-fixer schema that
    // does not exist until the version is known. Vanilla's own headless tooling does the same two calls.
    SharedConstants.tryDetectVersion()
    Bootstrap.bootStrap()

    val codecs = listOf(
        "chunk generator (field)" to FieldChunkGenerator.CODEC,
        "chunk generator (spire)" to SpireChunkGenerator.CODEC,
        "biome source (age)" to AgeBiomeSource.CODEC,
        "biome source (regions)" to RegionBiomeSource.CODEC,
        "surface rule (regions)" to RegionRule.CODEC,
        "field tree" to TerrainField.CODEC,
    )
    for ((what, codec) in codecs) {
        checkNotNull(codec) { "$what built a null codec — something above it in its companion is null too" }
    }

    println("Codecs: all ${codecs.size} build, so no companion reads a field declared below it.")
}
