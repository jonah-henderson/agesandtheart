package co.voik.agesandtheart.worldgen.dni

import co.voik.agesandtheart.content.AdvancedAnalysisMachine
import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.content.PageItem
import co.voik.agesandtheart.location
import co.voik.agesandtheart.station.Compounder
import co.voik.agesandtheart.station.CompounderBlock
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.util.RandomSource
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.StructureManager
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.entity.ChestBlockEntity
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

    constructor(at: BlockPos, device: DniDevice, front: Direction) :
        super(AgeContent.DNI_DEVICE_PIECE, 0, boxFor(at, device, front)) {
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
        val where = machineAt(boundingBox, device, front)
        if (within.isInside(where)) level.setBlock(where, device.stateFacing(front), UPDATE_FLAGS)
        if (device == DniDevice.COMPOUNDER) leaveTheWordForPlasma(level, where.relative(besideOf(front)), within)
    }

    /**
     * A chest beside the compounder holding the page for plasma, which is what its repair wants (design
     * §7.1.2) — a STAND-IN until Jonah's own structures teach the word near the machine.
     */
    private fun leaveTheWordForPlasma(level: WorldGenLevel, chest: BlockPos, within: BoundingBox) {
        if (!within.isInside(chest)) return
        val facing = if (front.axis.isHorizontal) front else Direction.NORTH
        level.setBlock(chest, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing), UPDATE_FLAGS)
        (level.getBlockEntity(chest) as? ChestBlockEntity)?.setItem(0, plasmaPage(level))
    }

    private fun plasmaPage(level: WorldGenLevel): ItemStack {
        val vocabulary = Vocabulary.of(level.level.server)
        val word = vocabulary.word(PLASMA_WORD.toString()) ?: return PageItem.writtenWith(PLASMA_WORD)
        return PageItem.writtenWith(word, vocabulary)
    }

    private companion object {
        val PLASMA_WORD = "plasma".location()

        /** Along the frame, at a right angle to the front. */
        fun besideOf(front: Direction): Direction = if (front.axis.isHorizontal) front.clockWise else Direction.EAST

        /** The machine's block, and for the compounder the chest beside it too, so both are placed in whichever chunk holds them. */
        fun boxFor(at: BlockPos, device: DniDevice, front: Direction): BoundingBox {
            if (device != DniDevice.COMPOUNDER) return BoundingBox(at)
            val chest = at.relative(besideOf(front))
            return BoundingBox(
                minOf(at.x, chest.x), at.y, minOf(at.z, chest.z),
                maxOf(at.x, chest.x), at.y, maxOf(at.z, chest.z),
            )
        }

        /** Where the machine stands in its box: the corner, unless the chest is beside it on that side. */
        fun machineAt(box: BoundingBox, device: DniDevice, front: Direction): BlockPos {
            val corner = BlockPos(box.minX(), box.minY(), box.minZ())
            if (device != DniDevice.COMPOUNDER) return corner
            val beside = besideOf(front)
            val chestIsTheCorner = beside.stepX < 0 || beside.stepZ < 0
            return if (chestIsTheCorner) corner.relative(beside.opposite) else corner
        }

        const val DEVICE_KEY = "device"
        const val FRONT_KEY = "front"

        /** No neighbour updates: this is worldgen. */
        const val UPDATE_FLAGS = 2
    }
}
