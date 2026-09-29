package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * What a bolt does where it lands — the three answers `Tempest.struck` has to give, on a real server.
 *
 * There is deliberately no check that a rod *near* a blast survives it: a rod is exempt from nothing, and
 * what saves the one a strike lands on is that the explosion never happened.
 *
 * **It has to be a real one.** The blast is `ServerLevel.explode` against real blocks in a real dimension,
 * and the gate that decides whether it happens reads an Age's recipe out of saved data. There is nothing
 * here a pure check could hold.
 *
 * **And a mixin only exists at runtime.** Loom leaves the mixin annotation processor off (26.1 is
 * unobfuscated and there is no refmap to build), so a mistake in `LightningBoltMixin` — a shadow that does
 * not match, an injection point that is not found — compiles perfectly and fails when the class loads.
 * These are what notices.
 *
 * **Every check here that watches something *survive* also watches something else be destroyed.** A bolt
 * that never struck at all leaves the world exactly as an Age with no tempest in it would, so an assertion
 * of the form "this is still standing" is worth nothing on its own — and three of these passed on nothing
 * before the pairing was added. The destruction is the evidence that the world was live and striking.
 *
 * Each check works in a cleared box of its own along one column, far enough apart that neither the blast
 * nor the fire from one can reach the next.
 */
