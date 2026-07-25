package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.world.level.levelgen.XoroshiroRandomSource

/**
 * Stamps copies of its [templates] across the world according to [placement] — the "big multiplier"
 * (grids of pyramids/columns, scattered monoliths). Each instance's cell deterministically picks one
 * template from the handful and poses it within [variation], so variety is *choice-from-a-set* plus a
 * cheap affine pose — never per-instance CSG reshaping; organic variation belongs to noise/carvers
 * instead (see `notes/terrain-architecture.md`).
 *
 * Templates are authored around their local origin `(0, 0)`; [placement] translates the query into
 * each instance's frame and [variation] turns and sizes it there. The instance set is unbounded, so
 * nothing is stored — [placement] regenerates the nearby origins deterministically per column.
 */
data class Instanced(
    val templates: List<TerrainField>,
    val placement: Placement,
    val variation: Variation,
    val seed: Long,
) : TerrainField {
    override val kind = FieldKind.INSTANCED

    // An instanced field can place something anywhere, so it is never itself an instancing template.
    override val horizontalReach = Double.POSITIVE_INFINITY

    /**
     * Every template at every size [variation] allows, built once here rather than per instance —
     * which is what lets a resized copy be a genuinely larger shape instead of a stretched sampling of
     * a smaller one. Placing a copy is then just picking one of these.
     */
    private val posedTemplates = templates.flatMap { variation.sizesOf(it) }

    // Exact, because the resized templates report their own reach — no scale fudge factor needed.
    private val templateReach = posedTemplates.maxOfOrNull { it.horizontalReach } ?: 0.0

    // Deterministic per-coordinate RNG, built once from [seed]; immutable, so shared safely across threads.
    private val random = XoroshiroRandomSource(seed).forkPositional()

    override fun columnSpans(worldX: Int, worldZ: Int): Spans {
        if (posedTemplates.isEmpty()) return Spans.EMPTY
        // Hot path: accumulate the union across nearby instances in place rather than build a list.
        var solid = Spans.EMPTY
        placement.forEachInstanceNear(worldX, worldZ, templateReach, random) { originX, originZ, instanceRandom ->
            val chosen = posedTemplates[instanceRandom.nextInt(posedTemplates.size)]
            solid = solid.union(variation.sample(chosen, worldX - originX, worldZ - originZ, instanceRandom))
        }
        return solid
    }

    // Resizing an instanced field resizes what it places and spreads the layout to match.
    override fun resized(factor: Double, pivotY: Int) = Instanced(
        templates.map { it.resized(factor, pivotY) },
        placement.resized(factor),
        variation,
        seed,
    )

    companion object {
        fun codec(self: Codec<TerrainField>): MapCodec<Instanced> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                self.listOf().fieldOf("templates").forGetter(Instanced::templates),
                Placement.CODEC.fieldOf("placement").forGetter(Instanced::placement),
                // Optional (and nested) so field trees serialised before poses existed still load.
                Variation.CODEC.codec().optionalFieldOf("variation", Variation.NONE).forGetter(Instanced::variation),
                Codec.LONG.fieldOf("seed").forGetter(Instanced::seed),
            ).apply(instance, ::Instanced)
        }
    }
}
