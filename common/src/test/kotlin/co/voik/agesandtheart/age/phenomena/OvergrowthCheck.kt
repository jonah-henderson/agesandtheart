package co.voik.agesandtheart.age.phenomena

import com.google.gson.JsonParser
import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.Direction
import net.minecraft.util.RandomSource
import net.minecraft.world.level.ChunkPos

/**
 * The arithmetic and the assets an overgrowth stands on (design §5.2.2): what it favours raising, which way
 * a thorn may turn, and that every pose a thorn can be in has a model.
 */
class OvergrowthCheck : FunSpec({

    fun resource(path: String): String =
        checkNotNull(OvergrowthCheck::class.java.getResourceAsStream(path)) { "no resource $path" }
            .bufferedReader().readText()

    test("eight in ten raised are thorns, one in ten other vines, and mushrooms and everything else share the rest") {
        val rolls = (0..<ROLLS).map { it.toDouble() / ROLLS }
        val counts = rolls.groupingBy { Overgrowth.Raising.at(it) }.eachCount()
        val thorns = counts.getValue(Overgrowth.Raising.THORNS)
        val vines = counts.getValue(Overgrowth.Raising.VINES)
        val mushrooms = counts.getValue(Overgrowth.Raising.MUSHROOM)
        val flora = counts.getValue(Overgrowth.Raising.FLORA)
        check(thorns.toDouble() / ROLLS in 0.79..0.81) { "thorns are ${thorns.toDouble() / ROLLS} of what is raised: $counts" }
        check(vines.toDouble() / ROLLS in 0.09..0.11) { "other vines are ${vines.toDouble() / ROLLS} of what is raised: $counts" }
        check(mushrooms == flora) { "mushrooms and everything else are not shared evenly: $counts" }
        check(flora > 0) { "everything else is never raised: $counts" }
    }

    test("an axe is what cuts a thorn") {
        val axes = JsonParser.parseString(resource("/data/minecraft/tags/block/mineable/axe.json"))
            .asJsonObject.getAsJsonArray("values").map { it.asString }
        check("agesandtheart:thorn_vine" in axes) { "thorns are not in the axe's tag, so nothing cuts them quickly" }
    }

    test("a patch is solid at its core, thins towards its edge and stops short of the next chunk") {
        check(OvergrowthPatches.coverageAt(0.0) == 1.0) { "the middle of a patch is not solid" }
        check(OvergrowthPatches.coverageAt(OVER_A_CHUNK_AWAY) == 0.0) { "a patch reaches a whole chunk from its centre" }
        val halfway = OvergrowthPatches.coverageAt(HALFWAY_OUT)
        check(halfway > 0.0 && halfway < 1.0) { "the edge does not thin out: $halfway" }
    }

    test("a mound is a dome, roughened, and never lies flush inside its reach") {
        val plain = (0..12).map { OvergrowthPatches.heightAt(it.toDouble(), MIDDLING_LUMP) }
        check(plain.zipWithNext().all { (nearer, further) -> further <= nearer }) { "the mound is not a dome: $plain" }
        check(plain.first() >= TALL_ENOUGH_TO_STICK_OUT) { "the centre stands only ${plain.first()} high" }
        check(plain.all { it >= 1 }) { "part of the mound lies flush with the ground: $plain" }
        check(OvergrowthPatches.heightAt(0.0, 1.0) > OvergrowthPatches.heightAt(0.0, 0.0)) { "lumps do not change the height" }
    }

    test("the lumps are smooth, vary, and never carry a mound past the chunk next door") {
        val lumps = (0..<SAMPLED_COLUMNS).map { OvergrowthPatches.lumpAt(SEED, it, it * 3) }
        check(lumps.all { it in 0.0..1.0 }) { "a lump fell outside 0..1" }
        check(lumps.max() - lumps.min() > ENOUGH_VARIANCE) { "the lumps barely vary: ${lumps.min()} to ${lumps.max()}" }
        val steps = (0..<SAMPLED_COLUMNS).map { OvergrowthPatches.lumpAt(SEED, it, 7) }.zipWithNext().map { (a, b) -> kotlin.math.abs(a - b) }
        check(steps.max() < MOST_ONE_BLOCK_MAY_CHANGE) { "neighbouring columns differ by ${steps.max()}, which is speckle rather than lumps" }
        val stretched = OvergrowthPatches.effectiveDistance(1.0, 1.0)
        val pulledIn = OvergrowthPatches.effectiveDistance(1.0, 0.0)
        check(pulledIn > stretched) { "a lump does not change the reach" }
        check(MOUND_RADIUS / stretched < MOST_A_MOUND_REACHES_IN_BLOCKS) { "a lumpy mound reaches past the chunk beside it" }
    }

    test("a chunk holds a patch's centre about as often as the steps bought say") {
        val chance = OvergrowthPatches.chanceAt(STEPS)
        val chunks = (0..<CHUNKS_SAMPLED).flatMap { x -> (0..<CHUNKS_SAMPLED).map { z -> ChunkPos(x, z) } }
        val centres = chunks.mapNotNull { OvergrowthPatches.centreIn(it, SEED, chance) }
        val share = centres.size.toDouble() / chunks.size
        check(share in chance * 0.7..chance * 1.3) { "$STEPS steps should hold a patch in $chance of chunks, held $share" }
        for (owner in chunks) {
            val centre = OvergrowthPatches.centreIn(owner, SEED, 1.0) ?: error("a chance of one held no centre")
            check(centre.x shr 4 == owner.x && centre.z shr 4 == owner.z) { "a centre fell outside its own chunk: $centre" }
        }
    }

    test("more steps never make patches rarer") {
        val chances = (0..MOST_STEPS).map(OvergrowthPatches::chanceAt)
        check(chances.zipWithNext().all { (fewer, more) -> more >= fewer }) { "the steps are not ordered: $chances" }
        check(chances.first() == 0.0) { "no steps still lay patches" }
    }

    test("a roll at either end of its range still lands on a kind") {
        check(Overgrowth.Raising.at(0.0) == Overgrowth.Raising.THORNS) { "the lowest roll missed" }
        check(Overgrowth.Raising.at(Math.nextDown(1.0)) == Overgrowth.Raising.FLORA) { "the highest roll missed" }
    }

    test("a chain never turns back on itself") {
        val random = RandomSource.create(SEED)
        for (travelling in Direction.entries) {
            repeat(TURNS) {
                val next = ThornVineBlock.headingAfter(travelling, random)
                check(next != travelling.opposite) { "heading $travelling turned straight back to $next" }
            }
        }
    }

    test("a chain does turn, and in every way open to it") {
        val random = RandomSource.create(SEED)
        val seen = (0..<TURNS).map { ThornVineBlock.headingAfter(Direction.NORTH, random) }.toSet()
        val expected = Direction.entries.filter { it != Direction.SOUTH }.toSet()
        check(seen == expected) { "heading north reached $seen, not $expected" }
    }

    test("every pose a thorn can take has a model, and every model it names is there") {
        val variants = JsonParser.parseString(resource("/assets/agesandtheart/blockstates/thorn_vine.json"))
            .asJsonObject.getAsJsonObject("variants")
        val poses = Direction.entries.flatMap { anchor ->
            Direction.entries.filter { it != anchor }.map { heading -> "anchor=${anchor.serializedName},heading=${heading.serializedName}" }
        }
        val missing = poses.filter { it !in variants.keySet() }
        check(missing.isEmpty()) { "no model for $missing" }
        val models = variants.entrySet().map { it.value.asJsonObject.get("model").asString.substringAfter(':') }.toSet()
        for (model in models) resource("/assets/agesandtheart/models/$model.json")
    }
}) {
    private companion object {
        const val ROLLS = 1000
        const val TURNS = 400
        const val SEED = 11L
        const val OVER_A_CHUNK_AWAY = 16.0
        const val HALFWAY_OUT = 10.0
        const val STEPS = 4
        const val TALL_ENOUGH_TO_STICK_OUT = 6
        const val MIDDLING_LUMP = 0.5
        const val SAMPLED_COLUMNS = 200
        const val ENOUGH_VARIANCE = 0.5
        const val MOST_ONE_BLOCK_MAY_CHANGE = 0.5
        const val MOUND_RADIUS = 12.5
        const val MOST_A_MOUND_REACHES_IN_BLOCKS = 16.0
        const val MOST_STEPS = 4
        const val CHUNKS_SAMPLED = 60
    }
}
