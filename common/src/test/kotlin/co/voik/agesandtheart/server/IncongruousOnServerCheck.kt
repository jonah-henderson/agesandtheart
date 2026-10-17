package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * **An Age where nothing is where it belongs generates without throwing** — the one thing offline cannot answer,
 * since every structure, feature and creature it moves is a registry entry and every seam it moves them through
 * is a Mixin into vanilla's own worldgen (`notes/strange-words-plan.md` §4). A structure or a feature that throws
 * during generation is caught on a worker thread and abandons the chunk in silence; the log is the only witness.
 */
@Tags(NEEDS_SERVER, NEEDS_TIME)
class IncongruousOnServerCheck : FunSpec({
    val server = DrivenServer.shared

    test("an incongruous Age generates its ground without throwing") {
        val before = server.saidSoFar().length
        server.run("age write nonsenseworld 4242 incongruous age")
        // A forceload returns once its chunks are generated, so whatever generating them threw has been said.
        server.run("execute in agesandtheart:nonsenseworld run forceload add -8 -8 8 8")
        val said = server.saidSoFar().drop(before)
        check(said.isNotEmpty()) { "the server said nothing at all, so this is watching nothing" }
        val thrown = said.lineSequence()
            .filter { "ReportedException" in it || "Exception generating" in it || "Biome decoration" in it }
            .toList()
        check(thrown.isEmpty()) { "an incongruous Age threw while generating:\n  ${thrown.take(3).joinToString("\n  ")}" }
        check("agesandtheart:nonsenseworld" in server.run("age list")) { "the incongruous Age was never written" }
    }
})

