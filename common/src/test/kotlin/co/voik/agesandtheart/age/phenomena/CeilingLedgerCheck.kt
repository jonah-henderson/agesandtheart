package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.age.aspect.Phenomenon
import io.kotest.core.spec.style.FunSpec

/** The phenomena ceiling's line: who begins, who waits, and in what order the waiting are let in. */
class CeilingLedgerCheck : FunSpec({

    val eachAge: (String) -> Any = { it }
    val oneServer: (String) -> Any = { SERVER }

    fun sandfall(age: String) = Episode(age, setOf(Phenomenon.SANDFALL))
    fun meteors(age: String) = Episode(age, setOf(Phenomenon.METEORS))
    fun tectonics(age: String) = Episode(age, setOf(Phenomenon.TECTONICS))
    fun rain(age: String) = Episode(age, setOf(Phenomenon.BLIZZARD, Phenomenon.DELUGE))

    fun CeilingLedger<String>.startIfAdmitted(episode: Episode<String>, ceiling: Int, scopeOf: (String) -> Any) =
        admits(episode, ceiling, scopeOf).also { if (it) begin(episode) }

    test("under the ceiling everything begins, and past it the rest wait") {
        val ledger = CeilingLedger<String>()
        check(ledger.startIfAdmitted(sandfall("a"), 2, eachAge))
        check(ledger.startIfAdmitted(meteors("a"), 2, eachAge))
        check(!ledger.startIfAdmitted(tectonics("a"), 2, eachAge)) { "a third began under a ceiling of two" }
        check(ledger.isWaiting(tectonics("a")))
    }

    test("a ceiling of zero is no ceiling") {
        val ledger = CeilingLedger<String>()
        for (episode in listOf(sandfall("a"), meteors("a"), tectonics("a"), rain("a"))) {
            check(ledger.startIfAdmitted(episode, 0, eachAge)) { "$episode waited with no ceiling" }
        }
    }

    test("each Age has its own ceiling, and the server shares one") {
        val apart = CeilingLedger<String>()
        apart.startIfAdmitted(sandfall("a"), 1, eachAge)
        check(apart.startIfAdmitted(sandfall("b"), 1, eachAge)) { "another Age's sandfall held this one back" }

        val shared = CeilingLedger<String>()
        shared.startIfAdmitted(sandfall("a"), 1, oneServer)
        check(!shared.startIfAdmitted(sandfall("b"), 1, oneServer)) { "two began under one shared place" }
    }

    test("a frequent phenomenon holds one place in the line however often it rolls") {
        val ledger = CeilingLedger<String>()
        ledger.startIfAdmitted(rain("a"), 2, eachAge)
        repeat(50) { ledger.startIfAdmitted(sandfall("a"), 2, eachAge) }
        ledger.startIfAdmitted(meteors("a"), 2, eachAge)
        check(ledger.waitingNow() == listOf(sandfall("a"), meteors("a"))) { "line: ${ledger.waitingNow()}" }
    }

    test("the waiting are let in oldest first, and begin without asking again") {
        val ledger = CeilingLedger<String>()
        ledger.startIfAdmitted(sandfall("a"), 1, eachAge)
        ledger.startIfAdmitted(meteors("a"), 1, eachAge)
        ledger.startIfAdmitted(tectonics("a"), 1, eachAge)
        ledger.end(sandfall("a"))

        val served = ledger.serve(now = 0, ceiling = 1, scopeOf = eachAge, grace = 10)
        check(served == listOf(meteors("a"))) { "served $served" }
        check(ledger.startIfAdmitted(meteors("a"), 1, eachAge)) { "the granted storm was turned away" }
        check(ledger.isWaiting(tectonics("a")))
    }

    test("a running phenomenon stops beginning anew while something waits") {
        val ledger = CeilingLedger<String>()
        ledger.startIfAdmitted(sandfall("a"), 1, eachAge)
        check(ledger.startIfAdmitted(sandfall("a"), 1, eachAge)) { "a lone sandfall could not raise a second column" }
        ledger.startIfAdmitted(meteors("a"), 1, eachAge)
        check(!ledger.startIfAdmitted(sandfall("a"), 1, eachAge)) { "the sandfall went on past a waiting storm" }
    }

    test("the head of the line is not skipped for something cheaper behind it") {
        val ledger = CeilingLedger<String>()
        ledger.startIfAdmitted(sandfall("a"), 2, eachAge)
        ledger.startIfAdmitted(meteors("a"), 2, eachAge)
        ledger.startIfAdmitted(rain("a"), 2, eachAge)
        ledger.startIfAdmitted(tectonics("a"), 2, eachAge)
        ledger.end(sandfall("a"))

        val served = ledger.serve(now = 0, ceiling = 2, scopeOf = eachAge, grace = 10)
        check(served.isEmpty()) { "the rain needs two places and $served took the one that came free" }
    }

    test("an episode costing more than the whole ceiling still runs once its scope is empty") {
        val ledger = CeilingLedger<String>()
        val everything = Episode("a", setOf(Phenomenon.BLIZZARD, Phenomenon.DELUGE, Phenomenon.TEMPEST))
        check(ledger.startIfAdmitted(everything, 2, eachAge)) { "a three-phenomenon storm could never begin" }
    }

    test("a grant not taken up in time loses its turn") {
        val ledger = CeilingLedger<String>()
        ledger.startIfAdmitted(sandfall("a"), 1, eachAge)
        ledger.startIfAdmitted(meteors("a"), 1, eachAge)
        ledger.end(sandfall("a"))
        ledger.serve(now = 0, ceiling = 1, scopeOf = eachAge, grace = 10)
        ledger.serve(now = 11, ceiling = 1, scopeOf = eachAge, grace = 10)
        check(!ledger.isGranted(meteors("a")))
        check(ledger.startIfAdmitted(sandfall("a"), 1, eachAge)) { "an expired grant still held the place" }
    }

    test("an Age forgotten takes its place in the line with it") {
        val ledger = CeilingLedger<String>()
        ledger.startIfAdmitted(sandfall("a"), 1, oneServer)
        ledger.startIfAdmitted(sandfall("b"), 1, oneServer)
        ledger.forget("a")
        check(ledger.serve(now = 0, ceiling = 1, scopeOf = oneServer, grace = 10) == listOf(sandfall("b")))
    }
}) {
    private companion object {
        const val SERVER = "the server"
    }
}
