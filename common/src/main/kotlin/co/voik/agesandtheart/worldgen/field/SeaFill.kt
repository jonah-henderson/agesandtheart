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
 * **Where a sea stands is the terrain's to declare (§3.4), territory by territory** ([territories]). Two
 * landforms that want different seas each keep their own, and where they meet the higher one is held back
 * by a wall of the land's own rock ([isWalledAt]) — islands over a void beside hills over a sea, neither
 * drowning the other (Jonah, 2026-10-06). [level] is still the one number for whatever has to have one:
 * vanilla's sea level, the shores, the aquifers, the abyss plane.
 *
 * What varies across a level is *what the sea is made of* — an ocean of water meeting one of lava along a
 * line at the same level, with no barrier and no obsidian, which nothing in Minecraft does.
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
    /** Each terrain territory's own level, where they differ; null where one level serves the whole Age. */
    val territories: Territories? = null,
) {

    /**
     * Each terrain territory's sea level, on the same map the rock is laid by — [NO_SEA] for a territory
     * with none, which is a floating landform nobody gave a sea.
     */
    data class Territories(val where: RegionMap, val levels: List<Int>) {
        fun levelAt(worldX: Int, worldZ: Int): Int = levels[where.memberAt(worldX, worldZ).coerceIn(levels.indices)]

        companion object {
            val CODEC: Codec<Territories> = RecordCodecBuilder.create { instance ->
                instance.group(
                    RegionMap.MAP_CODEC.fieldOf("where").forGetter(Territories::where),
                    Codec.INT.listOf().fieldOf("levels").forGetter(Territories::levels),
                ).apply(instance, ::Territories)
            }
        }
    }

    /** The level the sea stands at in this column: its territory's, or [level] where there is only one. */
    fun levelAt(worldX: Int, worldZ: Int): Int = territories?.levelAt(worldX, worldZ) ?: level

    /** [surfaceY] for this column, or null where this column's territory has no sea. */
    fun surfaceYAt(worldX: Int, worldZ: Int): Int? {
        val here = levelAt(worldX, worldZ)
        return if (representative.isAir || here == NO_SEA) null else here - 1
    }

    /**
     * Whether the sea's place at this block is taken by a wall instead: it would fill here at [levelHere],
     * and a column beside it stands its sea lower than this block, so water here would pour over the edge.
     * The wall is one block thick along the higher sea's edge, from the lower sea up to its own; beside a
     * territory with no sea it reaches the floor of the world.
     */
    fun isWalledAt(worldX: Int, worldZ: Int, y: Int, levelHere: Int): Boolean {
        val held = territories ?: return false
        if (y >= levelHere || representative.isAir) return false
        fun lowerBeside(x: Int, z: Int) = held.levelAt(x, z) <= y
        return lowerBeside(worldX - 1, worldZ) || lowerBeside(worldX + 1, worldZ) ||
            lowerBeside(worldX, worldZ - 1) || lowerBeside(worldX, worldZ + 1)
    }

    /** This sea [blocks] higher everywhere, every territory's level with it — what a rising sea asks. */
    fun raisedBy(blocks: Int): SeaFill = copy(
        level = level + blocks,
        territories = territories?.let { held ->
            held.copy(levels = held.levels.map { if (it == NO_SEA) it else it + blocks })
        },
    )

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
    fun fillsAt(y: Int, dryness: Spans, wetness: Spans): Boolean = fillsAt(y, level, dryness, wetness)

    /** And for a column whose own level, [levelHere], has been read too — see [levelAt]. */
    fun fillsAt(y: Int, levelHere: Int, dryness: Spans, wetness: Spans): Boolean {
        if (representative.isAir) return false
        // A shape's own water is not subject to the waterline, so it is asked first and answers outright.
        if (wetness.contains(y)) return true
        return y < levelHere && !dryness.contains(y)
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
                Territories.CODEC.optionalFieldOf("territories")
                    .forGetter { fill -> java.util.Optional.ofNullable(fill.territories) },
            ).apply(instance) { blocks, level, map, dry, wet, carried, territories ->
                SeaFill(blocks, level, map, dry.orElse(null), wet.orElse(null), carried, territories.orElse(null))
            }
        }

        /** A territory's level where it has no sea at all: nothing is ever below it. */
        const val NO_SEA = Int.MIN_VALUE

        /** Empty space all the way down. */
        val NONE = SeaFill(listOf(Blocks.AIR.defaultBlockState()), NO_SEA)

        /** A sea filling everything below [level]. */
        fun of(block: BlockState, level: Int) = SeaFill(listOf(block), level)
    }
}
