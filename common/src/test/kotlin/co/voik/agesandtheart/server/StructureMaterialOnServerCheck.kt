package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * What a book builds the Age's structures of, and how big it grows its trees, on the ground a server generates:
 * a village written of nether bricks has nether bricks in it, and colossal trees stand taller than ordinary ones on
 * trunks thick enough to be more wood. Not more of them: a colossal crown leaves the trees after it nowhere to stand.
 */
@Tags(NEEDS_SERVER)
class StructureMaterialOnServerCheck : FunSpec({
    val server = DrivenServer.shared

    fun inAge(age: String, command: String) = server.run("execute in agesandtheart:$age run $command")

    /** How many of [block] stand in the 32-block columns around [x], [z], counted by filling them with glass. */
    fun countOf(age: String, block: String, x: Int, z: Int): Int =
        (COUNTED_FROM..<COUNTED_TO step LAYER).sumOf { bottom ->
            val said = inAge(age, "fill ${x - HALF} $bottom ${z - HALF} ${x + HALF - 1} ${bottom + LAYER - 1} ${z + HALF - 1} minecraft:glass replace $block")
            Regex("""filled (\d+)""").find(said)?.groupValues?.get(1)?.toInt() ?: 0
        }

    /** The bottom of the highest of the columns' 8-block layers holding any [block] around [x], [z], or null where none does. Clears what it finds. */
    fun highestOf(age: String, block: String, x: Int, z: Int): Int? =
        (COUNTED_FROM..<COUNTED_TO step SLICE).lastOrNull { bottom ->
            val said = inAge(age, "fill ${x - HALF} $bottom ${z - HALF} ${x + HALF - 1} ${bottom + SLICE - 1} ${z + HALF - 1} minecraft:air replace $block")
            Regex("""filled (\d+)""").containsMatchIn(said)
        }

    /** Where `locate` finds [what] in [age]: a column, its height left to the counting. */
    fun located(age: String, what: String): Pair<Int, Int> {
        val found = inAge(age, "locate $what")
        val (x, z) = Regex("""\[(-?\d+), [^,]+, (-?\d+)]""").find(found)?.destructured ?: error("found no $what: $found")
        return x.toInt() to z.toInt()
    }

    fun generatedAround(age: String, x: Int, z: Int) {
        // A forceload returns once its chunks are generated, decorated and with their structures in.
        inAge(age, "forceload add ${x - REACH} ${z - REACH} ${x + REACH} ${z + REACH}")
    }

    test("a village written of nether bricks is built of nether bricks") {
        server.run("age write brickvillages $SEED age nether_bricks villages structures")
        val (x, z) = located("brickvillages", "structure #minecraft:village")
        generatedAround("brickvillages", x, z)
        val bricks = countOf("brickvillages", "minecraft:nether_bricks", x, z)
        check(bricks > 0) { "the village at $x, $z had no nether bricks in it" }
        check(!server.saidSoFar().contains("StructureMaterials")) { "placing it threw: see the boot log" }
    }

    test("a village whose cats stand outside the chunk being decorated still generates") {
        // This seed's village has a cat in a piece chunks from its start, which the count of its blocks once
        // spawned and so deadlocked the generator: nothing ever came back.
        server.run("age write bluevillages $CAT_SEED age blue villages structures")
        generatedAround("bluevillages", 0, 0)
        val (x, z) = located("bluevillages", "structure #minecraft:village")
        generatedAround("bluevillages", x, z)
        check(countOf("bluevillages", "#minecraft:wool", x, z) > 0) { "the village at $x, $z had no wool in it" }
    }

    test("colossal trees stand taller than ordinary trees, on trunks that make them more wood") {
        server.run("age write ordinarytrees $SEED age overworld landmass trees features")
        server.run("age write colossaltrees $SEED age overworld landmass colossal trees features")
        val (x, z) = located("ordinarytrees", "biome #minecraft:is_forest")
        generatedAround("ordinarytrees", x, z)
        generatedAround("colossaltrees", x, z)
        val ordinaryWood = countOf("ordinarytrees", "#minecraft:logs", x, z)
        val colossalWood = countOf("colossaltrees", "#minecraft:logs", x, z)
        check(colossalWood > ordinaryWood) { "colossal trees were $colossalWood logs against ordinary trees' $ordinaryWood" }
        val ordinary = highestOf("ordinarytrees", "minecraft:glass", x, z)
        val colossal = highestOf("colossaltrees", "minecraft:glass", x, z)
        check(ordinary != null) { "the forest at $x, $z had no trees in it, so this compares nothing" }
        check(colossal != null && colossal > ordinary) { "colossal trees reached y $colossal against ordinary trees' $ordinary" }
    }
})

private const val SEED = 7L

/** The seed whose village spawned a cat chunks from its start (2026-10-18). */
private const val CAT_SEED = -6446760600093126732L
private const val HALF = 16
private const val REACH = 32
private const val COUNTED_FROM = 40
private const val COUNTED_TO = 168
private const val LAYER = 32
private const val SLICE = 8
