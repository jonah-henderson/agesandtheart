package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.world.level.levelgen.XoroshiroRandomSource

/**
 * Stamps copies of its [templates] across the world according to [placement] — the "big multiplier"
 * (grids of pyramids/columns, scattered monoliths). Each instance's cell deterministically picks one
 * template from the handful, so variety is *choice-from-a-set*, never per-instance CSG reshaping;
 * organic variation belongs to noise/carvers instead (see `notes/terrain-architecture.md`).
 *
 * Templates are authored around their local origin `(0, 0)`; [placement] translates the query into
 * each instance's frame. The instance set is unbounded, so nothing is stored — [placement] regenerates
 * the nearby origins deterministically per column.
 */
data class Instanced(
    val templates: List<TerrainField>,
    val placement: Placement,
    val seed: Long,
) : TerrainField {
    override val kind = FieldKind.INSTANCED

    // An instanced field can place something anywhere, so it is never itself an instancing template.
    override val horizontalReach = Double.POSITIVE_INFINITY

    private val templateReach = templates.maxOfOrNull { it.horizontalReach } ?: 0.0

    // Deterministic per-coordinate RNG, built once from [seed]; immutable, so shared safely across threads.
    private val random = XoroshiroRandomSource(seed).forkPositional()

    override fun columnSpans(worldX: Int, worldZ: Int): Spans {
        if (templates.isEmpty()) return Spans.EMPTY
        // Hot path: accumulate the union across nearby instances in place rather than build a list.
        var solid = Spans.EMPTY
        placement.forEachInstanceNear(worldX, worldZ, templateReach, random) { originX, originZ, instanceRandom ->
            val chosen = templates[instanceRandom.nextInt(templates.size)]
            solid = solid.union(chosen.columnSpans(worldX - originX, worldZ - originZ))
        }
        return solid
    }

    companion object {
        fun codec(self: Codec<TerrainField>): MapCodec<Instanced> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                self.listOf().fieldOf("templates").forGetter(Instanced::templates),
                Placement.CODEC.fieldOf("placement").forGetter(Instanced::placement),
                Codec.LONG.fieldOf("seed").forGetter(Instanced::seed),
            ).apply(instance, ::Instanced)
        }
    }
}
