package co.voik.agesandtheart.desk

import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.word.grammar.Grammar
import co.voik.agesandtheart.content.AgeContent
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.MenuProvider
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerData
import net.minecraft.world.inventory.ContainerLevelAccess
import net.minecraft.world.inventory.SimpleContainerData
import net.minecraft.world.item.ItemStack

/** What a crystal viewer found to show as it was opened. */
enum class ViewerFinding {
    /** Nothing heard yet. First, because a screen opens a tick before its data slots arrive and they read nought. */
    UNHEARD,

    /** An Age to look at: the screen asks for its panel. */
    PREVIEWING,

    /** No writer's desk in the room. */
    NO_DESK,

    /** A desk, with nothing laid out on it by this writer. */
    IDLE_DESK,

    /** Words in the sentence this writer has not learned, or that are no words at all — the desk would refuse to bind it. */
    UNWRITABLE,

    /** Learned words that do not describe an Age, such as a sentence with no `age` page. */
    NOT_AN_AGE,
    ;

    companion object {
        /** The finding a synced ordinal names, or [UNHEARD] for one out of range. */
        fun ofOrdinal(ordinal: Int): ViewerFinding = entries.getOrNull(ordinal) ?: UNHEARD
    }
}

/**
 * Opens the crystal viewer onto what [writer] has laid out at a desk in the room, making its Age first.
 * Read once, as it opens: the sentence cannot change while this screen replaces the desk's.
 */
fun CrystalViewerMenuProvider(writer: ServerPlayer, pos: BlockPos): MenuProvider {
    val finding = findingFor(writer, pos)
    return SimpleMenuProvider(
        { containerId, inventory, _ ->
            CrystalViewerMenu(
                containerId,
                inventory,
                ContainerLevelAccess.create(writer.level(), pos),
                SimpleContainerData(CrystalViewerMenu.READINGS).also { it.set(CrystalViewerMenu.FINDING, finding.ordinal) },
            )
        },
        Component.translatable("container.agesandtheart.crystal_viewer"),
    )
}

/** What the viewer at [pos] has to show [writer], with the Age made where there is one. */
private fun findingFor(writer: ServerPlayer, pos: BlockPos): ViewerFinding {
    val previewable = Previewable.of(writer, NearbyDesk(pos).readFor(writer))
    if (previewable.finding != ViewerFinding.PREVIEWING) return previewable.finding
    return if (PreviewedAges.ageFor(writer, previewable.words) != null) ViewerFinding.PREVIEWING else ViewerFinding.NOT_AN_AGE
}

/**
 * Whether a writer's template can be previewed: its [words] where [finding] is [ViewerFinding.PREVIEWING],
 * and otherwise the finding that says why not. Asked by the viewer as it opens and by the desk as it closes.
 */
class Previewable private constructor(val finding: ViewerFinding, val words: List<Identifier>) {
    companion object {
        /**
         * What [read] — a template read at a desk, or null for no desk — holds to preview. Refuses what the
         * desk would refuse to bind.
         */
        fun of(writer: ServerPlayer, read: List<ReadWord>?): Previewable {
            if (read == null) return nothing(ViewerFinding.NO_DESK)
            if (read.isEmpty()) return nothing(ViewerFinding.IDLE_DESK)
            if (!TemplateReading.isWritable(read)) return nothing(ViewerFinding.UNWRITABLE)
            val words = TemplateReading.learnedWords(read)
            val vocabulary = Vocabulary.of(writer.level().server)
            val sentence = Grammar.read(vocabulary, words.map { it.path })
            if (sentence == null || sentence.isEmpty) return nothing(ViewerFinding.NOT_AN_AGE)
            return Previewable(ViewerFinding.PREVIEWING, words)
        }

        private fun nothing(why: ViewerFinding) = Previewable(why, emptyList())
    }
}

/**
 * The crystal viewer's screen, which has no slots: one synced int saying what it found, and the panel,
 * which arrives on the panel's own payloads. The panel request names no position: the server reads this
 * menu off the player instead.
 */
class CrystalViewerMenu(
    containerId: Int,
    playerInventory: Inventory,
    private val access: ContainerLevelAccess,
    private val reading: ContainerData,
) : AbstractContainerMenu(AgeContent.CRYSTAL_VIEWER_MENU, containerId) {

    constructor(containerId: Int, playerInventory: Inventory, access: ContainerLevelAccess) :
        this(containerId, playerInventory, access, SimpleContainerData(READINGS))

    init {
        addDataSlots(reading)
    }

    val finding: ViewerFinding get() = ViewerFinding.ofOrdinal(reading.get(FINDING))

    /** No slots at all, so nothing can be moved into or out of this. */
    override fun quickMoveStack(player: Player, index: Int): ItemStack = ItemStack.EMPTY

    override fun stillValid(player: Player): Boolean =
        stillValid(access, player, AgeContent.CRYSTAL_VIEWER_BLOCK)

    companion object {
        const val FINDING = 0
        const val READINGS = 1
    }
}
