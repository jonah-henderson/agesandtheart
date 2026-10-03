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

    fun build(x: Int, vararg neighbours: Pair<Int, String>, repaired: Boolean = true) {
        // Each test's own chunk, which must tick for a signal to be answered.
        server.run("forceload add $x 0")
        server.run("setblock ${at(x)} agesandtheart:fusion_compounder[facing=east,repaired=$repaired]")
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

    fun signal(x: Int) {
        server.run("setblock $x ${COMPOUNDER_Y + 1} 0 minecraft:redstone_block")
        server.run("tick sprint $A_FEW_TICKS")
        Thread.sleep(SPRINT_WAIT_MILLIS)
    }

    test("a signal compounds into the container in front, and the inputs are spent for it") {
        build(INTO_A_CHEST_X, 1 to "agesandtheart:arc_crystal_block")
        val chest = "${INTO_A_CHEST_X + 1} $COMPOUNDER_Y 0"
        server.run("setblock $chest minecraft:chest")
        load(INTO_A_CHEST_X, 0, "minecraft:coal_block", A_STACK)
        signal(INTO_A_CHEST_X)
        val passed = server.untilPasses("execute if items block $chest container.0 minecraft:diamond_block[count=1]")
        val inChest = server.run("data get block $chest Items")
        check(passed.startsWith("Test passed")) { "the chest in front was not given a diamond block: $inChest" }
        val spent = server.run("execute if items block ${at(INTO_A_CHEST_X)} container.* minecraft:coal_block")
        check(!spent.startsWith("Test passed")) { "the coal blocks were not spent for what was made: $spent" }
    }

    test("a compounder found broken compounds nothing, however it is powered and signalled") {
        build(BROKEN_X, 1 to "agesandtheart:arc_crystal_block", repaired = false)
        val chest = "${BROKEN_X + 1} $COMPOUNDER_Y 0"
        server.run("setblock $chest minecraft:chest")
        load(BROKEN_X, 0, "minecraft:coal_block", A_STACK)
        signal(BROKEN_X)
        val inChest = server.run("data get block $chest Items")
        check("diamond_block" !in inChest) { "a broken compounder made a diamond block: $inChest" }
        val kept = server.run("execute if items block ${at(BROKEN_X)} container.0 minecraft:coal_block[count=$A_STACK]")
        check(kept.startsWith("Test passed")) { "a broken compounder spent its inputs: $kept" }
    }

    test("with nothing in front, a signal throws the result out onto the floor") {
        build(THROWN_X, 1 to "agesandtheart:arc_crystal_block")
        // A floor to land on: the compounder stands in the open air, and a thrown block would fall far.
        server.run("fill ${THROWN_X + 1} ${COMPOUNDER_Y - 1} -2 ${THROWN_X + NEAR_ENOUGH} ${COMPOUNDER_Y - 1} 2 minecraft:stone")
        load(THROWN_X, 0, "minecraft:coal_block", A_STACK)
        signal(THROWN_X)
        val near = "x=$THROWN_X,y=$COMPOUNDER_Y,z=0,distance=..$NEAR_ENOUGH"
        val thrown = server.untilPasses("execute if entity @e[type=minecraft:item,$near,nbt={Item:{id:\"minecraft:diamond_block\"}}]")
        check(thrown.startsWith("Test passed")) { "no diamond block was thrown out of the front: $thrown" }
    }

    test("a hopper beneath takes nothing out, and without a signal nothing is made") {
        build(HOPPER_X, 1 to "agesandtheart:arc_crystal_block")
        val hopper = "$HOPPER_X ${COMPOUNDER_Y - 1} 0"
        server.run("setblock $hopper minecraft:hopper")
        load(HOPPER_X, 0, "minecraft:coal_block", A_STACK)
        server.run("tick sprint $A_FEW_HOPPER_BEATS")
        Thread.sleep(SPRINT_WAIT_MILLIS)
        val kept = server.run("execute if items block ${at(HOPPER_X)} container.0 minecraft:coal_block[count=$A_STACK]")
        check(kept.startsWith("Test passed")) { "the coal blocks went somewhere without a signal: $kept" }
        val inHopper = server.run("data get block $hopper Items")
        check(inHopper.endsWith("[]")) { "the hopper beneath took something out: $inHopper" }
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
        const val INTO_A_CHEST_X = 26
        const val THROWN_X = 32
        const val HOPPER_X = 38
        const val BROKEN_X = 44
        const val RESULT_SLOT = 4

        /** Past a crafter's four-tick delay. */
        const val A_FEW_TICKS = 10

        /** Several of a hopper's eight-tick beats. */
        const val A_FEW_HOPPER_BEATS = 40

        /** A thrown item's flight and fall in those few ticks. */
        const val NEAR_ENOUGH = 6

        /** A sprint runs off the command's thread; forty ticks of an idle server take well under this. */
        const val SPRINT_WAIT_MILLIS = 1500L
        const val A_STACK = 64
        const val NARA_EACH = 16
    }
}
