package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * That `/age showing rainbow` answers, and answers about the right thing.
 *
 * A bow is drawn on the client from arithmetic the client does for itself, so a walk that sees none cannot
 * tell an Age with no bow from a day it does not come from a light standing too high from a renderer that
 * is broken. Four faults, one symptom — which is exactly why the command exists, and why a command that
 * silently answered nothing would be worse than no command.
 *
 * The eyes still decide what it looks like. This only says the lever is connected and the arithmetic
 * reaches the server's own reckoning of the sky.
 */
@Tags(NEEDS_SERVER)
class RainbowCommandCheck : FunSpec({
    val server = DrivenServer.shared

    val bowed = "bowage"
    val bare = "bowless"

    beforeSpec {
        server.run("age write $bowed 7 rainbows age")
        server.run("age compose $bare 5 landmass=gentle sea=minecraft:water")
    }

    test("an Age with no bow says so, and says how to get one") {
        val report = server.run("execute in agesandtheart:$bare run age showing rainbow")
        check("Nothing writes a bow" in report) { "An Age with no bow did not say so:\n$report" }
        check("rainbows" in report) { "It did not say which word writes one:\n$report" }
    }

    test("an Age with one reports every factor rather than a single verdict") {
        // The aurora's hard-won rule: a number that is nought says only that something is, which is the one
        // thing already known by the time anybody asks. Each factor has to be separately readable.
        val report = server.run("execute in agesandtheart:$bowed run age showing rainbow")
        for (factor in listOf("outermost first", "of days", "light", "stands at", "geometry allows")) {
            check(factor in report) { "'$factor' was missing, so a reader cannot tell which factor is at nought:\n$report" }
        }
    }

    test("the report says where the light stands against the radius the bow needs") {
        // The failure with no counterpart in a curtain: a bow whose light is too high is correct, complete
        // and entirely underground, and nothing else in the sky behaves that way.
        val report = server.run("execute in agesandtheart:$bowed run age showing rainbow")
        check(Regex("""stands at\s+-?\d+°""").containsMatchIn(report)) {
            "No light altitude was reported, so the commonest reason for an empty sky is invisible:\n$report"
        }
        check("needs one under" in report) { "The radius the light must be under was not said:\n$report" }
    }

    test("it answers in JSON too") {
        // The structured form is one message, and every fact has to survive the crossing — a report that
        // only works in prose is one no check can ever read.
        val report = server.run("execute in agesandtheart:$bowed run age showing json rainbow")
        for (key in listOf("today", "highest", "cast", "frequency", "lights")) {
            check("\"$key\"" in report) { "The JSON report carried no '$key':\n$report" }
        }
    }

    test("`now` brings one on, and says which way to look") {
        val report = server.run("execute in agesandtheart:$bowed run age showing rainbow now")
        check("no rain" in report || "needing no rain" in report) { "`now` did not say it took the rain off:\n$report" }
        check("back" in report) { "`now` did not say where to stand looking:\n$report" }
    }

    test("`now` on an Age with no bow fails rather than pretending") {
        val report = server.run("execute in agesandtheart:$bare run age showing rainbow now")
        check("Nothing writes a bow" in report) { "`now` invented a bow for an Age that has none:\n$report" }
    }
})
