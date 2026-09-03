package co.voik.agesandtheart.worldgen.feature

import co.voik.agesandtheart.worldgen.field.TerrainField
import co.voik.agesandtheart.worldgen.field.Variation
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.world.level.LevelHeightAccessor
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.XoroshiroRandomSource
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration
import kotlin.math.ceil

/**
 * A shape standing on the ground, made of one substance — an obelisk, a boulder, a ring of stones.
 *
 * **The shape is a [TerrainField], which is the whole reason this is one feature and not six.** The field
 * toolkit already describes solids as spans and already serialises, so an obelisk is a box under a
 * pyramid and a ring is a cylinder with a smaller one taken out of it — authored as data in a configured
 * feature rather than written as a placement loop apiece. A pack adds a seventh shape without us.
 *
 * It also settles two things that would otherwise each need code. [TerrainField.resized] resizes the
 * *description* rather than the output — a bigger obelisk genuinely has more courses of blocks, where
 * resampling a built one stretches its staircase — so `Features.SIZE` reaches this for free. And
 * [Variation] poses each copy, which is what turns an arch to face somewhere and lets boulders differ.
 *
 * **Not terrain, though the toolkit is the terrain's.** A field laid as ground answers `columnSpans` for
 * the whole world and takes its material from `TerrainFill`, which is keyed per *column* — so an obelisk
 * built that way would make the ground beneath it gold to bedrock. A feature carries its own substance
 * and sits on top of whatever the world is made of.
 */
data class FormationConfiguration(
    val shape: TerrainField,
    val variation: Variation,
    val substance: BlockState,
) : FeatureConfiguration {

    companion object {
        val CODEC: Codec<FormationConfiguration> = RecordCodecBuilder.create { instance ->
            instance.group(
                TerrainField.CODEC.fieldOf("shape").forGetter(FormationConfiguration::shape),
                Variation.CODEC.codec().optionalFieldOf("variation", Variation.NONE)
                    .forGetter(FormationConfiguration::variation),
                BlockState.CODEC.fieldOf("substance").forGetter(FormationConfiguration::substance),
            ).apply(instance, ::FormationConfiguration)
        }
    }
}

object Formation : Feature<FormationConfiguration>(FormationConfiguration.CODEC) {

    override fun place(context: FeaturePlaceContext<FormationConfiguration>): Boolean {
        val level = context.level()
        val configuration = context.config()
        val laid = raise(
            configuration = configuration,
            origin = context.origin(),
            // One seed for the whole formation, drawn once — see [raise].
            pose = context.random().nextLong(),
        ) { position, state ->
            if (!level.isOutsideBuildHeight(position)) level.setBlock(position, state, PLACED_BY_WORLDGEN)
        }
        return laid > 0
    }

    /**
     * The shape itself, laid block by block around [origin] — of anything that can take a block, so what
     * it draws can be checked without a world under it, as [SpilledSpring.spill] is.
     *
     * **[pose] is drawn once for the whole formation and re-seeds a source per column**, which is what
     * makes the pose the *instance's* rather than the column's: [Variation.sample] takes its turn and its
     * lift from the random it is handed, so a source shared across columns would rotate every column
     * differently and lay a smear rather than an obelisk.
     *
     * Returns how many blocks it laid, so a caller can tell a formation from nothing at all.
     */
    fun raise(
        configuration: FormationConfiguration,
        origin: BlockPos,
        pose: Long,
        lay: (BlockPos, BlockState) -> Unit,
    ): Int {
        val posed = configuration.variation.sizesOf(configuration.shape)
        if (posed.isEmpty()) return 0
        // **An unbounded shape is not a formation.** A slab or a half-space is solid to the horizon and
        // would ask us to lay every block in the world; the toolkit says so with an infinite reach.
        val furthest = posed.maxOf { it.horizontalReach }
        if (!furthest.isFinite()) return 0
        val reach = ceil(furthest).toInt()

        var laid = 0
        for (offsetX in -reach..reach) {
            for (offsetZ in -reach..reach) {
                val turning = XoroshiroRandomSource(pose)
                val chosen = posed[turning.nextInt(posed.size)]
                val solid = configuration.variation.sample(chosen, offsetX, offsetZ, turning)
                for (range in solid.ranges) {
                    for (height in range) {
                        lay(origin.offset(offsetX, height, offsetZ), configuration.substance)
                        laid++
                    }
                }
            }
        }
        return laid
    }

    private fun LevelHeightAccessor.isOutsideBuildHeight(position: BlockPos): Boolean =
        position.y < minY || position.y > maxY

    /** Vanilla's own flag for a block a feature lays: change it, and do not tell a neighbour. */
    private const val PLACED_BY_WORLDGEN = 2
}
