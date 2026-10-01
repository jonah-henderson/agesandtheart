package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * That the fusion-compounder offers a result only for inputs that pair off with a recipe, and only with
 * what that recipe needs beside it — power for every recipe, and heat and cold as well for nara's.
 *
 * On a server because the recipes are datapack JSON and the needs are real blocks. The result is never
 * stored, so it is read the way a screen reads it: `execute if items` asks the container's own slot.
 */
@Tags(NEEDS_SERVER)
class CompounderOnServerCheck : FunSpec({
    val server = DrivenServer.shared

    fun at(x: Int) = "$x $COMPOUNDER_Y 0"

    fun build(x: Int, vararg neighbours: Pair<Int, String>) {
        server.run("forceload add 0 0")
        server.run("setblock ${at(x)} agesandtheart:fusion_compounder")
        for ((offset, block) in neighbours) server.run("setblock $x $COMPOUNDER_Y $offset $block")
    }

    fun load(x: Int, slot: Int, item: String, count: Int = 1) {
        server.run("item replace block ${at(x)} container.$slot with $item $count")
    }

    fun offers(x: Int, item: String): Boolean =
        server.run("execute if items block ${at(x)} container.$RESULT_SLOT $item").startsWith("Test passed")

    fun loadNara(x: Int) {
        load(x, 0, "agesandtheart:pitchstone", NARA_EACH)
        load(x, 1, "minecraft:obsidian", NARA_EACH)
        load(x, 2, "minecraft:blackstone", NARA_EACH)
        load(x, 3, "minecraft:purpur_block", NARA_EACH)
    }

    test("a powered compounder offers a block of diamond for a stack of coal blocks") {
        build(POWERED_X, 1 to "agesandtheart:arc_crystal_block")
        load(POWERED_X, 2, "minecraft:coal_block", A_STACK)
        check(offers(POWERED_X, "minecraft:diamond_block")) { "no diamond offered for a stack of coal blocks" }
        val shows = server.run("execute if block ${at(POWERED_X)} agesandtheart:fusion_compounder[power=true]")
        check(shows.startsWith("Test passed")) { "the compounder does not show its power: $shows" }
    }

    test("a recipe a server may switch off runs while its switch is on, as it is by default") {
        build(BEDROCK_X, 1 to "agesandtheart:arc_crystal_block")
        load(BEDROCK_X, 0, "minecraft:netherite_block", A_STACK)
        check(offers(BEDROCK_X, "minecraft:bedrock")) { "no bedrock offered for a stack of netherite blocks" }
    }

    test("without arc crystal beside it, it offers nothing") {
        build(UNPOWERED_X)
        load(UNPOWERED_X, 0, "minecraft:coal_block", A_STACK)
        check(!offers(UNPOWERED_X, "minecraft:diamond_block")) { "an unpowered compounder offered diamond" }
    }

    test("a stack too few, or a slot left over, makes nothing") {
        build(SHORT_X, 1 to "agesandtheart:arc_crystal_block")
        load(SHORT_X, 0, "minecraft:coal_block", A_STACK - 1)
        check(!offers(SHORT_X, "minecraft:diamond_block")) { "sixty-three coal blocks made diamond" }
        load(SHORT_X, 0, "minecraft:coal_block", A_STACK)
        load(SHORT_X, 1, "minecraft:dirt")
        check(!offers(SHORT_X, "minecraft:diamond_block")) { "coal blocks with dirt beside them made diamond" }
    }

    test("nara wants heat and cold beside the machine as well as power") {
        build(NARA_X, 1 to "agesandtheart:arc_crystal_block")
        loadNara(NARA_X)
        check(!offers(NARA_X, "agesandtheart:compounded_stone")) { "nara was offered on power alone" }
        server.run("setblock ${NARA_X + 1} $COMPOUNDER_Y 0 minecraft:magma_block")
        server.run("setblock ${NARA_X - 1} $COMPOUNDER_Y 0 agesandtheart:white_rime_crystal")
        check(offers(NARA_X, "agesandtheart:compounded_stone")) { "nara was not offered with power, heat and cold" }
    }

    test("the ingredients pair off in any slots") {
        build(SHUFFLED_X, 1 to "agesandtheart:arc_crystal_block", -1 to "minecraft:magma_block")
        server.run("setblock $SHUFFLED_X ${COMPOUNDER_Y + 1} 0 agesandtheart:white_rime_crystal")
        load(SHUFFLED_X, 3, "agesandtheart:pitchstone", NARA_EACH)
        load(SHUFFLED_X, 0, "minecraft:purpur_block", NARA_EACH)
        load(SHUFFLED_X, 2, "minecraft:obsidian", NARA_EACH)
        load(SHUFFLED_X, 1, "minecraft:blackstone", NARA_EACH)
        check(offers(SHUFFLED_X, "agesandtheart:compounded_stone")) { "nara depended on which slot held what" }
    }
}) {
    private companion object {
        const val COMPOUNDER_Y = 210
        const val POWERED_X = 2
        const val UNPOWERED_X = 6
        const val SHORT_X = 10
        const val NARA_X = 14
        const val SHUFFLED_X = 18
        const val BEDROCK_X = 22
        const val RESULT_SLOT = 4
        const val A_STACK = 64
        const val NARA_EACH = 16
    }
}
