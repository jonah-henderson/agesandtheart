package co.voik.agesandtheart.book

import net.minecraft.core.BlockPos
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round

/**
 * A carriage's hold on one space of its wall, the block in front of a shelf: from tick [from] to tick [until], both
 * included, for as long as any part of the carriage is in it. Finders sharing a wall plan around each other's holds, so
 * their ways may cross and their carriages never meet (Jonah, 2026-11-19).
 */
data class Reservation(val space: BlockPos, val from: Long, val until: Long) {

    fun clashesWith(other: Reservation): Boolean = space == other.space && from <= other.until && other.from <= until

    companion object {
        /** Held from then on, as a carriage at rest holds where it stands. */
        const val FOR_GOOD = Long.MAX_VALUE
    }
}

/**
 * A carriage's timetable, written out stand by stand and leg by leg from [startsAt]: every space it will be in, held from
 * the tick any part of it enters to the tick the last of it leaves, with a tick to spare either side. [spaceOf] names the
 * block in front of a shelf for a space of the finder's own wall.
 */
class Timetable(startsAt: Long, private val spaceOf: (WallCell) -> BlockPos) {

    private val held = mutableListOf<Reservation>()
    private var clock = startsAt

    val reservations: List<Reservation> get() = held

    fun stand(cell: WallCell, ticks: Int): Timetable = apply {
        held += Reservation(spaceOf(cell), clock - SPARE, clock + ticks + SPARE)
        clock += ticks
    }

    fun standForGood(cell: WallCell): Timetable = apply {
        held += Reservation(spaceOf(cell), clock - SPARE, Reservation.FOR_GOOD)
    }

    fun travel(way: FramePath): Timetable = apply {
        val ticks = ticksAlong(way)
        val firstTick = LinkedHashMap<WallCell, Int>()
        val lastTick = HashMap<WallCell, Int>()
        for (tick in 0..ticks) {
            for (cell in cellsUnder(way.at(tick * BookFinderBlockEntity.BLOCKS_A_TICK))) {
                firstTick.putIfAbsent(cell, tick)
                lastTick[cell] = tick
            }
        }
        for ((cell, first) in firstTick) {
            held += Reservation(spaceOf(cell), clock + first - SPARE, clock + lastTick.getValue(cell) + SPARE)
        }
        clock += ticks
    }

    companion object {
        private const val SPARE = 1

        /** How many ticks the carriage takes along [way], at the pace it always travels. */
        fun ticksAlong(way: FramePath): Int = ceil(way.length / BookFinderBlockEntity.BLOCKS_A_TICK).toInt().coerceAtLeast(1)

        /**
         * The spaces a carriage a block across, its middle at [point], is partly in: one where it stands in the middle of
         * a space, two or four where it is between them.
         */
        fun cellsUnder(point: WallPoint): List<WallCell> {
            fun spanned(at: Double): List<Int> =
                if (abs(at - round(at)) < IN_THE_MIDDLE) listOf(round(at).toInt()) else listOf(floor(at).toInt(), floor(at).toInt() + 1)
            return spanned(point.across).flatMap { across -> spanned(point.up).map { up -> WallCell(across, up) } }
        }

        private const val IN_THE_MIDDLE = 1.0e-6
    }
}
