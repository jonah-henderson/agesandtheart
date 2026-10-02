package co.voik.agesandtheart.worldgen.dni

import co.voik.agesandtheart.content.AdvancedAnalysisMachine
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.station.Compounder
import co.voik.agesandtheart.station.CompounderBlock
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.util.RandomSource
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.StructureManager
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.level.levelgen.structure.StructurePiece
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext

/** A machine each D'ni city holds exactly one of, and where it stands relative to the city's frame. */
enum class DniDevice(val key: String) {
    /** Beside where a visitor arrives, so the first thing seen is the frame and the second is this. */
    COMPOUNDER("compounder"),

    /** On the arrival's other side, broken until nara wakes it. */
    ADVANCED_ANALYSIS_MACHINE("advanced_analysis_machine"),
    ;

    /** The machine as it stands, its front — the compounder's, where results come out — towards [front]. */
    fun stateFacing(front: Direction): BlockState = when (this) {
        COMPOUNDER -> Compounder.BLOCK.defaultBlockState().setValue(CompounderBlock.FACING, front)
        ADVANCED_ANALYSIS_MACHINE -> AdvancedAnalysisMachine.BLOCK.defaultBlockState()
    }

    /** How many blocks to the side of the arrival it stands, along the frame; negative is the other side. */
    val besideTheArrival: Int get() = when (this) {
        COMPOUNDER -> BESIDE
        ADVANCED_ANALYSIS_MACHINE -> -BESIDE
    }

    companion object {
        private const val BESIDE = 3

        fun byKey(key: String): DniDevice = entries.firstOrNull { it.key == key } ?: COMPOUNDER
    }
}

/**
 * One [DniDevice], set on the city's floor. A piece of its own rather than a block in a template, because a
 * template's pieces can turn up any number of times and a city must hold exactly one.
 */
class DniDevicePiece : StructurePiece {

    private val device: DniDevice

    /** Away from the frame, out over the floor a visitor arrives on. */
    private val front: Direction

    constructor(at: BlockPos, device: DniDevice, front: Direction) : super(AgeContent.DNI_DEVICE_PIECE, 0, BoundingBox(at)) {
        this.device = device
        this.front = front
    }

    constructor(saved: CompoundTag) : super(AgeContent.DNI_DEVICE_PIECE, saved) {
        this.device = DniDevice.byKey(saved.getStringOr(DEVICE_KEY, ""))
        this.front = Direction.byName(saved.getStringOr(FRONT_KEY, "")) ?: Direction.NORTH
    }

    override fun addAdditionalSaveData(context: StructurePieceSerializationContext, saved: CompoundTag) {
        saved.putString(DEVICE_KEY, device.key)
        saved.putString(FRONT_KEY, front.serializedName)
    }

    override fun postProcess(
        level: WorldGenLevel,
        structures: StructureManager,
        generator: ChunkGenerator,
        random: RandomSource,
        within: BoundingBox,
        chunk: ChunkPos,
        at: BlockPos,
    ) {
        val where = BlockPos(boundingBox.minX(), boundingBox.minY(), boundingBox.minZ())
        if (!within.isInside(where)) return
        level.setBlock(where, device.stateFacing(front), UPDATE_FLAGS)
    }

    private companion object {
        const val DEVICE_KEY = "device"
        const val FRONT_KEY = "front"

        /** No neighbour updates: this is worldgen. */
        const val UPDATE_FLAGS = 2
    }
}
