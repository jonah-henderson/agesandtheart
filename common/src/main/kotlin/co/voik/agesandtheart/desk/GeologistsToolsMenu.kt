package co.voik.agesandtheart.desk

import co.voik.agesandtheart.age.AgeRecipe
import co.voik.agesandtheart.age.reward.EarlyGameRareMaterial
import co.voik.agesandtheart.age.reward.Survey
import co.voik.agesandtheart.age.reward.Yield
import co.voik.agesandtheart.age.word.Resolution
import co.voik.agesandtheart.content.AgeContent
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
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

/** Opens the geologist's tools. Everything it shows travels on three synced ints — see [GeologistsToolsMenu]. */
fun GeologistsToolsMenuProvider(pos: BlockPos): MenuProvider = SimpleMenuProvider(
    { containerId, inventory, player ->
        GeologistsToolsMenu(
            containerId,
            inventory,
            ContainerLevelAccess.create(player.level(), pos),
            LiveSurvey(player as? ServerPlayer, pos),
        )
    },
    Component.translatable("container.agesandtheart.geologists_tools"),
)

/**
 * The geologist's tools' screen: what an Age would hold, and roughly how much (design §7.7).
 *
 * **Off the writer's desk and onto the block, which is the second time that rule has been applied and so
 * is now the rule** (Jonah, 2026-09-10): the desk is where you *write*, and every other implement owns its
 * own screen. A readout crowded onto the desk's panel is a paragraph; a block you walk up to is an
 * instrument. `SeismographBlock` stated it first on 2026-09-07 and this follows it exactly.
 *
 * **Three ints, for the reason the seismograph has two.** A `ContainerData` is synced by vanilla on every
 * broadcast, so the reading follows a writer laying pages at a desk *while the screen is open* with nothing
 * of ours on the wire and nothing to register per loader. A [Yield] is an ordinal and the early materials
 * are a bitmask over three, so a survey fits in what is already being sent.
 *
 * **Quantities and names, never a danger forecast** — see [Survey]. Saying what the danger *was* would be a
 * preview and would move §7.5's line; saying what comes out of the ground is an outcome, and it survives an
 * evocative word the writer themselves cannot unpack.
 */
class GeologistsToolsMenu(
    containerId: Int,
    playerInventory: Inventory,
    private val access: ContainerLevelAccess,
    private val survey: ContainerData,
) : AbstractContainerMenu(AgeContent.GEOLOGISTS_TOOLS_MENU, containerId) {

    constructor(containerId: Int, playerInventory: Inventory, access: ContainerLevelAccess) :
        this(containerId, playerInventory, access, SimpleContainerData(READINGS))

    init {
        addDataSlots(survey)
    }

    /** How much of the danger material there is, as a [Yield] ordinal. */
    val deposit: Int get() = survey.get(DEPOSIT)

    /** Which early materials the Age would grow, as a bitmask over `EarlyGameRareMaterial.entries`. */
    val materials: Int get() = survey.get(MATERIALS)

    /**
     * **What it is reading**, which decides the wording rather than a second opinion about it.
     *
     * A sentence that would hold nothing and a world that holds nothing say different things, and so does
     * an instrument with no desk and no Age to report on.
     */
    val source: ReadingSource get() = ReadingSource.ofOrdinal(survey.get(SOURCE))

    /** No slots at all, so nothing can be moved into or out of this. */
    override fun quickMoveStack(player: Player, index: Int): ItemStack = ItemStack.EMPTY

    override fun stillValid(player: Player): Boolean =
        stillValid(access, player, AgeContent.GEOLOGISTS_TOOLS_BLOCK)

    companion object {
        const val DEPOSIT = 0
        const val MATERIALS = 1
        const val SOURCE = 2
        const val READINGS = 3
    }
}

/**
 * What the tools currently say — see [InstrumentReading] for when it is worked out and what from.
 *
 * With no desk in the room they survey **the Age they are standing in**, so a set of tools carried into a
 * world tells you what that world holds.
 */
private class LiveSurvey(player: ServerPlayer?, pos: BlockPos) : InstrumentReading<Survey?>(player, pos) {

    private var deposit = Yield.NONE.ordinal
    private var materials = NOTHING_FOUND

    override fun getCount(): Int = GeologistsToolsMenu.READINGS

    override fun get(index: Int): Int {
        settle()
        return when (index) {
            GeologistsToolsMenu.DEPOSIT -> deposit
            GeologistsToolsMenu.MATERIALS -> materials
            else -> source.ordinal
        }
    }

    override fun ofTheSentence(writer: ServerPlayer, resolved: Resolution): Survey? =
        Survey.of(writer.level().server, resolved.composition, resolved.instability, writer.writingSeed)

    override fun ofAnIdleDesk(writer: ServerPlayer): Survey? = null

    /**
     * What the Age the tools are standing in holds.
     *
     * **Read off the Age's own recipe**, so it is the same composition the generator built the place from
     * rather than a second opinion about it. A world that was never written has no recipe and so no survey,
     * which is exactly what a plain world should report.
     */
    override fun ofTheWorld(writer: ServerPlayer, recipe: AgeRecipe?): Survey? {
        if (recipe == null) return null
        val composition = recipe.composition ?: return null
        return Survey.of(writer.level().server, composition, recipe.instability, recipe.seed)
    }

    override fun record(reading: Survey?) {
        deposit = (reading?.deposit ?: Yield.NONE).ordinal
        materials = reading?.earlyMaterials.orEmpty()
            .fold(NOTHING_FOUND) { mask, it -> mask or (1 shl it.ordinal) }
    }

    private companion object {
        const val NOTHING_FOUND = 0
    }
}

/** The materials a [GeologistsToolsMenu.materials] mask names, for the screen to say back. */
fun materialsIn(mask: Int): List<EarlyGameRareMaterial> =
    EarlyGameRareMaterial.entries.filter { mask and (1 shl it.ordinal) != 0 }
