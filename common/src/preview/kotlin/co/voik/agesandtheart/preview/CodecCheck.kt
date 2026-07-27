package co.voik.agesandtheart.preview

import co.voik.agesandtheart.age.Flaw
import co.voik.agesandtheart.age.Instability
import co.voik.agesandtheart.age.word.Antonym
import co.voik.agesandtheart.age.word.PresetTags
import co.voik.agesandtheart.age.slot.Share
import co.voik.agesandtheart.age.word.Word
import co.voik.agesandtheart.location
import co.voik.agesandtheart.worldgen.FieldChunkGenerator
import co.voik.agesandtheart.worldgen.SpireChunkGenerator
import co.voik.agesandtheart.worldgen.biome.AgeBiomeSource
import co.voik.agesandtheart.worldgen.biome.RegionBiomeSource
import co.voik.agesandtheart.worldgen.field.Density
import co.voik.agesandtheart.worldgen.field.Grid
import co.voik.agesandtheart.worldgen.field.Placement
import co.voik.agesandtheart.worldgen.field.PlacementKind
import co.voik.agesandtheart.worldgen.field.Radial
import co.voik.agesandtheart.worldgen.field.RegionMap
import co.voik.agesandtheart.worldgen.field.RegionRule
import co.voik.agesandtheart.worldgen.field.Scatter
import co.voik.agesandtheart.worldgen.field.TerrainField
import com.mojang.serialization.JsonOps
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
        "instability" to Instability.CODEC,
        "flaw" to Flaw.CODEC,
        "word" to Word.mapCodec("floating".location()).codec(),
        "preset tags" to PresetTags.CODEC,
        "antonym" to Antonym.CODEC,
        "share" to Share.CODEC,
        "region map" to RegionMap.CODEC,
        "placement (dispatch)" to Placement.CODEC,
        // Every kind by name, so adding one to the enum brings it under this check for free — the
        // dispatch codec above builds its branches lazily and would not have touched them.
    ) + PlacementKind.entries.map { kind -> "placement (${kind.serializedName})" to kind.codec() }
    for ((what, codec) in codecs) {
        checkNotNull(codec) { "$what built a null codec — something above it in its companion is null too" }
    }

    placementsSurviveAWrite()

    println("Codecs: all ${codecs.size} build, so no companion reads a field declared below it.")
}

/**
 * One real write-and-read per placement kind — the exception to this file's "loading the class is the
 * test" rule, and it earns the exception.
 *
 * Every other codec listed above is reached by something that already round-trips it: a preset uses it,
 * and `RecipeCheck` writes that preset out and reads it back. A placement kind no preset has adopted yet
 * has nothing doing that for it, so a field named wrong or a getter pointed at the wrong property would
 * sit undiscovered until the first Age using it failed to load — which is to say, in a save.
 */
private fun placementsSurviveAWrite() {
    val cases = listOf<Placement>(
        Grid(spacing = 250.0, jitter = 20.0, density = Density.uniform(0.85)),
        Radial(ringSpacing = 300.0, arcSpacing = 200.0, jitter = 40.0, density = Density.radial(1.0, 0.2, 900.0)),
        Scatter(cellSize = 160.0, leastPerCell = 0, mostPerCell = 3, density = Density.uniform(0.8)),
    )
    check(cases.map { it.kind }.toSet() == PlacementKind.entries.toSet()) {
        "a placement kind has no round-trip case here — add one, it is the only thing that writes it"
    }
    for (placement in cases) {
        val written = Placement.CODEC.encodeStart(JsonOps.INSTANCE, placement).getOrThrow {
            error("${placement.kind} would not encode: $it")
        }
        val read = Placement.CODEC.parse(JsonOps.INSTANCE, written).getOrThrow {
            error("${placement.kind} encoded to $written and would not read back: $it")
        }
        check(read == placement) { "${placement.kind} came back changed: wrote $placement, read $read" }
    }
}
