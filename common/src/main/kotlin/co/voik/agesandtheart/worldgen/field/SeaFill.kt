package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState

/**
 * A [co.voik.agesandtheart.age.aspect.Sea] once it has a height. Everything below [level] that the shape
 * leaves empty is filled with one of [blocks]; [NONE] fills nothing, and above [level] there is only air.
 *
 * **The substance is positional but the height is not**, which is the design's asymmetry rather than a
 * shortcut: where a sea belongs is the terrain's to declare (§3.4), so an Age has exactly one waterline.
 * What varies across it is *what the sea is made of* — an ocean of water meeting one of lava along a line
 * at the same level, with no barrier and no obsidian, which nothing in Minecraft does.
 */
data class SeaFill(
    val blocks: List<BlockState>,
    val level: Int,
    val map: RegionMap = RegionMap.whole(),
    /**
     * Space the sea does not reach however far below [level] it lies — a rift, so a chasm cut through
     * land comes out dry. Where it cuts a coast the surrounding sea is already there and unaffected,
     * which is the only way water should get in.
     */
    val dry: TerrainField? = null,
    /**
     * Space that fills **however far above [level] it lies** — the mirror of [dry], and what a river needs.
     *
     * The asymmetry the class doc describes has a limit: one waterline can pour an ocean, but not a river
     * system, because a network runs downhill everywhere and a plane meets it only where it happens to
     * cross. So a shape that carries its own water hands it over as a field, and the level stays what the
     * *sea* stands at. See [co.voik.agesandtheart.worldgen.field.Drainage.describes].
     */
    val wet: TerrainField? = null,
    /**
     * Bodies the shape carries that are **not made of the sea** — see [StandingFluid].
     *
     * They ride here rather than on the landform because this is the one object every filler already
     * asks: the chunk fill, the height contract, the aquifer a carver meets and the offline probe all
     * consult a `SeaFill` and nothing else, so a second home would be the same field threaded four more
     * times to say the same thing.
     */
    val carried: List<StandingFluid> = emptyList(),
) {

    /** Which part of this column the sea is kept out of. Asked once per column, like [blockAt]. */
    fun drynessAt(worldX: Int, worldZ: Int): Spans = dry?.columnSpans(worldX, worldZ) ?: Spans.EMPTY

    /** And which part of it holds water whatever the level says. Asked once per column, the same way. */
    fun wetnessAt(worldX: Int, worldZ: Int): Spans = wet?.columnSpans(worldX, worldZ) ?: Spans.EMPTY

    /** Where each of [carried] stands in this column, in its own order. Read once per column, like the rest. */
    fun carriedAt(worldX: Int, worldZ: Int): List<Spans> =
        if (carried.isEmpty()) emptyList() else carried.map { it.where.columnSpans(worldX, worldZ) }

    /** What one of [carried] puts at this level, or null where none of them reaches it. */
    fun carriedAt(y: Int, bodies: List<Spans>): BlockState? {
        for (index in bodies.indices) {
            if (bodies[index].contains(y)) return carried[index].fluid
        }
        return null
    }

    /** The highest any carried body stands in this column, for the questions that mean "what is on top". */
    fun carriedSurfaceY(worldX: Int, worldZ: Int, counts: (BlockState) -> Boolean): Int? =
        carried.filter { counts(it.fluid) }.mapNotNull { it.where.columnSpans(worldX, worldZ).highestSolidY }.maxOrNull()

    /** What fills the empty space at this column. */
    fun blockAt(worldX: Int, worldZ: Int): BlockState =
        blocks[map.memberAt(worldX, worldZ).coerceIn(blocks.indices)]

    /**
     * One substance standing for the sea where only one will do — vanilla's settings want a single default
     * fluid per dimension, with nowhere to say "it depends". It only ever reaches things that do not care;
     * anything a player can see asks [blockAt].
     */
    val representative: BlockState = blocks.firstOrNull() ?: Blocks.AIR.defaultBlockState()

    fun fillsAt(y: Int): Boolean = y < level && !representative.isAir

    /** The same question for a column whose [dryness] and [wetness] have already been read. */
    fun fillsAt(y: Int, dryness: Spans, wetness: Spans): Boolean {
        if (representative.isAir) return false
        // A shape's own water is not subject to the waterline, so it is asked first and answers outright.
        if (wetness.contains(y)) return true
        return fillsAt(y) && !dryness.contains(y)
    }

    /**
     * The highest block this sea fills, or `null` when it fills nothing — which is how [NONE] answers, and the
     * reason no caller has to know that its [level] is a sentinel rather than a height.
     */
    val surfaceY: Int? = if (representative.isAir) null else level - 1

    companion object {
        val CODEC: MapCodec<SeaFill> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                BlockState.CODEC.listOf().fieldOf("blocks").forGetter(SeaFill::blocks),
                Codec.INT.fieldOf("level").forGetter(SeaFill::level),
                RegionMap.MAP_CODEC.codec().optionalFieldOf("regions", RegionMap.whole())
                    .forGetter(SeaFill::map),
                TerrainField.CODEC.optionalFieldOf("dry")
                    .forGetter { fill -> java.util.Optional.ofNullable(fill.dry) },
                TerrainField.CODEC.optionalFieldOf("wet")
                    .forGetter { fill -> java.util.Optional.ofNullable(fill.wet) },
                StandingFluid.codec(TerrainField.CODEC).codec().listOf().optionalFieldOf("carried", emptyList())
                    .forGetter(SeaFill::carried),
            ).apply(instance) { blocks, level, map, dry, wet, carried ->
                SeaFill(blocks, level, map, dry.orElse(null), wet.orElse(null), carried)
            }
        }

        /** Empty space all the way down. */
        val NONE = SeaFill(listOf(Blocks.AIR.defaultBlockState()), Int.MIN_VALUE)

        /** A sea filling everything below [level]. */
        fun of(block: BlockState, level: Int) = SeaFill(listOf(block), level)
    }
}
