package co.voik.agesandtheart.desk

import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.content.AgeContent
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Container
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerData
import net.minecraft.world.inventory.ContainerLevelAccess
import net.minecraft.world.inventory.SimpleContainerData
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import java.util.Optional

/**
 * A full grammar guide's screen: the notebook slot, a button to study a word of each tier, and the study running.
 * Synced as data slots: the study's state, progress and tier, whether the viewer began it, and for each tier the
 * pages still wanting and the tier they are paid in.
 */
class GrammarGuideMenu(
    containerId: Int,
    playerInventory: Inventory,
    private val access: ContainerLevelAccess,
    notebookSlot: Container,
    private val shown: ContainerData,
    private val viewer: ServerPlayer? = null,
) : AbstractContainerMenu(AgeContent.GRAMMAR_GUIDE_MENU, containerId) {

    constructor(containerId: Int, playerInventory: Inventory, access: ContainerLevelAccess) :
        this(containerId, playerInventory, access, SimpleContainer(1), SimpleContainerData(DATA_SLOTS))

    enum class State { IDLE, STUDYING, DONE }

    val state: State get() = State.entries.getOrElse(shown.get(STATE)) { State.IDLE }

    /** How far the study has run, from 0 to 1. */
    val studied: Float get() = shown.get(STUDIED).toFloat() / shown.get(TO_STUDY).coerceAtLeast(1)

    val tierStudied: StudyTier get() = StudyTier.entries.getOrElse(shown.get(TIER)) { StudyTier.COMMON }

    val viewerBeganIt: Boolean get() = shown.get(VIEWER_BEGAN_IT) == 1

    fun pagesWanting(tier: StudyTier): Int = shown.get(SHORTFALLS + tier.ordinal)

    fun paidIn(tier: StudyTier): StudyTier = StudyTier.entries.getOrElse(shown.get(PAID_IN + tier.ordinal)) { StudyTier.COMMON }

    init {
        addSlot(object : Slot(notebookSlot, 0, DeskSlots.WING_INTAKE_X, DeskSlots.WING_INTAKE_Y) {
            override fun mayPlace(stack: ItemStack): Boolean = stack.item === AgeContent.NOTEBOOK

            override fun getMaxStackSize(): Int = 1
        })
        for (row in 0..<DeskSlots.INVENTORY_ROWS) {
            for (column in 0..<DeskSlots.INVENTORY_COLUMNS) {
                addSlot(
                    Slot(
                        playerInventory,
                        column + row * DeskSlots.INVENTORY_COLUMNS + DeskSlots.INVENTORY_COLUMNS,
                        DeskSlots.INVENTORY_X + column * SLOT_PITCH,
                        DeskSlots.WING_INVENTORY_Y + row * SLOT_PITCH,
                    ),
                )
            }
        }
        for (column in 0..<DeskSlots.INVENTORY_COLUMNS) {
            addSlot(Slot(playerInventory, column, DeskSlots.INVENTORY_X + column * SLOT_PITCH, DeskSlots.WING_HOTBAR_Y))
        }
        addDataSlots(shown)
    }

    /** A study finishing while the reader who began it is looking is theirs at once. */
    override fun broadcastChanges() {
        if (viewer != null && state == State.DONE && viewerBeganIt) guide()?.collect(viewer)
        super.broadcastChanges()
    }

    /** A button is a tier, by its ordinal. */
    override fun clickMenuButton(player: Player, id: Int): Boolean {
        val reader = player as? ServerPlayer ?: return false
        val tier = StudyTier.entries.getOrNull(id) ?: return false
        return guide()?.begin(tier, reader) == true
    }

    override fun quickMoveStack(player: Player, index: Int): ItemStack {
        val slot = slots.getOrNull(index) ?: return ItemStack.EMPTY
        if (!slot.hasItem()) return ItemStack.EMPTY
        val moved = slot.item
        val original = moved.copy()
        val wentWhereItCould = if (index == NOTEBOOK_SLOT) {
            moveItemStackTo(moved, FIRST_PLAYER_SLOT, slots.size, true)
        } else {
            moved.item === AgeContent.NOTEBOOK && moveItemStackTo(moved, NOTEBOOK_SLOT, FIRST_PLAYER_SLOT, false)
        }
        if (!wentWhereItCould) return ItemStack.EMPTY
        if (moved.isEmpty) slot.setByPlayer(ItemStack.EMPTY) else slot.setChanged()
        return original
    }

    override fun stillValid(player: Player): Boolean = stillValid(access, player, AgeContent.GRAMMAR_GUIDE_BLOCK)

    private fun guide(): GrammarGuideBlockEntity? = access.evaluate(
        { level, pos -> Optional.ofNullable(level.getBlockEntity(pos) as? GrammarGuideBlockEntity) },
        Optional.empty(),
    ).orElse(null)

    companion object {
        private const val SLOT_PITCH = 18
        private const val NOTEBOOK_SLOT = 0
        private const val FIRST_PLAYER_SLOT = 1

        private const val STATE = 0
        private const val STUDIED = 1
        private const val TO_STUDY = 2
        private const val TIER = 3
        private const val VIEWER_BEGAN_IT = 4
        private const val SHORTFALLS = 5
        private val PAID_IN = SHORTFALLS + StudyTier.entries.size
        private val DATA_SLOTS = PAID_IN + StudyTier.entries.size

        /** The server's menu, reading the guide at [access] live for [viewer]. */
        fun serving(containerId: Int, playerInventory: Inventory, access: ContainerLevelAccess, viewer: ServerPlayer): GrammarGuideMenu {
            val guide = access.evaluate(
                { level, pos -> Optional.ofNullable(level.getBlockEntity(pos) as? GrammarGuideBlockEntity) },
                Optional.empty(),
            ).orElse(null)
            val server = viewer.level().server
            val live = object : ContainerData {
                override fun get(index: Int): Int {
                    val study = guide?.study
                    val rules = GrammarStudy.of(server)
                    return when (index) {
                        STATE -> when {
                            study == null -> State.IDLE
                            study.isDone -> State.DONE
                            else -> State.STUDYING
                        }.ordinal
                        STUDIED -> study?.ticksStudied ?: 0
                        TO_STUDY -> study?.ticksToStudy ?: 0
                        TIER -> study?.tier?.ordinal ?: 0
                        VIEWER_BEGAN_IT -> if (study?.reader == viewer.uuid) 1 else 0
                        in SHORTFALLS..<PAID_IN -> {
                            val tier = StudyTier.entries[index - SHORTFALLS]
                            guide?.shortfalls(rules, Vocabulary.of(server))?.get(tier) ?: 0
                        }
                        else -> rules.trades[StudyTier.entries[index - PAID_IN]]?.paidIn?.ordinal ?: 0
                    }
                }

                override fun set(index: Int, value: Int) = Unit

                override fun getCount(): Int = DATA_SLOTS
            }
            return GrammarGuideMenu(containerId, playerInventory, access, guide?.notebookSlot ?: SimpleContainer(1), live, viewer)
        }
    }
}
