package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * That a plasma sea takes every block at its level and under it but bedrock, glows only at its skin, fills
 * a hole taken from it and nothing over it, and annihilates what falls in; and that unstable plasma flares
 * out through what it consumes and spares deretheni and bedrock.
 *
 * On a server because the sea is laid by generation in a written Age.
 */
@Tags(NEEDS_SERVER)
class PlasmaOnServerCheck : FunSpec({
    val server = DrivenServer.shared

    fun inAge(command: String) = server.run("execute in agesandtheart:$AGE run $command")
    fun isIn(x: Int, y: Int, z: Int, block: String) = inAge("execute if block $x $y $z $block").startsWith("Test passed")

    fun sprint(ticks: Int) {
        server.run("tick sprint $ticks")
        Thread.sleep(SPRINT_WAIT_MILLIS)
    }

    val surface by lazy {
        server.run("age write $AGE 7 age plasma sea")
        inAge("forceload add 0 0")
        val loaded = (1..LOAD_TRIES).any {
            Thread.sleep(LOAD_WAIT_MILLIS)
            inAge("execute if loaded 0 0 0").startsWith("Test passed")
        }
        check(loaded) { "the plasma Age's origin never loaded" }
        (SCAN_FROM downTo BOTTOM).firstOrNull { y -> isIn(0, y, 0, PLASMA) }
            ?: error("no plasma anywhere in the column at the origin")
    }

    test("the sea is plasma from its surface down to bedrock, in every column alike") {
        for ((x, z) in listOf(0 to 0, 9 to 3, 15 to 15)) {
            check(isIn(x, surface, z, PLASMA)) { "($x, $z) is not plasma at the surface, $surface" }
            check(!isIn(x, surface + 1, z, PLASMA)) { "($x, $z) is plasma over the surface" }
            for (y in listOf(surface - 1, (surface + BOTTOM) / 2, ABOVE_THE_BEDROCK)) {
                check(isIn(x, y, z, PLASMA)) { "($x, $z) is not plasma at $y, under a surface at $surface" }
            }
            check(isIn(x, BOTTOM, z, "minecraft:bedrock")) { "($x, $z) lost its bedrock at $BOTTOM" }
            val bedrockKeptOrConsumed = (BOTTOM + 1..<ABOVE_THE_BEDROCK).all { y -> isIn(x, y, z, PLASMA) || isIn(x, y, z, "minecraft:bedrock") }
            check(bedrockKeptOrConsumed) { "($x, $z) holds something but plasma and bedrock in the bedrock floor" }
        }
    }

    test("where there is land the sea is still whole, and nothing stands within eight of it") {
        server.run("age write $COAST_AGE 7 age gentle landmass plasma sea")
        fun inCoast(command: String) = server.run("execute in agesandtheart:$COAST_AGE run $command")
        inCoast("forceload add -16 -16 31 31")
        val loaded = (1..LOAD_TRIES).any {
            Thread.sleep(LOAD_WAIT_MILLIS)
            inCoast("execute if loaded 31 0 31").startsWith("Test passed") && inCoast("execute if loaded -16 0 -16").startsWith("Test passed")
        }
        check(loaded) { "the coast never loaded" }
        fun isAt(x: Int, y: Int, z: Int, block: String) = inCoast("execute if block $x $y $z $block").startsWith("Test passed")
        fun whatIsAt(x: Int, y: Int, z: Int) = SUSPECTS.firstOrNull { isAt(x, y, z, it) } ?: "something else"
        val notSea = (-16..31).flatMap { x -> (-16..31).map { z -> x to z } }.flatMap { (x, z) ->
            listOf(surface, surface - DEPTH_CHECKED).filter { y -> !isAt(x, y, z, PLASMA) }.map { y -> "($x, $y, $z) ${whatIsAt(x, y, z)}" }
        }
        check(notSea.isEmpty()) { "${notSea.size} places under the surface are not plasma: ${notSea.take(SHOWN * 3)}" }
        val overTheSea = (-16..31 step 3).flatMap { x -> (-16..31 step 3).map { z -> x to z } }.flatMap { (x, z) ->
            (surface + 1..surface + FIELD_REACH).filter { y -> !isAt(x, y, z, "minecraft:air") }.map { y -> "($x, $y, $z) ${whatIsAt(x, y, z)}" }
        }
        check(overTheSea.isEmpty()) { "${overTheSea.size} blocks stand within the field's reach: ${overTheSea.take(SHOWN * 3)}" }
    }

    test("only the sea's skin gives light, and a hole refilled under it comes back dark") {
        check(isIn(0, surface, 0, "$PLASMA[buried=false]")) { "the surface is buried" }
        check(isIn(0, surface - 1, 0, "$PLASMA[buried=true]")) { "the block under the surface is not buried" }
        inAge("setblock 6 ${surface - 3} 6 minecraft:air")
        sprint(REFILL_AND_A_LITTLE)
        check(isIn(6, surface - 3, 6, "$PLASMA[buried=true]")) { "a hole deep in the sea came back lit, or not at all" }
    }

    test("a hole taken from the sea fills again, and nothing over it does") {
        inAge("setblock 2 $surface 2 minecraft:air")
        inAge("setblock 2 ${surface + 1} 2 minecraft:air")
        sprint(REFILL_AND_A_LITTLE)
        check(isIn(2, surface, 2, PLASMA)) { "the hole at the surface did not fill" }
        check(!isIn(2, surface + 1, 2, PLASMA)) { "plasma grew over the surface" }
    }

    test("water poured onto the sea stays on it, and never takes the sea's place") {
        inAge("setblock 12 ${surface + 1} 12 minecraft:water")
        sprint(FLOW_AND_A_LITTLE)
        val replaced = (8..15).flatMap { x -> (8..15).map { z -> x to z } }
            .filter { (x, z) -> !isIn(x, surface, z, PLASMA) }
        check(replaced.isEmpty()) { "water took the sea's place at ${replaced.take(SHOWN)}" }
    }

    fun passTheField() {
        val said = server.run("execute in agesandtheart:$AGE positioned 8 $surface 8 run age plasma pass")
        check("heat passed" in said) { "the field was not passed: $said" }
    }

    test("a creature in the field burns to death, and one over it is set alight") {
        val still = "NoAI:1b,NoGravity:1b,Silent:1b,PersistenceRequired:1b"
        inAge("summon minecraft:pig 13.5 ${surface + 3} 1.5 {$still,Tags:[\"inTheField\"]}")
        inAge("setblock 14 ${surface + SCORCHED_HEIGHT + 2} 1 minecraft:stone")
        inAge("summon minecraft:pig 14.5 ${surface + SCORCHED_HEIGHT} 1.5 {$still,Tags:[\"overTheField\"]}")
        passTheField()
        val alight = inAge("data get entity @e[tag=overTheField,limit=1] Fire")
        val fireTicks = Regex("""(-?\d+)s""").find(alight)?.groupValues?.get(1)?.toInt() ?: 0
        check(fireTicks > 0) { "the creature over the field was not set alight: $alight" }
        sprint(PAST_THE_HURT_COOLDOWN)
        passTheField()
        sprint(DEATH_AND_A_LITTLE)
        val survived = inAge("execute if entity @e[tag=inTheField]")
        check(!survived.startsWith("Test passed")) { "a creature three blocks over the sea survived two passes: $survived" }
    }

    test("the field burns what is in it from the bottom up, and spares deretheni") {
        inAge("setblock 10 ${surface + 1} 0 agesandtheart:pitchstone")
        inAge("setblock 10 ${surface + 2} 0 minecraft:oak_planks")
        inAge("setblock 10 ${surface + 3} 0 minecraft:stone")
        passTheField()
        check(isIn(10, surface + 1, 0, "agesandtheart:pitchstone")) { "the field burned the deretheni" }
        check(isIn(10, surface + 2, 0, "minecraft:air")) { "the lowest burnable block in the field survived a pass" }
        check(isIn(10, surface + 3, 0, "minecraft:stone")) { "the field burned more than the lowest block in a pass" }
        passTheField()
        check(isIn(10, surface + 3, 0, "minecraft:air")) { "the next block up survived the second pass" }
    }

    test("what burns catches fire over the field") {
        inAge("setblock 11 ${surface + SCORCHED_HEIGHT} 2 minecraft:oak_planks")
        val caught = (1..PASSES_TO_CATCH).any {
            passTheField()
            isIn(11, surface + SCORCHED_HEIGHT + 1, 2, "minecraft:fire")
        }
        check(caught) { "planks twelve over the sea never caught in $PASSES_TO_CATCH passes" }
    }

    test("what falls into the sea is gone") {
        inAge("summon minecraft:item 5 ${surface + 3} 5 {Item:{id:\"minecraft:stone\",count:1}}")
        sprint(FALL_AND_A_LITTLE)
        val stillThere = inAge("execute if entity @e[type=minecraft:item,x=5,y=$surface,z=5,distance=..8]")
        check(!stillThere.startsWith("Test passed")) { "an item survived falling into plasma: $stillThere" }
    }

    test("plasma's word reaches the sea and nothing else, and unstable plasma has none") {
        val words = server.run("age words json")
        val plasmaWord = Regex("""\{[^{}]*"word":\s*"plasma"[^{}]*}""").find(words)?.value
        checkNotNull(plasmaWord) { "no word for plasma among: ${words.take(WORDS_SHOWN)}" }
        check(Regex(""""aspects":\s*\["sea"]""").containsMatchIn(plasmaWord)) { "plasma's word reaches more than the sea: $plasmaWord" }
        check("\"unstable_plasma\"" !in words) { "unstable plasma has a word" }
    }

    fun at(x: Int, y: Int, z: Int, block: String) = server.run("execute if block $x $y $z $block").startsWith("Test passed")

    /** Forceloads the overworld chunk holding ([x], [z]) and waits until it is there to build in. */
    fun loaded(x: Int, z: Int) {
        server.run("forceload add $x $z")
        val isThere = (1..LOAD_TRIES).any {
            Thread.sleep(LOAD_WAIT_MILLIS)
            server.run("execute if loaded $x 0 $z").startsWith("Test passed")
        }
        check(isThere) { "the chunk at ($x, $z) never loaded" }
    }

    test("a hanging unit that loses its hold lets the plasma loose, and drops nothing") {
        loaded(RELEASE_X, RELEASE_Z)
        server.run("setblock $RELEASE_X ${ERUPTION_Y + 1} $RELEASE_Z minecraft:stone")
        server.run("setblock $RELEASE_X $ERUPTION_Y $RELEASE_Z agesandtheart:contained_plasma[hanging=true]")
        server.run("setblock ${RELEASE_X + 1} $ERUPTION_Y $RELEASE_Z minecraft:stone")
        server.run("setblock $RELEASE_X ${ERUPTION_Y + 1} $RELEASE_Z minecraft:air")
        sprint(TO_THE_FIRST_BOLTS + 1)
        check(!at(RELEASE_X + 1, ERUPTION_Y, RELEASE_Z, "minecraft:stone")) { "the stone beside the unit stood, so nothing was let loose" }
        val dropped = server.run("execute if entity @e[type=minecraft:item,nbt={Item:{id:\"agesandtheart:contained_plasma\"}}]")
        check(!dropped.startsWith("Test passed")) { "the unit dropped itself: $dropped" }
    }

    test("a unit broken by anything but a player gives nothing back") {
        loaded(0, 0)
        server.run("setblock 6 $UNIT_Y 6 agesandtheart:contained_plasma")
        server.run("setblock 6 $UNIT_Y 7 minecraft:chest")
        server.run("loot insert 6 $UNIT_Y 7 mine 6 $UNIT_Y 6 minecraft:diamond_pickaxe")
        val held = server.run("data get block 6 $UNIT_Y 7 Items")
        check("contained_plasma" !in held) { "mining a unit without a player gave it back: $held" }
    }

    test("contained plasma burns in a furnace and leaves its unit behind") {
        loaded(0, 0)
        server.run("setblock 10 $UNIT_Y 10 minecraft:furnace")
        server.run("item replace block 10 $UNIT_Y 10 container.0 with minecraft:cobblestone")
        server.run("item replace block 10 $UNIT_Y 10 container.1 with agesandtheart:contained_plasma")
        sprint(ONE_SMELT_AND_A_LITTLE)
        val held = server.run("data get block 10 $UNIT_Y 10 Items")
        check("minecraft:stone\"" in held) { "the furnace smelted nothing on plasma: $held" }
        check("agesandtheart:plasma_containment_unit" in held) { "the empty unit was not left in the fuel slot: $held" }
    }

    test("unstable plasma erupts through stone, erases things in its way, and burns out") {
        val x = ERUPTION_X
        val y = ERUPTION_Y
        val z = ERUPTION_Z
        loaded(x, z)
        server.run("setblock ${x + 1} $y $z minecraft:stone")
        server.run("summon minecraft:item ${x + 0.5} $y ${z + 1.5} {NoGravity:1b,Item:{id:\"minecraft:stone\",count:1},Tags:[\"erased\"]}")
        // Frozen and stepped, since a sprint is followed by a wait the clock keeps running through.
        server.run("tick freeze")
        server.run("setblock $x $y $z agesandtheart:unstable_plasma")
        server.run("tick step $TO_THE_FIRST_BOLTS")
        Thread.sleep(STEP_WAIT_MILLIS)
        check(!at(x + 1, y, z, "minecraft:stone")) { "the stone beside the release stood" }
        val stillThere = server.run("execute if entity @e[tag=erased]")
        check(!stillThere.startsWith("Test passed")) { "the item in a bolt's way was not erased: $stillThere" }
        server.run("tick unfreeze")
        sprint(THE_WHOLE_ERUPTION)
        val burning = server.run("fill ${x - 16} ${y - 16} ${z - 16} ${x + 16} ${y + 16} ${z + 16} minecraft:air replace agesandtheart:unstable_plasma")
        check("Successfully" !in burning) { "plasma still burned after the eruption: $burning" }
    }
}) {
    private companion object {
        const val AGE = "plasmacheck"
        const val COAST_AGE = "plasmacoast"
        const val DEPTH_CHECKED = 10
        const val FIELD_REACH = 8
        val SUSPECTS = listOf(
            "minecraft:air", "minecraft:cave_air", "minecraft:water", "minecraft:lava", "minecraft:stone", "minecraft:dirt",
            "minecraft:grass_block", "minecraft:sand", "minecraft:gravel", "minecraft:deepslate", "#minecraft:base_stone_overworld",
            "minecraft:amethyst_block", "minecraft:calcite", "minecraft:smooth_basalt", "#minecraft:logs", "#minecraft:leaves",
        )
        const val PLASMA = "agesandtheart:plasma"
        const val SCAN_FROM = 160
        const val BOTTOM = -64

        /** Vanilla's bedrock floor thins out over its five lowest layers. */
        const val ABOVE_THE_BEDROCK = -59
        const val LOAD_TRIES = 20
        const val LOAD_WAIT_MILLIS = 500L
        const val SPRINT_WAIT_MILLIS = 3_000L
        const val REFILL_AND_A_LITTLE = 40
        const val FALL_AND_A_LITTLE = 60
        const val SCORCHED_HEIGHT = 12
        const val DEATH_AND_A_LITTLE = 30
        const val PAST_THE_HURT_COOLDOWN = 21

        /** Each pass offers a burning block one chance in eight; sixty misses in a row is one in three thousand. */
        const val PASSES_TO_CATCH = 60
        const val FLOW_AND_A_LITTLE = 100
        const val SHOWN = 8
        const val ERUPTION_X = 200
        const val ERUPTION_Z = 200
        const val RELEASE_X = 300
        const val RELEASE_Z = 300
        const val ERUPTION_Y = 150

        /** The release sends out its bolts a tick on, and they reach the block beyond a tick after. */
        const val TO_THE_FIRST_BOLTS = 3
        const val STEP_WAIT_MILLIS = 1_000L
        const val THE_WHOLE_ERUPTION = 80
        const val UNIT_Y = 240
        const val ONE_SMELT_AND_A_LITTLE = 260
        const val WORDS_SHOWN = 2_000
    }
}
