package co.voik.agesandtheart.age

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.aspect.Share
import co.voik.agesandtheart.age.word.Antonym
import co.voik.agesandtheart.age.word.PresetTags
import co.voik.agesandtheart.age.word.Word
import co.voik.agesandtheart.location
import co.voik.agesandtheart.sky.SkySpec
import co.voik.agesandtheart.worldgen.AgeChunkGenerator
import co.voik.agesandtheart.worldgen.SpireChunkGenerator
import co.voik.agesandtheart.worldgen.biome.AgeBiomeSource
import co.voik.agesandtheart.worldgen.field.Chance
import co.voik.agesandtheart.worldgen.field.Choose
import co.voik.agesandtheart.worldgen.field.Density
import co.voik.agesandtheart.worldgen.field.Fault
import co.voik.agesandtheart.worldgen.field.Grid
import co.voik.agesandtheart.worldgen.field.Placement
import co.voik.agesandtheart.worldgen.field.PlacementKind
import co.voik.agesandtheart.worldgen.field.Radial
import co.voik.agesandtheart.worldgen.field.RegionMap
import co.voik.agesandtheart.worldgen.field.RegionRule
import co.voik.agesandtheart.worldgen.field.Rift
import co.voik.agesandtheart.worldgen.field.Scatter
import co.voik.agesandtheart.worldgen.field.Slab
import co.voik.agesandtheart.worldgen.field.TerrainField
import com.mojang.serialization.JsonOps
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * Builds every codec the mod registers, and nothing else.
 *
 * It exists because of a specific bug that reached a server boot. A codec lives in a companion object,
 * a companion initialises top to bottom, and a field declared *below* one that reads it is simply null
 * at that moment — so `AgeChunkGenerator.CODEC` came out referencing a null helper and the game
 * refused to start. Nothing caught it earlier because no offline check had reason to load those
 * classes: [RecipeCheck] deliberately stays registry-free and so never touches a generator.
 *
 * **Loading the class is the test.** The assertions below exist only to stop the compiler eliding the
 * loads; if one of these companions is misordered, the check dies on the way in rather than failing an
 * assertion.
 *
 * Two things it deliberately does not do. It does not bootstrap *lightly* — a codec that mentions a
 * block needs the registries, so this pays a few seconds where the other checks pay milliseconds, which
 * is still far cheaper than finding out from a server. And it does not iterate `AgeContent`, because that
 * object eagerly constructs an `Item` and an item cannot be built once the registries have frozen. The
 * cost is that this list is maintained by hand: **add a codec, add it here.**
 */
@Tags(NEEDS_REGISTRIES)
class CodecCheck : FunSpec({

    test("every registered codec builds") {
        MinecraftRegistries.ensureStoodUp()
        val codecs = listOf(
            "chunk generator (field)" to AgeChunkGenerator.CODEC,
            "chunk generator (spire)" to SpireChunkGenerator.CODEC,
            "biome source (age)" to AgeBiomeSource.CODEC,
            "surface rule (regions)" to RegionRule.CODEC,
            "field tree" to TerrainField.CODEC,
            "sky spec" to SkySpec.CODEC,
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
    }

    /**
     * One real write-and-read per placement kind — the exception to this file's "loading the class is the
     * test" rule, and it earns the exception.
     *
     * Every other codec listed above is reached by something that already round-trips it: a preset uses it,
     * and [RecipeCheck] writes that preset out and reads it back. A placement kind no preset has adopted yet
     * has nothing doing that for it, so a field named wrong or a getter pointed at the wrong property would
     * sit undiscovered until the first Age using it failed to load — which is to say, in a save.
     */
    test("placements survive a write") {
        MinecraftRegistries.ensureStoodUp()
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

    /**
     * `Chance` and `Choose`, written and read back — the same exception, earned the same way.
     *
     * No preset composes either yet (they are capability ahead of vocabulary, like `Terrain.ALTITUDE`), so
     * nothing else in the build writes them and a field named wrong would wait for the first Age that used
     * one. `Choose` has the more breakable shape of the two: a nested list of records whose `weight` is
     * optional, so the round-trip below deliberately includes one alternative that states a weight and one
     * that leaves it out.
     *
     * Their *behaviour* — reproducibility, counts, weights, probabilities — is `ChooseCheck`'s business, not
     * this file's. This only asks whether the bytes survive.
     */
    test("the randomised combinators survive a write") {
        MinecraftRegistries.ensureStoodUp()
        val cases = listOf<TerrainField>(
            Chance(child = Slab(lowY = 0, highY = 8), probability = 0.3, seed = 12_345L),
            Choose(
                alternatives = listOf(
                    Choose.Alternative(Slab(lowY = 0, highY = 4), weight = 3.0),
                    Choose.Alternative(Slab(lowY = 10, highY = 14)),
                ),
                leastPlaced = 1,
                mostPlaced = 2,
                seed = 6_789L,
            ),
        )
        for (field in cases) roundTrips(field)
    }

    /**
     * `Fault` and `Rift`, written and read back — and the case for them is stronger than for the two above.
     *
     * Both embed a whole `RegionMap` rather than a scalar, so a getter pointed at the wrong property or a
     * field name that drifts loses an Age's *territories* and not just a number. And `Rift` is the toolkit's
     * only node with a map and no children, so it is the one whose codec is built the other way round (a
     * plain `CODEC`, not a `codec(self)`) — a mistake there would be invisible until an Age used one.
     *
     * `FaultCheck` owns their behaviour. This only asks whether the bytes survive.
     */
    test("the fault nodes survive a write") {
        MinecraftRegistries.ensureStoodUp()
        val territories = RegionMap(
            members = 2, scale = 400.0, blend = 12, originX = 40, originZ = -80, seed = 0x4E6109L,
            shares = listOf(3.0, 1.0),
        )
        val cases = listOf<TerrainField>(
            Fault(base = Slab(lowY = 60, highY = 70), map = territories, throws = listOf(32, -32)),
            Rift(map = territories, halfWidth = 16.0, floorY = 40),
        )
        for (field in cases) roundTrips(field)
    }
})

/** Write it, read it, and insist on getting the same thing back. */
private fun roundTrips(field: TerrainField) {
    val written = TerrainField.CODEC.encodeStart(JsonOps.INSTANCE, field).getOrThrow {
        error("${field.kind} would not encode: $it")
    }
    val read = TerrainField.CODEC.parse(JsonOps.INSTANCE, written).getOrThrow {
        error("${field.kind} encoded to $written and would not read back: $it")
    }
    check(read == field) { "${field.kind} came back changed: wrote $field, read $read" }
}
