package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.worldgen.FissuresField
import co.voik.agesandtheart.worldgen.LatticeField
import co.voik.agesandtheart.worldgen.TunnelsField
import co.voik.agesandtheart.worldgen.VerticalWindow
import co.voik.agesandtheart.worldgen.field.Slab
import co.voik.agesandtheart.worldgen.field.TerrainField
import io.kotest.core.spec.style.FunSpec

/**
 * An underground's depth and density: `deep underground` holds the caves low in the band with the rock over them
 * left whole, and never moves the floor; `few tunnels` and `plentiful tunnels` change how many there are.
 */
class UndergroundDialsCheck : FunSpec({

    val floor = -59
    val ceiling = 40
    val window = VerticalWindow(minY = -64, height = 384)

    fun said(parameter: Parameter, at: Double) = Options(mapOf(parameter.name to listOf(Span(at, at).spelled())))

    test("unsaid, the underground has the whole band") {
        check(Underground.heldBetween(floor, ceiling, Options.NONE, salt = 1L) == floor..ceiling)
    }

    test("deep keeps the floor and lowers the top; shallow keeps the top and raises the bottom") {
        val deep = Underground.heldBetween(floor, ceiling, said(Underground.DEPTH, 1.0), salt = 1L)
        val shallow = Underground.heldBetween(floor, ceiling, said(Underground.DEPTH, -1.0), salt = 1L)
        check(deep.first == floor && deep.last < ceiling) { "deep held $deep of $floor..$ceiling" }
        check(shallow.last == ceiling && shallow.first > floor) { "shallow held $shallow of $floor..$ceiling" }
    }

    test("however deep, a band keeps room for a storey") {
        val deep = Underground.heldBetween(0, 60, said(Underground.DEPTH, 1.0), salt = 1L)
        check(deep.last - deep.first >= 48) { "a 60-block band held to $deep" }
    }

    test("deep tunnels leave the rock over them whole") {
        val uncut = Slab(lowY = window.minY, highY = 60)
        val deep = said(Underground.DEPTH, 1.0)
        val heldTop = Underground.heldBetween(floor, ceiling, deep, salt = 3L).last
        val ground = Underground.TUNNELS.carve(uncut, floor, ceiling, window, deep, salt = 3L).shape
        for (x in -200..200 step 7) for (z in -200..200 step 11) {
            val solid = ground.columnSpans(x, z).ranges
            val wholeOverhead = solid.any { it.first <= heldTop + 1 && it.last >= 60 }
            check(wholeOverhead) { "($x, $z) is cut above $heldTop: $solid" }
        }
    }

    fun openIn(field: TerrainField): Int {
        var open = 0
        for (x in -160..160 step 4) for (z in -160..160 step 4) {
            open += field.columnSpans(x, z).ranges.sumOf { it.last - it.first + 1 }
        }
        return open
    }

    test("few tunnels are fewer and plentiful ones more, each about as wide") {
        val few = openIn(TunnelsField.tubes(floor, ceiling, salt = 5L, density = 0.5))
        val ordinary = openIn(TunnelsField.tubes(floor, ceiling, salt = 5L))
        val plentiful = openIn(TunnelsField.tubes(floor, ceiling, salt = 5L, density = 2.0))
        check(few < ordinary && ordinary < plentiful) { "open blocks: few $few, ordinary $ordinary, plentiful $plentiful" }
    }

    test("a denser lattice and denser fissures lie closer together") {
        val lattice = { density: Double -> LatticeField.passages(floor, ceiling, salt = 5L, density = density).spacingXZ }
        check(lattice(2.0) < lattice(1.0) && lattice(1.0) < lattice(0.5)) { "lattice spacings ${lattice(2.0)}, ${lattice(1.0)}, ${lattice(0.5)}" }
        val fissures = { density: Double -> FissuresField.openings(floor, ceiling, null, salt = 5L, density = density).spacing }
        check(fissures(2.0) < fissures(1.0) && fissures(1.0) < fissures(0.5)) { "fissure spacings ${fissures(2.0)}, ${fissures(1.0)}, ${fissures(0.5)}" }
    }

    test("at the densest a lattice keeps rock between its passages") {
        val lattice = LatticeField.passages(floor, ceiling, salt = 5L, density = 4.0)
        check(lattice.spacingXZ >= 2 * lattice.width) { "passages ${lattice.width} wide every ${lattice.spacingXZ}" }
    }
})
