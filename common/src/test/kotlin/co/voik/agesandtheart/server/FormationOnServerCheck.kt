package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * **`gold_block obelisks`, end to end** — the one thing offline cannot answer.
 *
 * A formation's pattern is a placed feature of *ours*, and placed features are a datapack registry: they
 * do not exist until a server has loaded its packs. So `FormationCheck` can prove that a shape is laid and
 * that minting swaps a substance, and it cannot prove that the word reaches the shipped shape at all.
 * Everything between the page a writer lays and the feature an Age grows lives here.
 */
@Tags(NEEDS_SERVER)
class FormationOnServerCheck : FunSpec({
    val server = DrivenServer.shared

    /** What one book left in the features pool, as the recipe holds it. */
    fun grownBy(name: String, vararg pages: String): String {
        server.run("age write $name ${pages.joinToString(" ")}")
        return server.run("age list").substringAfter(name)
    }

    test("a material and a shape said together mint a formation") {
        val grown = grownBy("goldobelisks", "age", "gold_block", "obelisks")
        check("agesandtheart:obelisks" in grown) {
            "'gold_block obelisks' reached no formation at all:\n$grown"
        }
        check("of=minecraft:gold_block" in grown) {
            "the obelisks were not made of what the clause named:\n$grown"
        }
    }

    /** Every shape is a page a writer can lay, which is the half a missing data file would lose in silence. */
    test("every shape the pack ships can be written") {
        val shapes = listOf("obelisks", "pyramids", "boulders", "spikes", "rings", "arches")
        val missed = shapes.filterNot { shape ->
            "agesandtheart:$shape" in grownBy("shape$shape", "age", "blackstone", shape)
        }
        check(missed.isEmpty()) { "these shapes are words the Art cannot grow: $missed" }
    }
})
