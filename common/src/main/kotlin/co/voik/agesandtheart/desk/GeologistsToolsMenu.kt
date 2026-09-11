package co.voik.agesandtheart.desk

import co.voik.agesandtheart.age.AgeSavedData
import co.voik.agesandtheart.age.reward.EarlyGameRareMaterial
import co.voik.agesandtheart.age.reward.Survey
import co.voik.agesandtheart.age.reward.Yield
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
    val source: Int get() = survey.get(SOURCE)

    /** No slots at all, so nothing can be moved into or out of this. */
    override fun quickMoveStack(player: Player, index: Int): ItemStack = ItemStack.EMPTY

    override fun stillValid(player: Player): Boolean =
        stillValid(access, player, AgeContent.GEOLOGISTS_TOOLS_BLOCK)

    companion object {
        const val DEPOSIT = 0
        const val MATERIALS = 1
        const val SOURCE = 2
        const val READINGS = 3

        /** Pages laid out at a desk in the room, which is what the instrument is really for. */
        const val A_SENTENCE = 0

        /** A desk in the room with nothing on it, or nothing on it that reads as a book yet. */
        const val AN_IDLE_DESK = 1

        /** No desk in the room, and the world it stands in was never written. */
        const val A_PLAIN_WORLD = 2

        /** No desk in the room, and the world it stands in is an Age — so it reports on that. */
        const val AN_AGE = 3
    }
}

/**
 * What the tools currently say, worked out on the server and pushed down the data slots.
 *
 * **It always reads something**, which is the rule the seismograph set: with a sentence laid out at a desk
 * in the room it surveys that; with no desk it surveys **the Age it is standing in**, so a set of tools
 * carried into a world tells you what that world holds. There is no state in which the screen is a
 * complaint about where it was put.
 *
 * **Read rather than remembered**, because there is no event that says "somebody laid a page at a desk five
 * blocks away" — asking is both simpler than invalidating and always right. But not on every ask: vanilla
 * polls each data slot on every broadcast, and one survey is a room scan, a parse and a resolve against the
 * corpus. The fields are the exception to no-mutable-state and a narrow one, belonging to one open screen.
 */
private class LiveSurvey(private val player: ServerPlayer?, pos: BlockPos) : ContainerData {

    private var settledAt = NOT_YET_SETTLED
    private var deposit = Yield.NONE.ordinal
    private var materials = NOTHING_FOUND
    private var source = GeologistsToolsMenu.A_PLAIN_WORLD

    /** Which desk it is reading, and the remembering of it — see [NearbyDesk]. */
    private val desk = NearbyDesk(pos)

    override fun getCount(): Int = GeologistsToolsMenu.READINGS

    override fun get(index: Int): Int {
        settle()
        return when (index) {
            GeologistsToolsMenu.DEPOSIT -> deposit
            GeologistsToolsMenu.MATERIALS -> materials
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
        val laid = desk.laidOutBy(writer)
        val said = laid?.let { surveyOf(writer, it) }
        when {
            said != null -> report(said, GeologistsToolsMenu.A_SENTENCE)
            laid != null -> report(null, GeologistsToolsMenu.AN_IDLE_DESK)
            else -> report(worldsOwn(writer), whereItStands(writer))
        }
    }

    /** What a desk in the room has laid out, surveyed — or null where it holds nothing that reads as a book. */
    private fun surveyOf(writer: ServerPlayer, laid: List<Identifier>): Survey? {
        if (laid.isEmpty()) return null
        val server = writer.level().server
        val vocabulary = Vocabulary.of(server)
        val said = Grammar.read(vocabulary, laid.map(Identifier::getPath)) ?: return null
        val resolved = Resolver.resolve(vocabulary, said, writer.writingSeed)
        return Survey.of(server, resolved.composition, resolved.instability, writer.writingSeed)
    }

    /**
     * What the Age the tools are standing in holds.
     *
     * **Read off the Age's own recipe**, so it is the same composition the generator built the place from
     * rather than a second opinion about it. A world that was never written has no recipe and so no survey,
     * which is exactly what a plain world should report.
     */
    private fun worldsOwn(writer: ServerPlayer): Survey? {
        val level = writer.level()
        val here = level.dimension().identifier()
        val saved = AgeSavedData.get(level.server)
        if (here !in saved.ages) return null
        val recipe = saved.recipe(here)
        val composition = recipe.composition ?: return null
        return Survey.of(level.server, composition, recipe.instability, recipe.seed)
    }

    /** Whether the world it stands in was written, which is the difference between a world and an Age. */
    private fun whereItStands(writer: ServerPlayer): Int {
        val level = writer.level()
        val here = level.dimension().identifier()
        return if (here in AgeSavedData.get(level.server).ages) GeologistsToolsMenu.AN_AGE
        else GeologistsToolsMenu.A_PLAIN_WORLD
    }

    private fun report(survey: Survey?, source: Int) {
        deposit = (survey?.deposit ?: Yield.NONE).ordinal
        materials = survey?.earlyMaterials.orEmpty()
            .fold(NOTHING_FOUND) { mask, it -> mask or (1 shl it.ordinal) }
        this.source = source
    }

    private companion object {
        /** How often the instrument settles, in ticks. Five a second is far more than a readout needs. */
        const val SETTLES_EVERY = 4L

        /** Before the first reading. Any negative would do; this one cannot overflow a subtraction. */
        const val NOT_YET_SETTLED = -1L
        const val NOTHING_FOUND = 0
    }
}

/** The materials a [GeologistsToolsMenu.materials] mask names, for the screen to say back. */
fun materialsIn(mask: Int): List<EarlyGameRareMaterial> =
    EarlyGameRareMaterial.entries.filter { mask and (1 shl it.ordinal) != 0 }
