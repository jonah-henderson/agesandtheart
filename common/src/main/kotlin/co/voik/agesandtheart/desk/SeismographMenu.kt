package co.voik.agesandtheart.desk

import co.voik.agesandtheart.age.AgeSavedData
import co.voik.agesandtheart.age.Instability
import co.voik.agesandtheart.age.reward.Footing
import co.voik.agesandtheart.age.reward.Tremor
import co.voik.agesandtheart.age.word.Resolver
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
    val source: Int get() = reading.get(SOURCE)

    /** No slots at all, so nothing can be moved into or out of this. */
    override fun quickMoveStack(player: Player, index: Int): ItemStack = ItemStack.EMPTY

    override fun stillValid(player: Player): Boolean =
        stillValid(access, player, AgeContent.SEISMOGRAPH_BLOCK)

    companion object {
        const val FOOTING = 0
        const val BOUGHT = 1
        const val SOURCE = 2
        const val READINGS = 3

        /** Pages laid out at a desk in the room, which is what the instrument is really for. */
        const val A_SENTENCE = 0

        /** A desk in the room with nothing on it, or nothing on it that reads as a book yet. */
        const val AN_IDLE_DESK = 1

        /** No desk in the room, and the world it stands in was never written. */
        const val A_PLAIN_WORLD = 2

        /** No desk in the room, and the world it stands in is an Age. */
        const val AN_AGE = 3
    }
}

/**
 * What the instrument currently says, worked out on the server and pushed down the data slots.
 *
 * **It always reads something** (Jonah, 2026-09-07). With a sentence laid out at a desk in the room it
 * reads that; with no desk, or a desk with a bare surface, it reads **the world it is standing in** — so
 * an instrument on a shelf at home says the place is perfectly stable, and one carried into a torn Age says
 * what is wrong with it. That is what an instrument does, and it means there is no state in which the
 * screen is a complaint about where it was put.
 *
 * **Read rather than remembered**, which is the same rule the desk's own survey follows: there is no event
 * that says "somebody laid a page at a desk five blocks away", so asking is both simpler than invalidating
 * and always right.
 *
 * **But not on every ask.** Vanilla polls each data slot on every broadcast, which is three times a tick,
 * and one reading is a room scan, a parse of the whole sentence and a resolve against the corpus — the desk
 * itself only pays that when a writer *does* something. So the answer is worked out at most once every
 * [SETTLES_EVERY] ticks and every slot reads the same cached triple. The fields are the exception to
 * no-mutable-state, and a narrow one: they belong to one open screen and being stale costs a fifth of a
 * second on a readout nobody is racing.
 */
private class LiveReading(private val player: ServerPlayer?, private val pos: BlockPos) : ContainerData {

    /**
     * **Minus one rather than [Long.MIN_VALUE]**, which is the difference between a reading and no reading
     * at all: `now - Long.MIN_VALUE` overflows for every game time there is, wraps negative, and so is
     * always under [SETTLES_EVERY] — the throttle returned on every poll, `settledAt` was never written,
     * and the seismograph reported the world's own footing for ever whatever was laid at the desk.
     */
    private var settledAt = NOT_YET_SETTLED
    private var footing = Footing.STABLE.ordinal
    private var bought = NOTHING_BOUGHT
    private var source = SeismographMenu.A_PLAIN_WORLD

    /**
     * Where the desk was found, so the room is only searched while there is no desk in it.
     *
     * A desk broken while the screen is open is caught by the block entity going missing, which puts this
     * back to null and starts the search again — so the one expensive case is the one where the answer is
     * "there is no desk here" anyway.
     */
    private var deskAt: BlockPos? = null

    override fun getCount(): Int = SeismographMenu.READINGS

    override fun get(index: Int): Int {
        settle()
        return when (index) {
            SeismographMenu.FOOTING -> footing
            SeismographMenu.BOUGHT -> bought
            else -> source
        }
    }

    /** Nothing to set: this is an instrument, and a client that tried would be told otherwise next tick. */
    override fun set(index: Int, value: Int) = Unit

