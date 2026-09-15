package co.voik.agesandtheart.desk

import co.voik.agesandtheart.age.AgeSavedData
import co.voik.agesandtheart.age.Instability
import co.voik.agesandtheart.age.reward.Footing
import co.voik.agesandtheart.age.reward.Tremor
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

/** Opens the seismograph. Everything it shows travels on two synced ints — see [SeismographMenu]. */
fun SeismographMenuProvider(pos: BlockPos): MenuProvider = SimpleMenuProvider(
    { containerId, inventory, player ->
        SeismographMenu(
            containerId,
            inventory,
            ContainerLevelAccess.create(player.level(), pos),
            LiveReading(player as? ServerPlayer, pos),
        )
    },
    Component.translatable("container.agesandtheart.seismograph"),
)

/**
 * The seismograph's screen, which has no slots and two numbers.
 *
 * **A screen of its own rather than a line on the desk's** (Jonah, 2026-09-07), which is the direction the
 * whole upgrade set is moving in: an implement that does something should be the thing you go and look at.
 * The desk stays the place you write, and stops being the place everything is reported.
 *
 * **A `ContainerData` rather than a payload of our own**, which is what makes that cheap: vanilla already
 * syncs a menu's data slots on every broadcast, so the reading follows a writer laying pages at the desk
 * *while the screen is open* with nothing of ours on the wire and nothing to register per loader. Two ints
 * carry it because that is all there is — which state the ground is in, and a bitmask of what the
 * instability bought.
 */
class SeismographMenu(
    containerId: Int,
    playerInventory: Inventory,
    private val access: ContainerLevelAccess,
    private val reading: ContainerData,
) : AbstractContainerMenu(AgeContent.SEISMOGRAPH_MENU, containerId) {

    constructor(containerId: Int, playerInventory: Inventory, access: ContainerLevelAccess) :
        this(containerId, playerInventory, access, SimpleContainerData(READINGS))

    init {
        addDataSlots(reading)
    }

    /** Which state the ground is in — a `Footing` ordinal. */
    val footing: Int get() = reading.get(FOOTING)

    /** What the instability bought, as a bitmask over `Manifestation.entries`. */
    val bought: Int get() = reading.get(BOUGHT)

    /**
     * **What it is reading**, which is what decides the calm wording rather than a second opinion about it.
     *
     * The four cases say four different things when nothing is wrong, and they are genuinely different
     * things to say: a sentence that buys nothing has no instability *yet*, a desk with a bare surface has
     * nothing to read at all, an ordinary world was never written, and a stable Age was written well.
     */
    val source: ReadingSource get() = ReadingSource.ofOrdinal(reading.get(SOURCE))

    /** No slots at all, so nothing can be moved into or out of this. */
    override fun quickMoveStack(player: Player, index: Int): ItemStack = ItemStack.EMPTY

    override fun stillValid(player: Player): Boolean =
        stillValid(access, player, AgeContent.SEISMOGRAPH_BLOCK)

    companion object {
        const val FOOTING = 0
        const val BOUGHT = 1
        const val SOURCE = 2
        const val READINGS = 3
    }
}

/**
 * What the seismograph currently says — see [InstrumentReading] for when it is worked out and what from.
 *
 * With no desk in the room it reads **the world it is standing in**, so an instrument on a shelf at home
 * says the place is perfectly stable, and one carried into a torn Age says what is wrong with it.
 */
private class LiveReading(player: ServerPlayer?, pos: BlockPos) : InstrumentReading<Tremor>(player, pos) {

    private var footing = Footing.STABLE.ordinal
    private var bought = NOTHING_BOUGHT

    override fun getCount(): Int = SeismographMenu.READINGS

    override fun get(index: Int): Int {
        settle()
        return when (index) {
            SeismographMenu.FOOTING -> footing
            SeismographMenu.BOUGHT -> bought
            else -> source.ordinal
        }
    }

    override fun ofTheSentence(writer: ServerPlayer, resolved: Resolution): Tremor =
        Tremor.of(writer.level().server, resolved.instability)

    override fun ofAnIdleDesk(writer: ServerPlayer): Tremor =
        Tremor.of(writer.level().server, Instability.NONE)

    /**
     * What the world the instrument is standing in is doing.
     *
     * **Read off the Age's own recipe**, so it is the same instability the generator built the place from
     * rather than a second opinion about it — and an ordinary world, which was never written, is exactly
     * what [Instability.NONE] describes.
     */
    override fun ofTheWorld(writer: ServerPlayer): Tremor {
        val level = writer.level()
        val here = level.dimension().identifier()
        val saved = AgeSavedData.get(level.server)
        val instability = if (here in saved.ages) saved.recipe(here).instability else Instability.NONE
        return Tremor.of(level.server, instability)
    }

    override fun record(reading: Tremor) {
        footing = reading.footing.ordinal
        bought = reading.manifests.fold(NOTHING_BOUGHT) { mask, it -> mask or (1 shl it.ordinal) }
    }

    private companion object {
        const val NOTHING_BOUGHT = 0
    }
}
