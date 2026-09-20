package co.voik.agesandtheart.age

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.consequence.Consequence
import co.voik.agesandtheart.age.word.Antonym
import co.voik.agesandtheart.age.word.PresetTags
import co.voik.agesandtheart.age.word.Word
import co.voik.agesandtheart.location
import co.voik.ephemeris.sky.SkySpec
import co.voik.agesandtheart.worldgen.AgeRock
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings
import co.voik.agesandtheart.generation.WorldgenCodecs
import co.voik.agesandtheart.worldgen.field.Chance
import co.voik.agesandtheart.worldgen.field.Choose
import co.voik.agesandtheart.worldgen.field.Density
import co.voik.agesandtheart.worldgen.field.Fault
import co.voik.agesandtheart.worldgen.field.Grid
import co.voik.agesandtheart.worldgen.field.Placement
import co.voik.agesandtheart.worldgen.field.PlacementKind
import co.voik.agesandtheart.worldgen.field.Radial
import co.voik.agesandtheart.worldgen.field.RegionMap
import co.voik.agesandtheart.worldgen.field.Rift
import co.voik.agesandtheart.worldgen.field.Scatter
import co.voik.agesandtheart.worldgen.field.Slab
import co.voik.agesandtheart.worldgen.field.TerrainField
import com.mojang.serialization.JsonOps
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * Builds every codec the mod registers, and nothing else. **Loading the class is the test** — a companion
 * initialises top to bottom, so a field declared below one that reads it is null at that moment, and the
 * assertions below exist only to stop the compiler eliding the loads.
 *
 * **The registered generation kinds are iterated rather than copied.** `WorldgenCodecs` holds no `Item`
 * and no `Block`, so unlike `AgeContent` — which eagerly constructs an `Item` that cannot be built once the
 * registries have frozen — it can simply be read here. The hand-written copy it replaces had already
 * drifted, missing `NearTheSurface` entirely, which is what a duplicate is for.
 *
 * What is still listed by hand is the codecs **nothing registers**, which no list can supply:
 * **add one of those, add it here.**
 */