    private fun settle() {
        val writer = player ?: return
        val now = writer.level().gameTime
        if (now - settledAt < SETTLES_EVERY) return
        settledAt = now
        // **A desk in the room answers even when it is bare**, which is the distinction the wording turns
        // on: an instrument beside an empty desk has nothing to read, and saying the *world* is stable
        // there would be answering a question nobody asked while a book was being started (Jonah,
        // 2026-09-09).
        val laid = laidOutNearby(writer)
        val said = laid?.let { sentenceFrom(writer, it) }
        when {
            said != null -> report(said, SeismographMenu.A_SENTENCE)
            laid != null -> report(nothingAtAll(writer), SeismographMenu.AN_IDLE_DESK)
            else -> report(worldsOwn(writer), whereItStands(writer))
        }
    }

    /** What a desk in the room has laid out, resolved — or null where it has nothing that reads as a book. */
    private fun sentenceFrom(writer: ServerPlayer, laid: List<Identifier>): Tremor? {
        if (laid.isEmpty()) return null
        val server = writer.level().server
        val vocabulary = Vocabulary.of(server)
        val said = Grammar.read(vocabulary, laid.map(Identifier::getPath)) ?: return null
        val resolved = Resolver.resolve(vocabulary, said, writer.writingSeed)
        return Tremor.of(server, resolved.instability, writer.writingSeed)
    }

    private fun nothingAtAll(writer: ServerPlayer): Tremor =
        Tremor.of(writer.level().server, Instability.NONE, writer.writingSeed)

    /** Whether the world it stands in was written, which is the difference between a world and an Age. */
    private fun whereItStands(writer: ServerPlayer): Int {
        val level = writer.level()
        val here = level.dimension().identifier()
        return if (here in AgeSavedData.get(level.server).ages) SeismographMenu.AN_AGE
        else SeismographMenu.A_PLAIN_WORLD
    }

    /**
     * What the world the instrument is standing in is doing.
     *
     * **Read off the Age's own recipe**, so it is the same instability the generator built the place from
     * rather than a second opinion about it — and an ordinary world, which was never written, is exactly
     * what [Instability.NONE] describes.
     */
    private fun worldsOwn(writer: ServerPlayer): Tremor {
        val level = writer.level()
        val here = level.dimension().identifier()
        val saved = AgeSavedData.get(level.server)
        val instability = if (here in saved.ages) saved.recipe(here).instability else Instability.NONE
        return Tremor.of(level.server, instability, level.seed)
    }

    private fun report(tremor: Tremor, source: Int) {
        footing = tremor.footing.ordinal
        bought = tremor.manifests.fold(NOTHING_BOUGHT) { mask, it -> mask or (1 shl it.ordinal) }
        this.source = source
    }

    /** This writer's pages on the desk in the room, or null if there is no desk in the room. */
    private fun laidOutNearby(writer: ServerPlayer): List<Identifier>? {
        val level = writer.level()
        val known = deskAt?.let { level.getBlockEntity(it) as? WritersDeskBlockEntity }
        if (known != null) return known.compositionFor(writer.uuid)
        deskAt = null
        val workshop = WritersDesk.load(level.server.resourceManager, mutableListOf())
        val reach = workshop.radius
        val cursor = BlockPos.MutableBlockPos()
        for (x in -reach..reach) for (y in -reach..reach) for (z in -reach..reach) {
            cursor.setWithOffset(pos, x, y, z)
            val desk = level.getBlockEntity(cursor) as? WritersDeskBlockEntity ?: continue
            deskAt = cursor.immutable()
            return desk.compositionFor(writer.uuid)
        }
        return null
    }

    private companion object {
        /** How often the instrument settles, in ticks. Five a second is far more than a readout needs. */
        const val SETTLES_EVERY = 4L

        /** Before the first reading. Any negative would do; this one cannot overflow a subtraction. */
        const val NOT_YET_SETTLED = -1L
        const val NOTHING_BOUGHT = 0
    }
}