// Two thirds of the server suite between this and its sibling — it waits out real weather over real ticks.
@Tags(NEEDS_SERVER, NEEDS_TIME)
class TempestCheck : FunSpec({
    val server = DrivenServer.shared

    val stormy = "agesandtheart:tempestuous"
    val calm = "agesandtheart:untroubled"
    val glass = "minecraft:glass"
    val rod = "minecraft:lightning_rod"

    fun gameTime(): Long {
        val said = server.run("time query gametime")
        val reading = said.substringAfterLast("is ").substringBefore(" tick").trim().toLongOrNull()
        check(reading != null) { "could not read a game time out of '$said'" }
        return reading
    }

    /**
     * Lets the world run [STRIKE_TICKS] ticks, which is what a summoned bolt needs to reach its strike.
     *
     * **The generosity is the point.** RCON runs a command between ticks, so a check that does not wait
     * asks what a block is milliseconds after summoning the bolt meant to destroy it. Waiting on the bolt
     * entity to disappear reads better and does not work — it is gone within a few ticks either way, so the
     * wait returns at once whether it struck or was never ticked at all.
     */
    fun letItStrike() {
        val struckBy = gameTime() + STRIKE_TICKS
        while (gameTime() < struckBy) Thread.sleep(POLL_MILLIS)
    }

    /** An empty box to work in, so what survives is decided by the blast and not by the rock around it. */
    fun clear(dimension: String, y: Int) {
        server.run("execute in $dimension run fill -4 ${y - 4} -4 4 ${y + 4} 4 minecraft:air")
    }

    fun put(dimension: String, x: Int, y: Int, block: String) {
        server.run("execute in $dimension run setblock $x $y 0 $block")
    }

    /** Strikes the block at [x], the way vanilla's own targeting does — the bolt stands one above it. */
    fun strike(dimension: String, x: Int, y: Int) {
        server.run("execute in $dimension run summon minecraft:lightning_bolt $x.5 ${y + 1} 0.5")
        letItStrike()
    }

    /**
     * Whether [block] is still at [x], [y].
     *
     * The unrecognised answer is an error rather than a `false`, because every one of them — an Age that
     * was never written, a dimension spelled wrong — reads as "the block is gone", which is what half of
     * these checks are hoping to see.
     */
    fun stillThere(dimension: String, x: Int, y: Int, block: String): Boolean {
        val said = server.run("execute in $dimension if block $x $y 0 $block")
        if (said.contains("Test passed")) return true
        check(said.contains("Test failed")) { "asking what is at $x $y 0 in $dimension answered '$said'" }
        return false
    }

    /** A cleared box with a marker at each of [markers], ready to be struck. */
    fun set(dimension: String, y: Int, vararg markers: Pair<Int, String>) {
        clear(dimension, y)
        markers.forEach { (x, block) -> put(dimension, x, y, block) }
    }

    /**
     * Waits until [stormy] is running, which a forceload does not wait for.
     *
     * A chunk comes back forced before it is entity-ticking, and an entity in a chunk that is not ticking
     * never ticks — so the first bolt after a forceload can be swallowed whole, and was, whenever the
     * server was busy enough generating for the other specs. Proved rather than slept over: the dimension
     * is live once a strike in it destroys something.
     */
    fun waitUntilStriking() {
        repeat(WARM_UP_TRIES) {
            set(stormy, WARM_UP_Y, 0 to glass)
            strike(stormy, 0, WARM_UP_Y)
            if (!stillThere(stormy, 0, WARM_UP_Y, glass)) return
        }
        error("$stormy did not strike in $WARM_UP_TRIES tries — its chunk never started ticking")
    }

    beforeSpec {
        server.run("age write tempestuous age tempest")
        server.run("age write untroubled age gentle")
        server.run("execute in $stormy run forceload add 0 0")
        server.run("execute in $calm run forceload add 0 0")
        waitUntilStriking()
    }

    test("a bolt landing in a tempest takes the ground with it") {
        val y = 100
        set(stormy, y, 0 to glass, 2 to glass)
        check(stillThere(stormy, 0, y, glass)) { "the marker was not placed, so this check proves nothing" }

        strike(stormy, 0, y)

        check(!stillThere(stormy, 0, y, glass)) {
            "a bolt came down on the marker in an Age written with a tempest and left it standing — either " +
                "the mixin did not apply, or the blast did not happen"
        }
        // The far marker is what the rod checks below stand on: it is the one they watch survive, so a
        // blast that never reached it would let them pass on a radius rather than on a rod.
        check(!stillThere(stormy, 2, y, glass)) {
            "the blast did not reach two blocks out, so nothing here can tell a rod grounding a strike from " +
                "a blast that was too small to matter"
        }
    }

    test("an Age nobody wrote a tempest into gets vanilla's lightning") {
        val untroubledY = 120
        val controlY = 140
        set(calm, untroubledY, 0 to glass)
        set(stormy, controlY, 0 to glass)

        strike(calm, 0, untroubledY)
        strike(stormy, 0, controlY)

        check(!stillThere(stormy, 0, controlY, glass)) { "the tempest did not strike, so the Age beside it proves nothing" }
        check(stillThere(calm, 0, untroubledY, glass)) {
            "a bolt cratered an Age with no tempest in it — the gate in Happenings.claimFor is not holding, " +
                "and every Age in the game is now explosive"
        }
    }

    test("a lightning rod grounds a strike that lands on it") {
        val guardedY = 160
        val controlY = 180
        set(stormy, guardedY, 0 to rod, 2 to glass)
        set(stormy, controlY, 0 to glass, 2 to glass)

        strike(stormy, 0, guardedY)
        strike(stormy, 0, controlY)

        check(!stillThere(stormy, 2, controlY, glass)) { "the control strike hit nothing, so the rod proves nothing" }
        check(stillThere(stormy, 0, guardedY, rod)) { "the rod the bolt came down on was destroyed" }
        // The far marker is the real assertion: a rod that merely survived its own blast is not a rod that
        // grounded one, and only the second lets a writer keep a base in an Age they wrote a storm into.
        check(stillThere(stormy, 2, guardedY, glass)) {
            "the rod took the strike and the ground beside it still went up — a roof of copper does not " +
                "protect what is under it"
        }
    }

}) {
    private companion object {
        /** Comfortably past the tick a summoned bolt strikes on. */
        const val STRIKE_TICKS = 40L
        const val POLL_MILLIS = 50L

        /** Where the warm-up strikes, clear of every check's box. */
        const val WARM_UP_Y = 80
        const val WARM_UP_TRIES = 5
    }
}
