package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * The two tags that overrule what an Age's rock may be made of (`Materials`).
 *
 * **Here rather than offline, because a tag binds nowhere else.** `Materials`' shape rule is a pure
 * question about a block and `MaterialsCheck` holds it without a server; the exceptions are datapack
 * content, so offline they are empty and an offline check would pass on a rule that had lost both of them.
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

    /**
     * **`valid_for_terrain` is the exception channel**, and cake is what it was made for: not a full cube,
     * refused by the shape rule, and a world of it is a thing the Art should be able to say.
     */
    test("a block the tag admits is kept though its shape refuses it") {
        val said = rockOf("rockcake", "minecraft:cake")
        val mine = said.substringAfter("rockcake")
        check("ignored" !in mine.substringBefore("agesandtheart:", missingDelimiterValue = mine)) {
            "cake is in valid_for_terrain and was still refused, so the tag is not binding:\n$said"
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
