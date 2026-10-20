package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus.resolved
import co.voik.agesandtheart.ShippedCorpus.vocabulary
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Materials
import co.voik.agesandtheart.age.aspect.Surface
import co.voik.agesandtheart.age.aspect.Terrain
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * **A material asked for by what it is like** — `"stone": "#frozen"` — and the fence that keeps a broad
 * word from handing out a world of something whose own word is ink-gated ([MaterialTable]).
 */
@Tags(NEEDS_REGISTRIES)
class MaterialQueryCheck : FunSpec({

    val table by lazy { vocabulary.materials }

    /** The prizes Jonah named, and the ones the ink tiers hold, are out of reach offline as on a server. */
    test("the fence holds without a server") {
        val prizes = listOf("minecraft:blue_ice", "minecraft:packed_ice", "minecraft:obsidian", "minecraft:diamond_block")
        val reachable = prizes.filter(table::isAvailableToBroadWords)
        check(reachable.isEmpty()) { "a broad word could still draw $reachable" }
    }

    /**
     * Every query lands on a block that answers it, and never on one the fence holds — over enough seeds
     * that a fenced block carrying the tag strongly would certainly have come up.
     */
    test("a query draws only what answers it and is not withheld") {
        for (tag in listOf("frozen", "stony", "sandy", "molten", "dark", "bright", "colourful", "glowing")) {
            check(table.carriersOf(tag).isNotEmpty()) { "nothing a world can be made of answers #$tag" }
            val drawn = (1L..QUERIES_DRAWN).mapNotNull { table.drawFor("${Materials.QUERY_MARK}$tag", it) }.toSet()
            val withheld = drawn.filterNot(table::isAvailableToBroadWords)
            check(withheld.isEmpty()) { "#$tag drew withheld blocks: $withheld" }
            val answering = table.carriersOf(tag).map { it.first }.toSet()
            check(drawn.all { it in answering }) { "#$tag drew ${drawn - answering}, which do not carry it" }
            check(drawn.size > 1 || answering.size == 1) { "#$tag only ever drew $drawn of $answering" }
        }
    }

    /** The same book at the same seed builds the same rock, and the recipe holds a block rather than a tag. */
    test("a query settles to one block, the same one every time") {
        // `arid` sets the rock to `#sandy`, certainly where it is aimed.
        fun rockAt(seed: Long): List<String> = resolved(seed, "arid", "landmass")
            .composition.optionsFor(Aspect.TERRAIN, 0).allSpelled(Terrain.STONE.name).toList()
        val sandy = table.carriersOf("sandy").toMap()
        for (seed in 1L..SEEDS) {
            val rock = rockAt(seed)
            check(rock.size == 1 && !Materials.isQuery(rock.single())) { "seed $seed left the rock as $rock" }
            check(rock == rockAt(seed)) { "seed $seed built $rock once and ${rockAt(seed)} again" }
            check(rock.single() in sandy) { "seed $seed built ${rock.single()}, which is not sandy" }
        }
        val rocks = (1L..SEEDS).map { rockAt(it).single() }.toSet()
        check(rocks.size > 1) { "#sandy built every Age of $rocks" }
    }

    /** A shipped word's query reaches the ground through a book: `arid` must dress it, so every Age has it. */
    test("an arid Age stands on sandy ground") {
        val sandy = table.carriersOf("sandy").toMap()
        val grounds = (1L..SEEDS).map { seed ->
            resolved(seed, "arid").composition.optionsFor(Aspect.SURFACE, 0).allSpelled(Surface.MATERIAL.name).toList()
        }
        val notSandy = grounds.filterNot { it.size == 1 && it.single() in sandy }
        check(notSandy.isEmpty()) { "arid Ages stood on $notSandy" }
        check(grounds.toSet().size > 1) { "every arid Age stood on ${grounds.first()}" }
    }
})

private const val QUERIES_DRAWN = 400L
private const val SEEDS = 30L
