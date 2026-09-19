package co.voik.agesandtheart.worldgen.dni

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.generation.AgeChunkGenerator
import co.voik.agesandtheart.worldgen.AgeRock
import co.voik.agesandtheart.worldgen.field.TerrainField
import net.minecraft.core.BlockPos
import java.util.Optional
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * Where an Age's D'ni city stands: on an island in the lake of the chamber over the origin, which
 * `Chambers` guarantees and which arrival already stands in.
 *
 * Read off the field tree rather than the world, because this decides which chunk the city starts in and
 * so must be answered before any chunk exists. The band of height the origin's chamber spans is the band
 * searched everywhere; an opening outside it is another storey.
 */
object DniCitySite {

    /** Where the start pool's anchor goes. */
    data class Site(val start: BlockPos)

    /** One answer per generator, worked out under the lock by whichever chunk worker asks first. */
    private val found = WeakHashMap<AgeChunkGenerator, Optional<Site>>()

    fun of(generator: AgeChunkGenerator): Site? =
        synchronized(found) { found.getOrPut(generator) { Optional.ofNullable(search(generator)) } }.orElse(null)

    /** One column's opening in the chamber band: the bed under it, the roof over it, and any water on it. */
    private class Column(val x: Int, val z: Int, val floor: Int, val roof: Int, val lakeTop: Int?)

    private fun search(generator: AgeChunkGenerator): Site? {
        val started = System.nanoTime()
        val rock = (generator.rock as? AgeRock.Ours)?.field ?: return null
        // A chambered Age's lakes are the water its shape carries — see `Underground.CHAMBERED`.
        val lakes = generator.seaFill.wet ?: return declined("it carries no lakes")
        val band = openingsIn(rock, 0, 0).firstOrNull { it.last - it.first >= LEAST_CHAMBER_HEIGHT }
            ?.let { (it.first - BED_SWING_ALLOWED)..it.last }
            ?: return declined("the origin holds no chamber")

        val columns = ArrayList<Column>()
        for (x in -SEARCH_REACH..SEARCH_REACH step SAMPLE_SPACING) {
            for (z in -SEARCH_REACH..SEARCH_REACH step SAMPLE_SPACING) {
                columnAt(rock, lakes, band, x, z)?.let(columns::add)
            }
        }
        // Every lake on one storey stands at the storey's own level, so the commonest top is that level.
        val lakeLevel = columns.mapNotNull { it.lakeTop }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            ?: return declined("its chamber holds no lake")

        fun isIsland(column: Column): Boolean {
            val standsClearOfTheWater = column.lakeTop == null && column.floor > lakeLevel
            val hasRoomForTheCity = column.roof - column.floor >= HEADROOM
            return standsClearOfTheWater && hasRoomForTheCity
        }
        val island = columns.filter(::isIsland)
        if (island.isEmpty()) return declined("its lake has no island in it")

        // The most level ground around it wins, then the nearest the origin: the city is one street height
        // across two hundred blocks, and the terrain adaptation should have as little to flatten as it can.
        fun levelGroundAround(candidate: Column) = island.count { other ->
            other.isWithin(CITY_REACH, of = candidate) && abs(other.floor - candidate.floor) <= LEVEL_ENOUGH
        }
        val levelGround = island.associateWith(::levelGroundAround)
        val chosen = island.maxWith(
            compareBy<Column> { levelGround.getValue(it) }.thenByDescending { it.x * it.x + it.z * it.z },
        )
        Constants.LOG.info(
            "D'ni city on the island at ({}, {}, {}), its lake at {}, found in {}ms over {} columns",
            chosen.x, chosen.floor, chosen.z, lakeLevel, (System.nanoTime() - started) / NANOS_PER_MILLI, columns.size,
        )
        return Site(BlockPos(chosen.x, chosen.floor + ANCHOR_ABOVE_THE_STREET, chosen.z))
    }

    private fun declined(why: String): Site? {
        Constants.LOG.warn("An Age written for a D'ni city has nowhere to put one: {}", why)
        return null
    }

    private fun Column.isWithin(reach: Int, of: Column): Boolean {
        val dx = x - of.x
        val dz = z - of.z
        return dx * dx + dz * dz <= reach * reach
    }

    /** The lowest opening in this column that reaches into [band], or null where the rock is solid through it. */
    private fun columnAt(rock: TerrainField, lakes: TerrainField, band: IntRange, x: Int, z: Int): Column? {
        val opening = openingsIn(rock, x, z).firstOrNull { it.first <= band.last && it.last >= band.first } ?: return null
        val lakeTop = lakes.columnSpans(x, z).ranges
            .filter { it.first <= opening.last && it.last >= opening.first }
            .maxOfOrNull { it.last }
        return Column(x, z, floor = opening.first - 1, roof = opening.last, lakeTop = lakeTop)
    }

    /** The open runs in a column with rock both under and over them, lowest first. Water counts as open. */
    private fun openingsIn(rock: TerrainField, x: Int, z: Int): List<IntRange> =
        rock.columnSpans(x, z).ranges.zipWithNext { under, over -> (under.last + 1)..(over.first - 1) }
            .filterNot { it.isEmpty() }

    /**
     * How far the start anchor stands over the block the street is laid on, read off
     * `ancient_city/city_center`: the `city_anchor` jigsaw is 24 over the template's floor, `JigsawPlacement`
     * sets a start piece down one (`getGroundLevelDelta`), and the street is its third layer. A start room of
     * our own moves this.
     */
    private const val ANCHOR_ABOVE_THE_STREET = 23

    /** How far out from the origin to look, and how finely. */
    private const val SEARCH_REACH = 320
    private const val SAMPLE_SPACING = 16

    /** An opening at the origin shorter than this is a crack, not the chamber. */
    private const val LEAST_CHAMBER_HEIGHT = 24

    /** How far under the origin's bed another column's may lie and be the same chamber; the bed swings ±33. */
    private const val BED_SWING_ALLOWED = 40

    /** Room over the island for the city; the start piece alone stands 28 over its street. */
    private const val HEADROOM = 32

    /** How much of an island counts as the city's ground, and how level it has to be to count. */
    private const val CITY_REACH = 64
    private const val LEVEL_ENOUGH = 12

    private const val NANOS_PER_MILLI = 1_000_000L
}
