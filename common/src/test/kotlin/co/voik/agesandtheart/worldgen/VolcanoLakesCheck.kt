package co.voik.agesandtheart.worldgen

import io.kotest.core.spec.style.FunSpec
import net.minecraft.world.level.levelgen.XoroshiroRandomSource

/**
 * **The lava a caldera arrives full of** — the one thing about a volcano that a picture of its heights
 * cannot show, because a lake and the rock around it are the same colour to a heightmap.
 *
 * Two of these are guarantees rather than measurements, and both are the kind that fail silently in the
 * game: a lake whose surface is not level reads as broken rock, and a lake standing higher than the rim
 * beside it **drains through that one column** and takes the whole caldera with it.
 */
class VolcanoLakesCheck : FunSpec({

    /**
     * Level, everywhere in one crater — which is the whole reason the crater is cut *after* the roughening
     * rather than before it.
     */
    test("a crater's lava stands at one height") {
        for ((atX, atZ) in someVolcanoes()) {
            val tops = surfacesAround(atX, atZ).map { it.lakeTop }.filterNotNull().distinct()
            check(tops.size <= ONE_SURFACE) {
                "the crater near ($atX, $atZ) holds lava at ${tops.size} different heights: ${tops.sorted()}"
            }
        }
    }

    /**
     * **And the rock beside it stands higher, on every bearing out of it.** A single column of rim under
     * the surface is a spillway, and vanilla will find it on the first tick the chunk is loaded.
     */
    test("nothing beside a lake is lower than the lake") {
        for ((atX, atZ) in someVolcanoes()) {
            val around = surfacesAround(atX, atZ).associateBy { it.x to it.z }
            for (column in around.values) {
                val lakeTop = column.lakeTop ?: continue
                for ((stepX, stepZ) in BESIDE) {
                    val neighbour = around[column.x + stepX * STRIDE to column.z + stepZ * STRIDE] ?: continue
                    if (neighbour.lakeTop != null) continue
                    check(neighbour.rockTop >= lakeTop) {
                        "lava at y=$lakeTop by (${column.x}, ${column.z}) has rock at only y=${neighbour.rockTop} " +
                            "beside it, which is a spillway the whole crater drains through"
                    }
                }
            }
        }
    }

    /** A crater that came out dry is a caldera nobody would walk into twice. */
    test("every crater actually holds some") {
        for ((atX, atZ) in someVolcanoes()) {
            val wet = surfacesAround(atX, atZ).count { it.lakeTop != null }
            check(wet >= ENOUGH_TO_BE_A_LAKE) {
                "the crater near ($atX, $atZ) holds lava in only $wet of the columns sampled around it"
            }
        }
    }

    /** What one volcano actually came out as, printed rather than asserted — the instrument, as ever. */
    test("a crater in cross-section, for reading") {
        val (atX, atZ) = someVolcanoes().first()
        println("  the cone near ($atX, $atZ), west to east through its middle:")
        for (offset in -ACROSS..ACROSS step STRIDE) {
            val column = surfaceAt(atX + offset, atZ)
            val lake = column.lakeTop?.let { "lava to y=$it" } ?: "dry"
            println("    x${offset.toString().padStart(4)}  rock to y=${column.rockTop.toString().padStart(4)}  $lake")
        }
    }
}) {
    private companion object {
        private const val SEED = 11L

        /** The same lattice the vent feature draws its candidates from, so a sample is a column it could pick. */
        private const val STRIDE = 4

        /** Past the widest caldera at its largest pose, so a scan crosses the whole crater and its rim. */
        private const val ACROSS = 60

        private const val ONE_SURFACE = 1

        /**
         * Columns on the [STRIDE] lattice, so about `pi r^2 / 16` of them — ten is a lake seven or eight
         * blocks across, which the narrowest crater here (a floor radius of six) comfortably beats and a
         * crater that failed to flood could not.
         */
        private const val ENOUGH_TO_BE_A_LAKE = 10

        /** How far out to look for cones to test, and how many is enough to have tested the four shapes. */
        private const val SEARCH = 3000.0
        private const val ENOUGH_CONES = 6

        private val BESIDE = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

        private val VOLCANOES = VolcanoField.over(SEED)

        private data class Column(val x: Int, val z: Int, val rockTop: Int, val lakeTop: Int?)

        private fun surfaceAt(x: Int, z: Int) = Column(
            x = x,
            z = z,
            rockTop = VOLCANOES.cones.columnSpans(x, z).highestSolidY ?: Int.MIN_VALUE / 2,
            lakeTop = VOLCANOES.lakes.columnSpans(x, z).highestSolidY,
        )

        /** Every column on the lattice within [ACROSS] of a cone's axis, read once. */
        private fun surfacesAround(atX: Int, atZ: Int): List<Column> =
            (-ACROSS..ACROSS step STRIDE).flatMap { offsetX ->
                (-ACROSS..ACROSS step STRIDE).map { offsetZ -> surfaceAt(atX + offsetX, atZ + offsetZ) }
            }

        /**
         * Where the nearest cones stand, asked of [VolcanoField.sites] with the random factory an instanced
         * field builds from the same seed — so these are the answers generation gets rather than a guess.
         */
        private fun someVolcanoes(): List<Pair<Int, Int>> {
            val random = XoroshiroRandomSource(SEED).forkPositional()
            val found = mutableListOf<Pair<Int, Int>>()
            VolcanoField.sites(SEED).forEachInstanceNear(0, 0, SEARCH, random) { x, z, _ -> found += x to z }
            return found.sortedBy { (x, z) -> x.toLong() * x + z.toLong() * z }.take(ENOUGH_CONES)
        }
    }
}
