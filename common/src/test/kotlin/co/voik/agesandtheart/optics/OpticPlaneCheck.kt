package co.voik.agesandtheart.optics

import co.voik.agesandtheart.optics.ReflectorBlock.Mirror
import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.Direction

/** Using a face of a reflector, splitter or merger keeps it in its plane (`optics/OpticPlane`). */
class OpticPlaneCheck : FunSpec({

    fun reflectorOpening(plane: OpticPlane, faces: Mirror, face: Direction) =
        plane.opening(faces, face) { mirror, opened -> mirror.opening(opened) }

    fun halfSilveredOpening(plane: OpticPlane, faces: Mirror, face: Direction) =
        plane.opening(faces, face, HalfSilvered::levelOpening)

    val everyFace = Direction.entries

    test("a horizontal form never opens up or down, and stays level whatever is used") {
        for (start in Mirror.entries.filter { it.first.axis.isHorizontal && it.second.axis.isHorizontal }) {
            for (face in everyFace) {
                for (opened in listOfNotNull(reflectorOpening(OpticPlane.HORIZONTAL, start, face), halfSilveredOpening(OpticPlane.HORIZONTAL, start, face))) {
                    val staysLevel = opened.first.axis.isHorizontal && opened.second.axis.isHorizontal
                    check(staysLevel) { "using $face on a level $start opened $opened" }
                }
            }
        }
    }

    test("a vertical form keeps one face level and one up or down, whatever is used") {
        val vertical = Mirror.entries.filter { it.first.axis.isVertical != it.second.axis.isVertical }
        for (start in vertical) {
            for (face in everyFace) {
                for (opened in listOfNotNull(reflectorOpening(OpticPlane.VERTICAL, start, face), halfSilveredOpening(OpticPlane.VERTICAL, start, face))) {
                    check(opened in vertical) { "using $face on a vertical $start opened $opened" }
                    check(face == opened.first || face == opened.second) { "using $face on $start left it closed in $opened" }
                }
            }
        }
    }

    test("a splitter's straight face is reversed by using the face opposite it") {
        val opened = halfSilveredOpening(OpticPlane.HORIZONTAL, Mirror.of(Direction.NORTH, Direction.EAST), Direction.SOUTH)
        check(opened == Mirror.of(Direction.SOUTH, Direction.EAST)) { "using the face opposite the straight face opened $opened" }
    }
})
