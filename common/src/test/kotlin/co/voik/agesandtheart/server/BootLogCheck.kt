package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * That the server starts without complaining about anything we ship.
 *
 * **A datapack file the server cannot read is logged, not fatal.** The boot carries on, the recipe is simply
 * absent, the loot modifier simply never fires, and no command can be asked a question that reveals it. So
 * two broken files sat in the NeoForge build being reported on every single boot and caught by nothing:
 * `DrivenServer` discarded the server's output on the reasoning that it writes its own log, which was true
 * and meant no check could see it.
 *
 * This is that gap closed. It reads the boot rather than driving it, and it is deliberately narrow — it
 * fails on the server failing to *read* something, which is always our fault, and says nothing about
 * warnings, which are frequently not.
 */
@Tags(NEEDS_SERVER)
class BootLogCheck : FunSpec({
    val server = DrivenServer.shared

    /** What the server says when it cannot read a file — the whole family, from one listener. */
    val couldNotRead = listOf(
        "Couldn't parse data file",
        "Failed to load recipe",
        "Failed to parse loot table",
        "Couldn't load loot table",
    )

    test("nothing we ship failed to parse") {
        val said = server.saidSoFar()
        check(said.isNotEmpty()) {
            "The boot log was empty, so this check is watching nothing. `DrivenServer` is meant to keep the " +
                "server's output — see `LaunchSpec.start`"
        }

        val complaints = said.lineSequence()
            .filter { line -> couldNotRead.any { it in line } }
            // Ours, not another mod's: a dependency shipping a file this loader dislikes is not our bug.
            .filter { line -> "agesandtheart" in line || "ephemeris" in line }
            .toList()

        check(complaints.isEmpty()) {
            "The server could not read ${complaints.size} of the files we ship:\n" +
                complaints.joinToString("\n") { "  $it" }
        }
    }

    test("no mixin of ours failed to apply") {
        // A mixin that misses its target is logged and the game continues without it — the same shape of
        // silent failure, and the one that would take a sky renderer out entirely.
        val failures = server.saidSoFar().lineSequence()
            .filter { "Mixin apply" in it || "was not found" in it }
            .filter { "agesandtheart" in it || "ephemeris" in it }
            .toList()
        check(failures.isEmpty()) {
            "${failures.size} mixin(s) of ours did not apply:\n" + failures.joinToString("\n") { "  $it" }
        }
    }
})
