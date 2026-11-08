package co.voik.agesandtheart.age.phenomena

import io.kotest.core.spec.style.FunSpec

/**
 * A tornado's winds go round anticlockwise seen from above, as its funnel turns, turn inward more the nearer the
 * funnel, and grow towards it from nothing at their outer reach.
 */
class TornadoWindsCheck : FunSpec({

    val vortex = TornadoWinds.Vortex(x = 0.0, footY = 64.0, z = 0.0, core = 6.0, top = 182.0, might = 1.0)

    test("east of the foot, the air goes north and in, as an anticlockwise turn seen from above does") {
        val flow = vortex.flowAt(vortex.bearingsOf(vortex.core * 2, 0.0))
        check(flow.towardsZ < 0.0) { "east of the foot the air goes ${flow.towardsZ} along z, not north" }
        check(flow.towardsX < 0.0) { "east of the foot the air goes ${flow.towardsX} along x, not inward" }
    }

    test("the air turns further inward, and blows harder, the nearer the funnel") {
        val far = vortex.flowAt(vortex.bearingsOf(vortex.outerReach * 0.9, 0.0))
        val near = vortex.flowAt(vortex.bearingsOf(vortex.core * 1.1, 0.0))
        check(-near.towardsX > -far.towardsX) { "inward ${-near.towardsX} near the funnel, ${-far.towardsX} far out" }
        check(near.strength > far.strength) { "${near.strength} near the funnel, ${far.strength} far out" }
    }

    test("the winds die away to nothing at their outer reach") {
        val edge = vortex.flowAt(vortex.bearingsOf(vortex.outerReach, 0.0))
        check(edge.strength == 0.0) { "${edge.strength} still blowing at the outer reach" }
    }
})
