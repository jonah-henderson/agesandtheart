package co.voik.agesandtheart.book

import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.Direction
import net.minecraft.world.phys.Vec3

/** Which of a bookstand's places a click lands on, counted from the left of whoever faces its spines. */
class BookstandPlacesCheck : FunSpec({

    /** A point on the stand's front edge, [across] blocks from the left end of a stand whose spines face [facing]. */
    fun clickedAt(facing: Direction, across: Double): Vec3 {
        val toTheRight = facing.counterClockWise
        val leftEnd = Vec3(0.5, 0.5, 0.5).add(Vec3(-toTheRight.stepX * 0.5, 0.0, -toTheRight.stepZ * 0.5))
        return leftEnd.add(Vec3(toTheRight.stepX * across, 0.0, toTheRight.stepZ * across)).add(facing.stepX * 0.25, 0.0, facing.stepZ * 0.25)
    }

    test("the places run from the reader's left, whichever way the spines face") {
        for (facing in Direction.Plane.HORIZONTAL) {
            for (place in 0 until BookstandPlaces.COUNT) {
                val middle = BookstandPlaces.middleOf(place, 1) / 16.0
                val found = BookstandPlaces.placeAt(BookstandPlaces.acrossOf(facing, clickedAt(facing, middle)))
                check(found == place) { "facing $facing, the middle of place $place is found as place $found" }
            }
        }
    }

    test("facing south, the reader's left is west") {
        val nearTheWestEnd = BookstandPlaces.placeAt(BookstandPlaces.acrossOf(Direction.SOUTH, Vec3(0.2, 0.5, 0.9)))
        check(nearTheWestEnd == 0) { "the west end is place $nearTheWestEnd" }
    }

    test("a click past a guard is the nearest place") {
        check(BookstandPlaces.placeAt(0.5) == 0)
        check(BookstandPlaces.placeAt(15.5) == BookstandPlaces.COUNT - 1)
    }

    test("a descriptive book stands in the pair its place is one of, and a linking book in its place alone") {
        check((0 until BookstandPlaces.COUNT).map { BookstandPlaces.firstOf(it, 2) } == listOf(0, 0, 2, 2, 4, 4))
        check((0 until BookstandPlaces.COUNT).map { BookstandPlaces.firstOf(it, 1) } == (0 until BookstandPlaces.COUNT).toList())
        check(BookstandPlaces.middleOf(0, 2) == 4.0 && BookstandPlaces.middleOf(4, 2) == 12.0)
    }
})
