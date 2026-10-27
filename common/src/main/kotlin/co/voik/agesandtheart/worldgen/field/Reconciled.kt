package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import kotlin.math.roundToInt

/**
 * [Regions], with the ground eased from one territory's height to the next across [width] blocks either side of
 * each seam, so two landforms joined by `and` meet as one country rather than at a cliff.
 *
 * Each column is the winning member's, lifted or lowered whole until its top stands at a weighted mean of the tops
 * of every member near enough to count — the winner at full weight, a member on the seam at full weight too, and
 * one [width] behind at none. On a seam two members agree on the mean, so the ground is continuous across it.
 *
 * [guides] are the rock the heights are read from, index for index with [members]. The rock divided this way is
 * its own guide; water and hollows divided on the same map take the rock as theirs, and so move with it.
 *
 * Moving a column whole by its own top would lift a river bed further than its banks and leave the water standing
 * proud of them, so surface water ([staysInItsBanks]) is moved by its own top instead, which comes out level with
 * the banks it stood level with.
 */
data class Reconciled(
    val members: List<TerrainField>,
    val guides: List<TerrainField>,
    val map: RegionMap,
    val width: Double,
    /** Whether rock lifted off its floor is filled back down to where it stood — rock, not water or hollows. */
    val keepsFloor: Boolean,
    /** Whether each column is moved by its own top rather than its rock's — surface water, not the underground's. */
    val staysInItsBanks: Boolean = false,
) : TerrainField {
    override val kind = FieldKind.RECONCILED

    override val horizontalReach = Double.POSITIVE_INFINITY

    override val samplesPerColumn =
        members.size + (members.maxOfOrNull { it.samplesPerColumn } ?: 0) + 2 * (guides.maxOfOrNull { it.samplesPerColumn } ?: 0)

    override fun columnSpans(worldX: Int, worldZ: Int): Spans {
        val behind = map.blocksBehindAt(worldX, worldZ)
        val winner = behind.indices.minBy { behind[it] }
        val standing = members.getOrNull(winner)?.columnSpans(worldX, worldZ) ?: return Spans.EMPTY
        if (standing.ranges.isEmpty()) return standing
        val ownTop = if (staysInItsBanks) standing.highestSolidY else null
        val lift = liftAt(worldX, worldZ, behind, winner, ownTop)
        if (lift == 0) return standing
        val moved = standing.shifted(lift)
        val floor = standing.ranges.first().first
        val leftAFloorBehind = keepsFloor && lift > 0
        return if (leftAFloorBehind) moved.union(Spans.of(floor, floor + lift)) else moved
    }

    /** How far to move a column whose top stands at [ownTop], or at its rock's top where that is null. */
    private fun liftAt(worldX: Int, worldZ: Int, behind: DoubleArray, winner: Int, ownTop: Int?): Int {
        val near = behind.indices.filter { it != winner && behind[it] < width }
        if (near.isEmpty()) return 0
        val winnersTop = ownTop ?: guides[winner].columnSpans(worldX, worldZ).highestSolidY ?: return 0
        var weighed = winnersTop.toDouble()
        var weights = 1.0
        for (member in near) {
            val top = guides[member].columnSpans(worldX, worldZ).highestSolidY ?: continue
            val weight = eased(1.0 - behind[member] / width)
            weighed += top * weight
            weights += weight
        }
        return (weighed / weights - winnersTop).roundToInt()
    }

    override fun resized(factor: Double, pivotY: Int) = Reconciled(
        members.map { it.resized(factor, pivotY) },
        guides.map { it.resized(factor, pivotY) },
        map.resized(factor),
        width * factor,
        keepsFloor,
        staysInItsBanks,
    )

    companion object {
        /** Smoothstep, so a member's weight comes in with no crease where it starts to count. */
        private fun eased(share: Double): Double = share * share * (3.0 - 2.0 * share)

        /** [members] divided by territory and reconciled across seams, or the single field where nothing divides. */
        fun of(
            members: List<TerrainField>,
            guides: List<TerrainField>,
            map: RegionMap,
            width: Double,
            keepsFloor: Boolean,
            staysInItsBanks: Boolean = false,
        ): TerrainField = members.singleOrNull() ?: Reconciled(members, guides, map, width, keepsFloor, staysInItsBanks)

        fun codec(self: Codec<TerrainField>): MapCodec<Reconciled> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                self.listOf().fieldOf("members").forGetter(Reconciled::members),
                self.listOf().fieldOf("guides").forGetter(Reconciled::guides),
                RegionMap.MAP_CODEC.codec().fieldOf("map").forGetter(Reconciled::map),
                Codec.DOUBLE.fieldOf("width").forGetter(Reconciled::width),
                Codec.BOOL.fieldOf("keeps_floor").forGetter(Reconciled::keepsFloor),
                Codec.BOOL.optionalFieldOf("stays_in_its_banks", false).forGetter(Reconciled::staysInItsBanks),
            ).apply(instance, ::Reconciled)
        }
    }
}
