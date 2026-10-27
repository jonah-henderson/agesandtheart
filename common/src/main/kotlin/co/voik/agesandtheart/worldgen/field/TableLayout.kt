package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.world.level.levelgen.XoroshiroRandomSource
import java.util.Optional
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Territories laid out by a [TerrainTable]: each member claims a column by how near its [homes] lie to the drivers
 * there, so a landform stands where the land suits it and its neighbours are the ones whose homes are near its own.
 * A member with no home of its own — a made or strange landform — is given one drawn from [seed], so it still keeps
 * to one part of the table rather than to none.
 *
 * Each member's claim is offset so that it covers its share of the ground ([shares], or an equal division), which is
 * worked out once over a wide sample of the drivers: a writer who asks for two landforms gets about half of each,
 * wherever in the table their homes are.
 */
data class TableLayout(
    val table: TerrainTable,
    val homes: List<TerrainTable.Home?>,
    val shares: List<Double>,
    val seed: Long,
    /** How much larger the territories are than the table's own grain, for a resized field. */
    val scale: Double = 1.0,
) {
    private val placed: List<TerrainTable.Home> = homes.mapIndexed { member, home -> home ?: drawnHome(member) }

    private val offsets: DoubleArray by lazy { calibrated() }

    // A little roughness of each member's own, so a border wanders rather than tracing the drivers' smooth contours.
    private val edges = placed.indices.map { member -> fieldNoise(seed + member * MEMBER_STRIDE, EDGE_OCTAVE, EDGE_AMPLITUDES) }

    /** Which member wins a column, which comes second, and by how many blocks the winner leads. */
    data class Contest(val winner: Int, val runnerUp: Int, val leadInBlocks: Double)

    /** Which member wins a column, without measuring by how much. */
    fun winnerAt(worldX: Int, worldZ: Int): Int {
        val scores = scoresAt(worldX.toDouble(), worldZ.toDouble())
        return scores.indices.maxBy { scores[it] }
    }

    fun contestAt(worldX: Int, worldZ: Int): Contest {
        val scores = scoresAt(worldX.toDouble(), worldZ.toDouble())
        var winner = 0
        var runnerUp = -1
        for (member in 1..<scores.size) {
            if (scores[member] > scores[winner]) {
                runnerUp = winner
                winner = member
            } else if (runnerUp < 0 || scores[member] > scores[runnerUp]) {
                runnerUp = member
            }
        }
        if (runnerUp < 0) return Contest(winner, winner, Double.POSITIVE_INFINITY)
        return Contest(winner, runnerUp, blocksBetween(worldX, worldZ, scores, winner, runnerUp))
    }

    /** How far each member's claim falls short of the winner's here, in blocks: zero for the winner. */
    fun blocksBehindAt(worldX: Int, worldZ: Int): DoubleArray {
        val scores = scoresAt(worldX.toDouble(), worldZ.toDouble())
        val winner = scores.indices.maxBy { scores[it] }
        return DoubleArray(scores.size) { member ->
            if (member == winner) 0.0 else blocksBetween(worldX, worldZ, scores, winner, member)
        }
    }

    fun resized(factor: Double) = copy(scale = scale * factor)

    private fun scoresAt(worldX: Double, worldZ: Double): DoubleArray {
        val x = worldX / scale
        val z = worldZ / scale
        val here = table.placeAt(x, z)
        return DoubleArray(placed.size) { member ->
            scoreOf(here, member) + offsets[member] + edges[member].get(x, 0.0, z).toDouble() * EDGE_ROUGHNESS
        }
    }

    private fun scoreOf(here: TerrainTable.Home, member: Int) = -here.distanceSquaredTo(placed[member]) / SPREAD

    /**
     * The lead of [winner] over [other] in blocks: the difference in their scores over how fast it changes across
     * the ground, read a few blocks either way. Exact where the drivers change evenly, which over a seam they do.
     */
    private fun blocksBetween(worldX: Int, worldZ: Int, scores: DoubleArray, winner: Int, other: Int): Double {
        val lead = scores[winner] - scores[other]
        val eastward = scoresAt(worldX + GRADIENT_STEP, worldZ.toDouble())
        val southward = scoresAt(worldX.toDouble(), worldZ + GRADIENT_STEP)
        val leadEast = (eastward[winner] - eastward[other] - lead) / GRADIENT_STEP
        val leadSouth = (southward[winner] - southward[other] - lead) / GRADIENT_STEP
        val rate = sqrt(leadEast * leadEast + leadSouth * leadSouth).coerceAtLeast(SLOWEST_LEAD)
        return lead / rate
    }

    /** A home for a member that has none, drawn from the seed and anywhere in the table. */
    private fun drawnHome(member: Int): TerrainTable.Home {
        val random = XoroshiroRandomSource(seed + member * MEMBER_STRIDE)
        fun anywhere() = random.nextDouble() * 2.0 - 1.0
        return TerrainTable.Home(anywhere() * DRAWN_REACH, anywhere() * DRAWN_REACH, anywhere() * DRAWN_REACH)
    }

    /**
     * Each member's offset, nudged until it covers its share of a wide sample of the drivers. A share is ground
     * covered, which depends on where in the table the homes stand and how the drivers are spread, so it is
     * measured rather than worked out.
     */
    private fun calibrated(): DoubleArray {
        val members = placed.size
        val wanted = shares.takeIf { it.size == members }?.map { it.coerceAtLeast(FAINTEST_SHARE) } ?: List(members) { 1.0 }
        val total = wanted.sum()
        val targets = wanted.map { it / total }
        val sampled = (0..<SAMPLES_ACROSS).flatMap { row ->
            (0..<SAMPLES_ACROSS).map { column ->
                val x = (column - SAMPLES_ACROSS / 2) * SAMPLE_SPACING
                val z = (row - SAMPLES_ACROSS / 2) * SAMPLE_SPACING
                DoubleArray(members) { member -> scoreOf(table.placeAt(x, z), member) }
            }
        }
        val offsets = DoubleArray(members)
        repeat(CALIBRATION_ROUNDS) {
            val won = IntArray(members)
            for (scores in sampled) won[scores.indices.maxBy { scores[it] + offsets[it] }]++
            for (member in 0..<members) {
                val covered = (won[member].toDouble() / sampled.size).coerceAtLeast(1.0 / sampled.size)
                offsets[member] += CALIBRATION_STEP * ln(targets[member] / covered)
            }
        }
        return offsets
    }

    companion object {
        /** How sharply a claim falls off with distance from home, in the table's units squared. */
        private const val SPREAD = 0.5

        private const val GRADIENT_STEP = 8.0

        // Borders wandering at about 256 blocks and half that, by a fraction of what separates two homes.
        private const val EDGE_OCTAVE = -8
        private val EDGE_AMPLITUDES = listOf(1.0, 0.5)
        private const val EDGE_ROUGHNESS = 0.12

        /** A lead changing slower than this per block is treated as this, so a flat stretch is not a seam. */
        private const val SLOWEST_LEAD = 1.0e-5

        /** How far out in the table a drawn home may stand. */
        private const val DRAWN_REACH = 0.7

        private const val SAMPLES_ACROSS = 64
        private const val SAMPLE_SPACING = 211.0
        private const val CALIBRATION_ROUNDS = 60
        private const val CALIBRATION_STEP = 0.4
        private const val FAINTEST_SHARE = 1.0e-3
        private const val MEMBER_STRIDE = 0x9E37_79B9L

        val CODEC: Codec<TableLayout> = RecordCodecBuilder.create { instance ->
            instance.group(
                TerrainTable.CODEC.fieldOf("table").forGetter(TableLayout::table),
                TerrainTable.Home.CODEC.optionalFieldOf("home").codec().listOf().fieldOf("homes")
                    .forGetter { layout -> layout.homes.map { Optional.ofNullable(it) } },
                Codec.DOUBLE.listOf().optionalFieldOf("shares", emptyList()).forGetter(TableLayout::shares),
                Codec.LONG.fieldOf("seed").forGetter(TableLayout::seed),
                Codec.DOUBLE.optionalFieldOf("scale", 1.0).forGetter(TableLayout::scale),
            ).apply(instance) { table, homes, shares, seed, scale ->
                TableLayout(table, homes.map { it.orElse(null) }, shares, seed, scale)
            }
        }
    }
}
