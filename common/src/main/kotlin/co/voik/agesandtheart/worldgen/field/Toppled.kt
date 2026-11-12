package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import kotlin.math.ceil

/**
 * A standing shape knocked over — `fallen megaliths`: its lowest [STUMP] courses left standing where it stood, and the
 * rest lying on its side along +z beyond them, its back on the ground. [Variation] turns it to lie any way.
 *
 * Every column of the standing shape is read once, when it is built: a lying column crosses the standing one's whole
 * depth, and asking for it per column would read the same columns over again for each.
 */
data class Toppled(val standing: TerrainField) : TerrainField {
    override val kind = FieldKind.TOPPLED

    private val reach = ceil(standing.horizontalReach).toInt()

    /** The standing shape's columns, by `[x + reach][z + reach]`. */
    private val columns: List<List<Spans>> = (-reach..reach).map { x -> (-reach..reach).map { z -> standing.columnSpans(x, z) } }

    private val solid = columns.flatten().flatMap { it.ranges }

    private val lowest = solid.minOfOrNull { it.first } ?: 0

    private val top = solid.maxOfOrNull { it.last } ?: 0

    /** The nearest and furthest the standing shape reaches along z: its back, which lies on the ground, and its front. */
    private val back = depthsHeld().minOrNull() ?: 0
    private val front = depthsHeld().maxOrNull() ?: 0

    /** How far along z a height of the standing shape lies once it has fallen: just past the stump, and a block clear. */
    private val lyingOffset = front + 1 + GAP - STUMP

    /** Whether it stands taller than it is deep, and so can fall at all: an obelisk can, a pyramid or a ring cannot. */
    val canFall: Boolean get() = top - lowest + 1 > front - back + 1

    override val horizontalReach: Double = maxOf(standing.horizontalReach, (top + lyingOffset).toDouble(), (reach - back).toDouble())

    private fun depthsHeld(): List<Int> = columns.flatMap { byZ ->
        byZ.withIndex().filter { (_, spans) -> spans.ranges.isNotEmpty() }.map { (index, _) -> index - reach }
    }

    private fun columnAt(x: Int, z: Int): Spans? = columns.getOrNull(x + reach)?.getOrNull(z + reach)

    override fun columnSpans(worldX: Int, worldZ: Int): Spans {
        val stump = columnAt(worldX, worldZ)?.intersect(Spans.of(lowest, STUMP - 1)) ?: Spans.EMPTY
        val height = worldZ - lyingOffset
        if (height < STUMP || height > top) return stump
        val lying = (back..front).filter { depth -> columnAt(worldX, depth)?.contains(height) == true }.map { it - back }
        return stump.union(Spans.ofAscending(runsOf(lying)))
    }

    private fun runsOf(heights: List<Int>): List<IntRange> = buildList {
        var start: Int? = null
        var previous = 0
        for (height in heights) {
            if (start != null && height == previous + 1) {
                previous = height
                continue
            }
            start?.let { add(it..previous) }
            start = height
            previous = height
        }
        start?.let { add(it..previous) }
    }

    override fun resized(factor: Double, pivotY: Int) = Toppled(standing.resized(factor, pivotY))

    companion object {
        /** The courses of a fallen shape left standing where it stood. */
        const val STUMP = 2

        /** The blocks between the stump and where the rest fell. */
        private const val GAP = 1

        /** [standing] knocked over, or itself where it is squat or stands to the horizon, and cannot fall. */
        fun of(standing: TerrainField): TerrainField {
            if (!standing.horizontalReach.isFinite()) return standing
            return Toppled(standing).takeIf { it.canFall } ?: standing
        }

        fun codec(self: Codec<TerrainField>): MapCodec<Toppled> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(self.fieldOf("standing").forGetter(Toppled::standing)).apply(instance, ::Toppled)
        }
    }
}
