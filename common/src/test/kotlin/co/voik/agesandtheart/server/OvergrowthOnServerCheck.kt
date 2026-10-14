package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * That an overgrowth eats what it touches but not what is held out of its reach, that a fire holds it back
 * and burns its thorns, that a cut thorn chain falls away, and that what stands on it survives (design
 * §5.2.2).
 *
 * On a server because spreading is the world's own random tick, and a thorn's hold on its chain is the
 * world's own neighbour updates.
 */
@Tags(NEEDS_SERVER)
class OvergrowthOnServerCheck : FunSpec({
    val server = DrivenServer.shared

    fun inWorld(command: String) = server.run("execute in minecraft:overworld run $command")
    fun isAt(x: Int, y: Int, z: Int, block: String) = inWorld("execute if block $x $y $z $block").startsWith("Test passed")

    fun sprint(ticks: Int) {
        server.run("tick sprint $ticks")
        Thread.sleep(SPRINT_WAIT_MILLIS)
    }

    fun tickingFast(body: () -> Unit) {
        server.run("gamerule spawn_chunk_radius 0")
        server.run("gamerule random_tick_speed $FAST")
        try {
            body()
        } finally {
            server.run("gamerule random_tick_speed $ORDINARY")
            server.run("gamerule spawn_chunk_radius $ORDINARY_SPAWN_RADIUS")
        }
    }

    val loaded by lazy {
        inWorld("forceload add 0 0 31 31")
        val loaded = (1..LOAD_TRIES).any {
            Thread.sleep(LOAD_WAIT_MILLIS)
            inWorld("execute if loaded 31 $PLATFORM_Y 31").startsWith("Test passed")
        }
        check(loaded) { "the platform's chunks never loaded" }
    }

    /** A bare stone floor with nothing on it, so one test's growth cannot reach another's. */
    fun freshGround(): Int {
        loaded
        inWorld("fill 0 ${PLATFORM_Y + 1} 0 31 ${PLATFORM_Y + CLEARED_HEIGHT} 31 minecraft:air")
        inWorld("fill 0 $PLATFORM_Y 0 31 $PLATFORM_Y 31 minecraft:stone")
        inWorld("kill @e[type=minecraft:item]")
        return PLATFORM_Y + 1
    }

    test("it eats stone and obsidian and water, and leaves reinforced deepslate and bedrock") {
        val y = freshGround()
        inWorld("setblock 8 $y 8 $OVERGROWTH")
        inWorld("setblock 9 $y 8 minecraft:obsidian")
        inWorld("setblock 7 $y 8 minecraft:reinforced_deepslate")
        inWorld("setblock 8 $y 9 minecraft:water")
        inWorld("setblock 8 $y 7 minecraft:bedrock")
        tickingFast { sprint(SPREAD_TICKS) }
        check(!isAt(9, y, 8, "minecraft:obsidian")) { "obsidian stood beside an overgrowth and was not eaten" }
        check(!isAt(8, y, 9, "minecraft:water")) { "water stood beside an overgrowth and was not eaten" }
        check(!isAt(8, PLATFORM_Y, 8, "minecraft:stone")) { "the stone under it was not eaten" }
        check(isAt(7, y, 8, "minecraft:reinforced_deepslate")) { "reinforced deepslate was eaten" }
        check(isAt(8, y, 7, "minecraft:bedrock")) { "bedrock was eaten" }
    }

    test("a chest it eats spills what was in it") {
        val y = freshGround()
        inWorld("setblock 20 $y 20 $OVERGROWTH")
        inWorld("setblock 21 $y 20 minecraft:chest{Items:[{Slot:0b,id:\"minecraft:diamond\",count:3}]}")
        tickingFast { sprint(SPREAD_TICKS) }
        check(!isAt(21, y, 20, "minecraft:chest")) { "the chest was not eaten" }
        val spilled = inWorld("execute if entity @e[type=minecraft:item,x=21,y=$y,z=20,distance=..8,nbt={Item:{id:\"minecraft:diamond\"}}]")
        check(spilled.startsWith("Test passed")) { "eating the chest lost what it held: $spilled" }
    }

    test("a fire within reach stops it spreading at all") {
        val y = freshGround()
        inWorld("setblock 24 $y 24 $OVERGROWTH")
        inWorld("setblock 26 $y 24 minecraft:campfire[lit=true]")
        tickingFast { sprint(SPREAD_TICKS) }
        val eaten = listOf(23 to 24, 25 to 24, 24 to 23, 24 to 25).filter { (x, z) -> !isAt(x, y, z, "minecraft:air") }
        check(eaten.isEmpty()) { "it spread beside a lit campfire, at $eaten" }
        check(isAt(24, PLATFORM_Y, 24, "minecraft:stone")) { "it ate the stone beside a lit campfire" }
        check(isAt(24, y, 24, OVERGROWTH)) { "a fire removed the overgrowth itself" }
    }

    test("a thorn beside a fire burns, and the overgrowth under it does not") {
        val y = freshGround()
        inWorld("setblock 28 $y 10 $OVERGROWTH")
        inWorld("setblock 28 ${y + 1} 10 $THORNS[anchor=down,heading=up,reach=1]")
        inWorld("setblock 29 ${y + 1} 10 minecraft:campfire[lit=true]")
        tickingFast { sprint(BURN_TICKS) }
        check(!isAt(28, y + 1, 10, THORNS)) { "a thorn beside a lit campfire survived" }
        check(isAt(28, y, 10, OVERGROWTH)) { "the fire took the overgrowth with the thorn" }
    }

    test("a thorn chain with its root cut away falls away, and the part still attached stays") {
        val y = freshGround()
        inWorld("setblock 12 $y 28 $OVERGROWTH")
        inWorld("setblock 12 ${y + 1} 28 $THORNS[anchor=down,heading=up,reach=1]")
        inWorld("setblock 12 ${y + 2} 28 $THORNS[anchor=down,heading=up,reach=2]")
        sprint(SETTLE_TICKS)
        check(isAt(12, y + 2, 28, THORNS)) { "an anchored chain fell away of its own accord" }
        inWorld("setblock 12 $y 28 minecraft:air")
        sprint(SETTLE_TICKS)
        check(!isAt(12, y + 1, 28, THORNS)) { "the segment on the cut root stayed" }
        check(!isAt(12, y + 2, 28, THORNS)) { "the segment past the cut stayed" }
    }

    test("a plant stands on an overgrowth, and falls where it has no ground") {
        val y = freshGround()
        inWorld("setblock 28 $y 28 $OVERGROWTH")
        inWorld("setblock 28 ${y + 1} 28 minecraft:wheat[age=0]")
        inWorld("setblock 28 ${y + 1} 31 minecraft:wheat[age=0]")
        // A plant is only asked whether it can stand when something beside it changes.
        inWorld("setblock 29 ${y + 1} 28 minecraft:stone")
        inWorld("setblock 29 ${y + 1} 31 minecraft:stone")
        sprint(SETTLE_TICKS)
        check(isAt(28, y + 1, 28, "minecraft:wheat")) { "wheat could not stand on an overgrowth" }
        check(!isAt(28, y + 1, 31, "minecraft:wheat")) { "wheat stood on bare stone, so this check proves nothing" }
    }

    test("a crop on an overgrowth ripens in about twenty seconds") {
        val y = freshGround()
        inWorld("setblock 20 $y 20 $OVERGROWTH")
        inWorld("setblock 20 ${y + 1} 20 minecraft:wheat[age=0]")
        // A fire holds the spreading back, so what is under test is the ripening alone.
        inWorld("setblock 22 ${y + 1} 20 minecraft:campfire[lit=true]")
        sprint(RIPEN_TICKS)
        check(isAt(20, y + 1, 20, "minecraft:wheat[age=7]")) { "the wheat had not ripened" }
    }

    test("a sapling on an overgrowth is given bone meal until it is a tree") {
        val y = freshGround()
        inWorld("setblock 8 $y 20 $OVERGROWTH")
        inWorld("setblock 8 ${y + 1} 20 minecraft:oak_sapling")
        inWorld("setblock 11 ${y + 1} 20 minecraft:campfire[lit=true]")
        sprint(SAPLING_TICKS)
        check(isAt(8, y + 1, 20, "minecraft:oak_log")) { "the sapling had not become a tree" }
    }

    test("a mushroom in full daylight is given bone meal until it is huge") {
        val y = freshGround()
        inWorld("setblock 4 $y 20 $OVERGROWTH")
        inWorld("setblock 4 ${y + 1} 20 minecraft:red_mushroom")
        inWorld("setblock 7 ${y + 1} 20 minecraft:campfire[lit=true]")
        sprint(SAPLING_TICKS)
        check(isAt(4, y + 1, 20, "minecraft:mushroom_stem")) { "the mushroom survived in daylight but did not grow huge" }
    }

    test("a sapling under a stone roof still becomes a tree") {
        val y = freshGround()
        inWorld("fill 2 ${y + ROOF_HEIGHT} 14 14 ${y + ROOF_HEIGHT} 26 minecraft:stone")
        inWorld("setblock 8 $y 20 $OVERGROWTH")
        inWorld("setblock 8 ${y + 1} 20 minecraft:oak_sapling")
        inWorld("setblock 11 ${y + 1} 20 minecraft:campfire[lit=true]")
        sprint(SAPLING_TICKS)
        check(isAt(8, y + 1, 20, "minecraft:oak_log")) { "the sapling did not grow in the dark" }
    }

    test("an overgrowth hangs vines of other kinds as well as thorns") {
        val y = freshGround()
        // A plate in mid-air, so there are free cells under it, beside it and over it for each kind to try.
        inWorld("fill 12 ${y + 6} 12 18 ${y + 6} 18 $OVERGROWTH")
        sprint(VINE_TICKS)
        val kinds = listOf("minecraft:vine", "minecraft:cave_vines", "minecraft:cave_vines_plant", "minecraft:weeping_vines", "minecraft:weeping_vines_plant", "minecraft:twisting_vines", "minecraft:twisting_vines_plant")
        val hung = kinds.sumOf { kind ->
            val said = inWorld("fill 8 ${y + 1} 8 22 ${y + 12} 22 minecraft:air replace $kind")
            Regex("filled (\\d+) block").find(said)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }
        check(hung > 0) { "no vine of another kind grew on the plate" }
    }

    fun count(block: String, from: String, to: String): Int {
        val said = inWorld("fill $from $to minecraft:air replace $block")
        return Regex("filled (\\d+) block").find(said)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    test("a thorn chain that keeps to a wall grows past the length an unsupported one is held to") {
        val y = freshGround()
        // A one-block shaft through solid stone, so every cell of the chain has a wall beside it.
        inWorld("fill 23 $y 23 25 ${y + SHAFT} 25 minecraft:stone")
        inWorld("fill 24 ${y + 1} 24 24 ${y + SHAFT} 24 minecraft:air")
        inWorld("setblock 24 $y 24 $OVERGROWTH")
        inWorld("setblock 24 ${y + 1} 24 $THORNS[anchor=down,heading=up,reach=0]")
        sprint(GROWTH_TICKS)
        val grown = count(THORNS, "24 ${y + 1} 24", "24 ${y + SHAFT} 24")
        check(grown > MOST_UNSUPPORTED) { "a chain beside a wall grew only $grown segments" }
    }

    test("a thorn chain with nothing beside it stops short") {
        val y = freshGround()
        inWorld("setblock 8 ${y + 4} 8 $OVERGROWTH")
        inWorld("setblock 8 ${y + 5} 8 $THORNS[anchor=down,heading=up,reach=0]")
        sprint(GROWTH_TICKS)
        val tooHigh = count(THORNS, "4 ${y + 4 + MOST_UNSUPPORTED + 2} 4", "12 ${y + 4 + MOST_UNSUPPORTED + 2} 12")
        check(tooHigh == 0) { "an unsupported chain climbed past its limit: $tooHigh segments out there" }
    }

    test("an overgrowth drops nothing, with silk touch or without") {
        val y = freshGround()
        inWorld("setblock 24 $y 24 $OVERGROWTH")
        for (tool in listOf("minecraft:diamond_shovel", "minecraft:diamond_shovel[enchantments={\"minecraft:silk_touch\":1}]")) {
            val said = inWorld("loot spawn 24 ${y + 3} 24 mine 24 $y 24 $tool")
            check(!said.contains("Dropped")) { "an overgrowth mined with $tool dropped something: $said" }
        }
    }
}) {
    private companion object {
        const val LOAD_TRIES = 20
        const val LOAD_WAIT_MILLIS = 500L
        const val SPRINT_WAIT_MILLIS = 3_000L
        const val PLATFORM_Y = 200
        const val OVERGROWTH = "agesandtheart:overgrowth"
        const val THORNS = "agesandtheart:thorn_vine"
        const val FAST = 300
        const val ORDINARY = 3
        const val ORDINARY_SPAWN_RADIUS = 2
        const val SPREAD_TICKS = 400
        const val BURN_TICKS = 100
        const val SETTLE_TICKS = 40
        const val RIPEN_TICKS = 460
        const val SAPLING_TICKS = 1500
        const val VINE_TICKS = 800
        const val GROWTH_TICKS = 2500
        const val SHAFT = 14
        const val MOST_UNSUPPORTED = 8
        const val CLEARED_HEIGHT = 16
        const val ROOF_HEIGHT = 12
    }
}
