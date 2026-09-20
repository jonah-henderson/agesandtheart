package co.voik.agesandtheart.worldgen.field

import co.voik.agesandtheart.location
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.resources.Identifier
import net.minecraft.world.level.levelgen.SurfaceRules

/**
 * True where this block belongs to the top of its own column's rock, rather than to the roof or floor of
 * something hollowed out beneath it — **what keeps a cave floor from growing grass.**
 *
 * Vanilla's answer to the same question is `abovePreliminarySurface`, and it is a heightmap comparison:
 * `SurfaceRules.Context` samples the surface at the four corners of a **16-block cell** and bilinearly
 * interpolates across it. That is sound for terrain generated from the same density it reads, and it fails
 * both ways on ours, because a field can put more relief inside sixteen blocks than vanilla ever does:
 *
 * - a gorge floor **narrower than the cell** has no corner standing on it, so the interpolated surface runs
 *   far overhead, the whole tree declines, and the floor comes out as bare deepslate;
 * - a cave under a **steep slope** sits below a surface the interpolation has dragged down toward the low
 *   corner, so it reads as open ground and is dressed as one.
 *
 * Asking the column itself costs one [TerrainField.columnSpans] per column and answers exactly. It is the
 * same *rule* as vanilla's, including the [HOW_FAR_BELOW] blocks of slack the soil layers need — only
 * without the sampling that made it approximate.
 *
 * The memo is not an optimisation to be traded away: a surface is built column by column and this is asked
 * at every block of one, so without it a `Drainage` or `MountainRange` Age would pay a fifty-sample
 * neighbourhood scan a hundred times over per column. One entry is enough because the walk never leaves a
 * column until it is done with it, and a [SurfaceRules.Condition] belongs to a single chunk worker's
 * `Context` — the same reason vanilla's own `LazyXZCondition` keeps one.
 */
data class NearTheSurface(val terrain: TerrainField) : SurfaceRules.ConditionSource {

    override fun apply(context: SurfaceRules.Context): SurfaceRules.Condition = InThisColumn(context)

    override fun codec(): MapCodec<out SurfaceRules.ConditionSource> = CODEC

    private inner class InThisColumn(private val context: SurfaceRules.Context) : SurfaceRules.Condition {
        private var knownX = Int.MIN_VALUE
        private var knownZ = Int.MIN_VALUE
        private var surfaceY = Spans.LOWEST_Y

        override fun test(): Boolean {
            val blockX = context.blockX
            val blockZ = context.blockZ
            if (blockX != knownX || blockZ != knownZ) {
                knownX = blockX
                knownZ = blockZ
                // A column with no rock at all has no surface to be near, and nothing to paint either.
                surfaceY = terrain.columnSpans(blockX, blockZ).highestSolidY ?: Spans.LOWEST_Y
            }
            return context.blockY >= surfaceY - HOW_FAR_BELOW
        }
    }

    companion object {
        /**
         * How far under the surface the dressing still reaches, in blocks — vanilla's own
         * `HOW_FAR_BELOW_PRELIMINARY_SURFACE_LEVEL_TO_BUILD_SURFACE`, which is what leaves room for the soil
         * beneath the skin and for a surface depth that varies with noise.
         */
        private const val HOW_FAR_BELOW = 8

        val CODEC: MapCodec<NearTheSurface> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(TerrainField.CODEC.fieldOf("terrain").forGetter(NearTheSurface::terrain))
                .apply(instance, ::NearTheSurface)
        }

        /**
         * Registered through `WorldgenCodecs.surfaceConditionCodecs` like any other condition kind, because the
         * palette a generator holds **is** serialised — unlike the noise settings, which are rebuilt on load
         * and so can carry an unserialisable `PreliminarySurface`. That the field tree is then written twice, once
         * here and once as the generator's own shape, is the price, and it is paid once per Age rather than
         * per chunk.
         */
        val ID: Identifier = "near_the_surface".location()
    }
}
