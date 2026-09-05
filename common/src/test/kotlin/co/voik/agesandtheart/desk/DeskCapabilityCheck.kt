package co.voik.agesandtheart.desk

import io.kotest.core.spec.style.FunSpec
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.walk

/**
 * That a capability the desk grants actually gates something.
 *
 * **A capability nobody consults is worse than no capability.** It reads as a feature in the data, an
 * implement is authored to grant it, a player furnishes their study to earn it, and nothing happens —
 * which is the silent acceptance §3.3 forbids, wearing the workshop's clothes rather than the language's.
 *
 * `READABLE_GRAMMAR` was exactly that for as long as it existed: defined, granted by the grammar guide,
 * and read nowhere, so the desk handed over the full reading whatever was in the room. It went unnoticed
 * because nothing was *wrong* — the desk worked, it was simply more generous than the design said. This is
 * what would have said so.
 */
class DeskCapabilityCheck : FunSpec({

    /**
     * The ones that grant nothing **yet**, and why.
     *
     * An entry here is a promise rather than an exemption: it says somebody decided this is not wired and
     * when it will be. An empty map is the goal, and a capability missing from both the map and the code
     * is the bug this spec exists for.
     */
    val notYetWired = mapOf(
        DeskCapability.EDIT_BOOKS to "Phase 8's gate, deliberately inert so the implement is findable first",
        DeskCapability.SURVEY_MATERIALS to
            "Phase 7 step 2's last piece. The evaluator behind it is built and checked; what is missing is " +
            "the readout, which is the desk screen's, and the desk screen is out of room",
    )

    test("every capability the desk grants is consulted somewhere") {
        val roots = listOf(Path.of("src/main/kotlin"), Path.of("../fabric/src/main/kotlin"))
            .filter { it.isDirectory() }
        check(roots.isNotEmpty()) {
            "found no Kotlin source under ${Path.of("").toAbsolutePath()} — this check would pass on nothing"
        }
        val source = roots.flatMap { root ->
            root.walk().filter { it.extension == "kt" }.map { it.readText() }
        }

        val unread = DeskCapability.entries.filter { capability ->
            if (capability in notYetWired) return@filter false
            // Its own declaration does not count as a use, so the name has to appear somewhere that is not
            // the enum body — which is what "something asks for it" looks like from here.
            val mentions = source.sumOf { text -> Regex("\\b${capability.name}\\b").findAll(text).count() }
            mentions <= 1
        }

        check(unread.isEmpty()) {
            "these are granted and never consulted, so furnishing a desk for them changes nothing: " +
                "${unread.map { it.key }} — wire each one, or delete it, or say here when it lands"
        }
    }

    /** And the reverse: a promise here has to be about something that exists. */
    test("nothing is excused that is not a capability") {
        val strays = notYetWired.keys.filterNot { it in DeskCapability.entries }
        check(strays.isEmpty()) { "excused capabilities that do not exist: $strays" }
    }
})