@Tags(NEEDS_REGISTRIES)
class CodecCheck : FunSpec({

    test("every registered codec builds") {
        // Every kind a loader actually registers, read off the lists the loaders read — so adding one
        // brings it under this check for free, and none can be forgotten the way NearTheSurface was.
        val registered: List<Pair<String, Any?>> = (
            WorldgenCodecs.chunkGeneratorCodecs + WorldgenCodecs.biomeSourceCodecs +
                WorldgenCodecs.surfaceRuleCodecs + WorldgenCodecs.surfaceConditionCodecs
            ).map { (id, codec) -> "registered $id" to codec }
        val codecs: List<Pair<String, Any?>> = registered + listOf(
            "field tree" to TerrainField.CODEC,
            "sky spec" to SkySpec.CODEC,
            "instability" to Instability.CODEC,
            "flaw" to Flaw.CODEC,
            "word" to Word.mapCodec("floating".location()).codec(),
            "preset tags" to PresetTags.CODEC,
            "antonym" to Antonym.CODEC,
            "region map" to RegionMap.MAP_CODEC.codec(),
            "placement (dispatch)" to Placement.CODEC,
            // Every kind by name, so adding one to the enum brings it under this check for free — the
            // dispatch codec above builds its branches lazily and would not have touched them.
        ) + PlacementKind.entries.map { kind -> "placement (${kind.serializedName})" to kind.codec() }

        for ((what, codec) in codecs) {
            checkNotNull(codec) { "$what built a null codec — something above it in its companion is null too" }
        }
    }

    /**
     * **What an Age's instability bought survives a write** — which nothing else in the suite asks.
     *
     * `AgeChunkGenerator.CODEC` is registered and built and never round-tripped, so these four keys are the
     * save format of every torn Age with nothing checking them. They were four loose fields on the
     * generator until they became [Consequence.MAP_CODEC]'s, and the promise made then was that the names,
     * the defaults and the order did not move. This is that promise, written down.
     *
     * The names are asserted rather than the shape alone: a rename here does not fail, it silently orphans
     * the wounds of every Age already on disk.
     */
    test("what instability bought survives a write") {
        val codec = Consequence.MAP_CODEC.codec()
        val bought = Consequence(
            woundsPerChunk = 2.5,
            woundsPerDay = 0.25,
            collapseTears = 3,
            writtenAt = 123_456L,
        )
        val written = codec.encodeStart(JsonOps.INSTANCE, bought)
            .getOrThrow { error("a consequence would not encode: $it") }
        val read = codec.parse(JsonOps.INSTANCE, written)
            .getOrThrow { error("a consequence encoded to $written and would not read back: $it") }
        check(read == bought) { "read back as a different consequence: wrote $bought, read $read" }

        val keys = written.asJsonObject.keySet()
        check(keys == setOf("wounds_per_chunk", "wounds_per_day", "collapse_tears", "written_at")) {
            "the consequence's keys moved, which orphans every torn Age already written: $keys"
        }

        // And a coherent Age writes nothing at all, which is the whole of what the defaults are for.
        val nothing = codec.encodeStart(JsonOps.INSTANCE, Consequence.NONE)
            .getOrThrow { error("a coherent Age would not encode: $it") }
        check(nothing.asJsonObject.keySet().isEmpty()) { "a coherent Age wrote $nothing rather than nothing" }
        val backToNone = codec.parse(JsonOps.INSTANCE, nothing)
            .getOrThrow { error("an omitted consequence would not read back: $it") }
        check(backToNone == Consequence.NONE) { "an Age that wrote nothing read back as $backToNone" }
    }

    /**
     * One real write-and-read per placement kind — the exception to "loading the class is the test", and
     * it earns it: no preset has adopted these yet, so nothing else round-trips them and a field named
     * wrong would wait for the first Age that used one.
     */
    test("placements survive a write") {
        val cases = listOf<Placement>(
            Grid(spacing = 250.0, jitter = 20.0, density = Density.uniform(0.85)),
            Radial(ringSpacing = 300.0, arcSpacing = 200.0, jitter = 40.0, density = Density(atOrigin = 1.0, atEdge = 0.2, falloffRadius = 900.0)),
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
     * **Which rock answers for an Age survives a write**, both ways.
     *
     * The exception earns itself here more than anywhere: this is the field that selects the *mode*, so
     * losing it does not make a lossier generator, it makes a different world. It was a nullable outside
     * the codec entirely — an Age wearing vanilla's rock read back with no rock at all and a field of no
     * height, which is an empty world.
     */
    test("which rock answers survives a write") {
        val settings = MinecraftRegistries.worldgen.lookupOrThrow(Registries.NOISE_SETTINGS)
            .getOrThrow(NoiseGeneratorSettings.NETHER)
        val cases = listOf<AgeRock>(
            AgeRock.Ours(Slab(lowY = 0, highY = 40)),
            AgeRock.Ours(Slab(lowY = 0, highY = 40), hollows = Slab(lowY = 0, highY = 12)),
            AgeRock.Vanillas(settings),
        )
        check(cases.map { it.kind }.toSet() == AgeRock.Kind.entries.toSet()) {
            "a rock kind has no round-trip case here — add one, it is what decides an Age's whole terrain"
        }
        val codec = AgeRock.MAP_CODEC.codec()
        // Registry-aware, because a rock carries its world's skin and 26.2's surface rules may name
        // biomes — which a plain `JsonOps` cannot reach the registry for.
        val ops = MinecraftRegistries.worldgen.createSerializationContext(JsonOps.INSTANCE)
        for (rock in cases) {
            val written = codec.encodeStart(ops, rock).getOrThrow {
                error("${rock.kind} would not encode: $it")
            }
            val read = codec.parse(ops, written).getOrThrow {
                error("${rock.kind} encoded and would not read back: $it")
            }
            // **The kind first, because it is the whole point.** A rock that came back as the other kind is
            // not a lossier Age, it is a different world — an empty one, which is what losing this did.
            check(read.kind == rock.kind) { "${rock.kind} came back as ${read.kind}, which is another world" }
            when (rock) {
                // **Which rock, not every byte of it.** `NoiseGeneratorSettings.CODEC` is a registry file
                // codec and the offline provider cannot write a reference, so the settings go inline and
                // come back rebuilt — and vanilla's density functions do not compare equal once rebuilt.
                // What identifies the nether's rock as the nether's is what is asked instead.
                is AgeRock.Vanillas -> {
                    val theirs = (read as AgeRock.Vanillas).settings.value()
                    val mine = rock.settings.value()
                    check(theirs.defaultBlock() == mine.defaultBlock() && theirs.seaLevel() == mine.seaLevel()) {
                        "vanilla's rock came back as ${theirs.defaultBlock()} at y=${theirs.seaLevel()}, " +
                            "where it was ${mine.defaultBlock()} at y=${mine.seaLevel()}"
                    }
                }

                is AgeRock.Ours -> check(read == rock) { "our rock came back changed: wrote $rock, read $read" }
            }
        }
    }

    /**
     * `Chance` and `Choose`, written and read back — the same exception, earned the same way. `Choose` has
     * the more breakable shape: a nested list of records whose `weight` is optional, so the round-trip
     * includes one alternative stating a weight and one leaving it out.
     *
     * Their *behaviour* is `ChooseCheck`'s business. This asks only whether the bytes survive.
     */
    test("the randomised combinators survive a write") {
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
     * `Fault` and `Rift`, written and read back — a stronger case than the two above: both embed a whole
     * `RegionMap`, so a drifting field name loses an Age's *territories* rather than a number, and `Rift`
     * is the toolkit's only node with a map and no children, so its codec is built the other way round (a
     * plain `CODEC`, not a `codec(self)`).
     */
    test("the fault nodes survive a write") {
        val territories = RegionMap(
            members = 2, scale = 400.0, blend = 12, originX = 40, originZ = -80, seed = 0x4E6109L,
            shares = listOf(3.0, 1.0),
        )
        val cases = listOf<TerrainField>(
            Fault(base = Slab(lowY = 60, highY = 70), map = territories, throws = listOf(32, -32)),
            Rift(map = territories, halfWidth = 16.0, floorY = 40, rimY = 72),
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
