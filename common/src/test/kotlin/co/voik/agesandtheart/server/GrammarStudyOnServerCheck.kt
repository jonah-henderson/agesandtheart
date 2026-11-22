package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * An empty grammar guide fed by a hopper (`EmptyGrammarGuideBlockEntity`): what arrives is studied, one at a time,
 * and full the guide becomes the grammar guide. Survey reports, at 20 of the 200 points each (`art/grammar_study.json`).
 */
@Tags(NEEDS_SERVER)
class GrammarStudyOnServerCheck : FunSpec({
    val server = DrivenServer.shared

    fun inWorld(command: String) = server.run("execute in minecraft:overworld run $command")

    fun passes(test: String) = inWorld("execute $test").startsWith("Test passed")

    /** An empty guide at [x] with a hopper over it holding [reports] survey reports. */
    fun guideFedBy(x: Int, reports: Int) {
        inWorld("forceload add $x $Z")
        server.waitUntil("the guide's chunk loading") { passes("if loaded $x $Y $Z") }
        inWorld("setblock $x $Y $Z agesandtheart:empty_grammar_guide")
        inWorld(
            "setblock $x ${Y + 1} $Z minecraft:hopper[facing=down]" +
                "{Items:[{Slot:0b,id:\"agesandtheart:sunlit_age_survey\",count:$reports}]}",
        )
    }

    test("what a hopper hands an empty guide is studied") {
        guideFedBy(PART_X, reports = 3)
        server.sprint(FEEDING_TICKS)
        check(passes("if block $PART_X $Y $Z agesandtheart:empty_grammar_guide")) { "three reports filled the guide" }
        check(passes("if data block $PART_X $Y $Z {studied:60}")) { "three reports did not study 60 points" }
    }

    test("a guide a hopper fills becomes the grammar guide") {
        guideFedBy(FULL_X, reports = 12)
        server.sprint(FEEDING_TICKS)
        check(passes("if block $FULL_X $Y $Z agesandtheart:grammar_guide")) { "twelve reports did not fill the guide" }
        check(passes("if data block $FULL_X ${Y + 1} $Z {Items:[{count:2}]}")) { "the hopper did not keep the two left over" }
    }
})

private const val Y = 200
private const val Z = -9000
private const val PART_X = -9000
private const val FULL_X = -9100

/** A hopper moves one item every eight ticks: twelve and some to spare. */
private const val FEEDING_TICKS = 200
