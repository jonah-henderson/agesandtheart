package co.voik.agesandtheart.generation

import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.core.HolderSet
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.LevelHeightAccessor
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.RandomState
import net.minecraft.world.level.levelgen.structure.Structure
import java.util.Optional
import kotlin.random.Random

/**
 * What an `incongruous structures` Age builds where it does not belong (`notes/strange-words-plan.md` §4): **any
 * structure in any biome, at a height drawn for it** — a taiga village on the plains, a trail ruin out of the
 * ground, a village on the sea floor or in the sky, an ancient city in the open.
 *
 * Answered off a thread-local the Age's generator sets around its own structure starts, as the abandoned
 * villages are ([co.voik.agesandtheart.age.consequence.StrangeGround]), and asked by three Mixins at the three
 * places a structure's biome and height are decided — so anywhere else it costs one read.
 */
object IncongruousStructures {

    private val placing = ThreadLocal<Placing?>()

    private class Placing(val seed: Long, val everyBiome: HolderSet<Biome>) {
        var drawn: Drawn = Drawn(Height.AS_WRITTEN, 0)
    }

    /** Where one structure is put. Drawn per start, so every height a structure asks for agrees with the others. */
    private data class Drawn(val height: Height, val by: Int)

    private enum class Height { AS_WRITTEN, SURFACE, SEA_FLOOR, SKY, BURIED, DEEP }

    /** Runs [starting] with this thread building any structure anywhere, from the Age's [seed]. */
    fun starting(seed: Long, biomes: Collection<Holder<Biome>>, starting: () -> Unit) {
        val was = placing.get()
        placing.set(Placing(seed, HolderSet.direct(biomes.toList())))
        try {
            starting()
        } finally {
            placing.set(was)
        }
    }

    /** A structure is being tried at [at]: draw where it goes. Asked by `ChunkGeneratorMixin`. */
    @JvmStatic
    fun beginning(at: ChunkPos) {
        val doing = placing.get() ?: return
        val random = Random(doing.seed xor at.pack() * START_MIXER)
        val height = Height.entries[random.nextInt(Height.entries.size)]
        val by = when (height) {
            Height.SKY -> random.nextInt(SKY_LEAST, SKY_MOST)
            Height.BURIED -> -random.nextInt(BURIED_LEAST, BURIED_MOST)
            Height.DEEP -> random.nextInt(DEEP_LEAST, DEEP_MOST)
            Height.AS_WRITTEN, Height.SURFACE, Height.SEA_FLOOR -> 0
        }
        doing.drawn = Drawn(height, by)
    }

    /** The biomes [structure] may stand in: every one, while this thread builds anywhere. */
    @JvmStatic
    fun biomesFor(structure: Structure): HolderSet<Biome> = placing.get()?.everyBiome ?: structure.biomes()

    /** Where a jigsaw structure's start is, and what it is projected onto — vanilla's own unless drawn otherwise. */
    class JigsawStart(val at: BlockPos, val projection: Optional<Heightmap.Types>)

    /** A jigsaw structure's [start] and [projection] as drawn, or null to keep them. Asked by `JigsawStructureMixin`. */
    @JvmStatic
    fun jigsawStart(start: BlockPos, projection: Optional<Heightmap.Types>): JigsawStart? {
        val drawn = placing.get()?.drawn ?: return null
        fun onto(heightmap: Heightmap.Types) = JigsawStart(start.atY(drawn.by), Optional.of(heightmap))
        return when (drawn.height) {
            Height.AS_WRITTEN -> null
            Height.SURFACE, Height.SKY, Height.BURIED -> onto(Heightmap.Types.WORLD_SURFACE_WG)
            Height.SEA_FLOOR -> onto(Heightmap.Types.OCEAN_FLOOR_WG)
            Height.DEEP -> JigsawStart(start.atY(drawn.by), Optional.empty())
        }
    }

    /**
     * The height a structure that sits on the ground stands at, as drawn — asked by `StructureMixin` wherever
     * vanilla asks [generator] for it, so a temple's four corners and an igloo's centre move alike.
     */
    @JvmStatic
    fun groundAt(
        generator: ChunkGenerator,
        x: Int,
        z: Int,
        heightmap: Heightmap.Types,
        level: LevelHeightAccessor,
        random: RandomState,
    ): Int {
        val drawn = placing.get()?.drawn ?: return generator.getFirstOccupiedHeight(x, z, heightmap, level, random)
        fun on(asked: Heightmap.Types) = generator.getFirstOccupiedHeight(x, z, asked, level, random)
        return when (drawn.height) {
            Height.AS_WRITTEN -> on(heightmap)
            Height.SURFACE, Height.SKY, Height.BURIED -> on(Heightmap.Types.WORLD_SURFACE_WG) + drawn.by
            Height.SEA_FLOOR -> on(Heightmap.Types.OCEAN_FLOOR_WG)
            Height.DEEP -> drawn.by
        }
    }

    private const val START_MIXER = 0x5DEE_CE66_DL

    /** How far over the ground a structure in the sky stands, and how far under it one is buried. */
    private const val SKY_LEAST = 24
    private const val SKY_MOST = 64
    private const val BURIED_LEAST = 12
    private const val BURIED_MOST = 40

    /** The band a structure taken deep is put in, in blocks. */
    private const val DEEP_LEAST = -48
    private const val DEEP_MOST = 0
}
