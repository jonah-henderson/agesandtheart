package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * What an Age's rock may be made of (`Materials`), through the real path a writer's block takes.
 *
 * **Here rather than offline, because a tag binds nowhere else.** `Materials`' shape rule is a pure
 * question about a block and `MaterialsCheck` holds it without a server; `invalid_for_terrain` is datapack
 * content, so offline it is empty and an offline check would pass on a rule that had lost it.
 * That is the same reason the tag layer's derivation is held by a server check.
 *
 * A parameter a composition cannot read is *reported* rather than refused, since a composition is a save — so
 * the question asked here is whether `/age list` complains, not whether the command failed.
 */
@Tags(NEEDS_SERVER)
class TerrainMaterialCheck : FunSpec({
    val server = DrivenServer.shared

    fun rockOf(name: String, block: String): String {
        server.run("age compose $name 4242 landmass=gentle landmass.stone=$block")
        return server.run("age list")
    }

    /** The rule itself, through the real path a writer's block takes. */
    test("a rock you would fall through is refused") {
        val said = rockOf("rocksign", "minecraft:oak_sign")
        check("landmass.stone=minecraft:oak_sign" in said.substringAfter("rocksign")) {
            "the sign was not even stored, where the composition should keep what it was told:\n$said"
        }
        check("ignored" in said.substringAfter("rocksign").substringBefore("agesandtheart:", missingDelimiterValue = said)) {
            "a world of signs was accepted in silence:\n$said"
        }
    }

    /** And an ordinary block still gets through it, which is the half a rule can quietly lose. */
    test("a rock you can stand on is kept") {
        val said = rockOf("rockgold", "minecraft:gold_block")
        val mine = said.substringAfter("rockgold")
        check("ignored" !in mine.substringBefore("agesandtheart:", missingDelimiterValue = mine)) {
            "a world of gold was refused, and the rule has tightened past what it is for:\n$said"
        }
    }

    /** **There is no exception channel**: cake, which once had one, is refused like any other part-block. */
    test("a rock of cake is refused") {
        val said = rockOf("rockcake", "minecraft:cake")
        val mine = said.substringAfter("rockcake")
        check("ignored" in mine.substringBefore("agesandtheart:", missingDelimiterValue = mine)) {
            "a world of cake was accepted, and nothing should admit a block that is not a whole cube:\n$said"
        }
    }

    /**
     * **`invalid_for_terrain` is the other direction**, for what a shape cannot answer: leaves are a full
     * cube with no block entity that decays where no log stands, so a world of them evaporates.
     */
    test("a block the tag refuses is dropped though its shape allows it") {
        val said = rockOf("rockleaf", "minecraft:oak_leaves")
        val mine = said.substringAfter("rockleaf")
        check("ignored" in mine.substringBefore("agesandtheart:", missingDelimiterValue = mine)) {
            "leaves are in invalid_for_terrain and were accepted, so the tag is not binding:\n$said"
        }
    }
})
