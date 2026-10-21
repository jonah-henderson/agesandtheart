package co.voik.agesandtheart.client.ui

import io.kotest.core.spec.style.FunSpec
import kotlin.math.abs

/** The scrollbar's knob stays under the pointer that holds it, wherever it was taken hold of. */
class ContainerScrollbarCheck : FunSpec({

    val track = Rect(0, 20, ContainerScrollbar.WIDTH, 100)
    val travel = track.height - 2 - ContainerScrollbar.KNOB_HEIGHT

    test("a knob dragged moves exactly as far as the pointer, one track of travel to the whole list") {
        val scrollbar = ContainerScrollbar()
        val grabbed = track.y + 1 + 4.0
        val start = scrollbar.press(track, 0.0, grabbed)
        check(start == 0.0) { "taking hold of the knob at the top moved it to $start" }
        val moved = scrollbar.drag(track, grabbed + travel / 2.0)
        check(moved != null && abs(moved - 0.5) < 1e-9) { "half the travel dragged moved it to $moved" }
        check(scrollbar.drag(track, grabbed + travel * 3.0) == 1.0) { "a drag past the end went past it" }
        scrollbar.release()
        check(scrollbar.drag(track, grabbed) == null) { "a released knob still followed the pointer" }
    }

    test("pressing the track brings the knob's middle to the pointer") {
        val scrollbar = ContainerScrollbar()
        val middle = track.y + 1 + travel / 2.0 + ContainerScrollbar.KNOB_HEIGHT / 2.0
        val at = scrollbar.press(track, 0.0, middle)
        check(abs(at - 0.5) < 1e-9) { "pressing the middle of the track put the knob at $at" }
    }
})
