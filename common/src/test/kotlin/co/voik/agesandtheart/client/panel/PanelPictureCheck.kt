package co.voik.agesandtheart.client.panel

import io.kotest.core.spec.style.FunSpec

/**
 * What a panel's picture is made of — the one description a book screen and a lectern both show, so a fault
 * here would show in both at once.
 */
class PanelPictureCheck : FunSpec({

    val haze = 0xFF405060.toInt()

    fun ageAt(unsettled: Float, hasOlderField: Boolean = true) =
        AgeInView(haze = haze, shot = 7, unsettled = unsettled, hasOlderField = hasOlderField)

    fun fieldsOf(age: AgeInView) = PanelDistortion.strokes(age).filterIsInstance<PanelStroke.Field>()

    /** How many of [strokes] cover each page-pixel of the panel. */
    fun coverageOf(strokes: List<PanelStroke>): Array<IntArray> {
        val counts = Array(PanelPicture.HEIGHT) { IntArray(PanelPicture.WIDTH) }
        for (stroke in strokes) {
            for (y in stroke.rect.top..<stroke.rect.bottom) {
                for (x in stroke.rect.left..<stroke.rect.right) counts[y][x]++
            }
        }
        return counts
    }

    test("a coherent Age is one field, whole") {
        val whole = PanelStroke.Field(PanelPicture.WHOLE, PanelField.NEWEST, 0.0f, 1.0f, 1.0f, 0.0f)
        check(fieldsOf(ageAt(0.0f)) == listOf(whole))
    }

    for (unsettled in listOf(0.1f, 0.5f, 1.0f)) {
        test("a torn Age's bands cover every page-pixel exactly once, at $unsettled") {
            val counts = coverageOf(fieldsOf(ageAt(unsettled)))
            val bare = counts.sumOf { row -> row.count { it == 0 } }
            val laidTwice = counts.sumOf { row -> row.count { it > 1 } }
            check(bare == 0 && laidTwice == 0) { "$bare page-pixels bare and $laidTwice laid twice" }
        }
    }

    test("with no older field yet, every band takes the newest") {
        check(fieldsOf(ageAt(1.0f, hasOlderField = false)).all { it.field == PanelField.NEWEST })
    }

    test("the frame goes down first and the mist last") {
        val strokes = PanelPicture.strokes(ageAt(0.5f), mist = 0.3f)
        val frame = strokes.first()
        check(frame is PanelStroke.Fill && frame.rect.left < 0) { "the first stroke is $frame, not the frame" }
        check(strokes.last() == PanelStroke.Mist(PanelPicture.WHOLE, 0.3f)) { "the last stroke is ${strokes.last()}" }
    }

    test("a whole ring leaves no mist, and no picture leaves no Age") {
        check(PanelPicture.strokes(ageAt(0.5f), mist = 0.0f).none { it is PanelStroke.Mist })
        check(PanelPicture.strokes(age = null, mist = 1.0f).none { it is PanelStroke.Field })
    }
})
