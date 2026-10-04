package co.voik.agesandtheart.desk

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.age.word.learnedWords
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.location
import co.voik.agesandtheart.platform.Services
import io.netty.buffer.ByteBuf
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionResult
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.flag.FeatureFlags
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerData
import net.minecraft.world.inventory.ContainerLevelAccess
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.inventory.SimpleContainerData
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.phys.BlockHitResult

/**
 * Mixes two signals into the number a writer's next book is written at, and proposes a book for it from the
 * words they know (design §7.4).
 */
class FrequencyTunerBlock(properties: BlockBehaviour.Properties) : Block(properties) {

    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hit: BlockHitResult,
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val writer = player as? ServerPlayer ?: return InteractionResult.CONSUME
        val room = NearbyDesk(pos)
        writer.openMenu(
            SimpleMenuProvider(
                { containerId, inventory, _ ->
                    FrequencyTunerMenu(containerId, inventory, ContainerLevelAccess.create(level, pos), TunerReading(writer, room), room)
                },
                name,
            ),
        )
        FrequencyTunerMenu.sendProposal(writer, room)
        return InteractionResult.CONSUME
    }
}

/**
 * The tuner's screen: six dials and two flags on synced ints, and the proposed book on [TunerProposalPayload].
 *
 * A dial is moved by a menu button whose id says which dial and which step, so turning one needs nothing of
 * ours on the wire; the power switch and copying to the desk are a button each.
 */
class FrequencyTunerMenu(
    containerId: Int,
    @Suppress("UNUSED_PARAMETER") playerInventory: Inventory,
    private val access: ContainerLevelAccess,
    private val reading: ContainerData,
    private val room: NearbyDesk?,
) : AbstractContainerMenu(FrequencyTuner.MENU, containerId) {

    /** The client's. */
    constructor(containerId: Int, playerInventory: Inventory) :
        this(containerId, playerInventory, ContainerLevelAccess.NULL, SimpleContainerData(READINGS), null)

    init {
        addDataSlots(reading)
    }

    /** The dials as the server last said, centred where the writer has not tuned. */
    val tuning: Tuning get() = Tuning.ofDials((0..<Tuning.DIALS).map(reading::get)) ?: Tuning.CENTRED

    /** Whether the tuner is on, and so choosing the seed rather than letting the desk draw it. */
    val isPowered: Boolean get() = reading.get(POWERED) == 1

    /** Whether a writer's desk stands in the room to load the proposal into. */
    val hasADesk: Boolean get() = reading.get(DESK) == 1

    override fun clickMenuButton(player: Player, id: Int): Boolean {
        val writer = player as? ServerPlayer ?: return false
        val room = room ?: return false
        val tuned = writer.tuning ?: Tuning.CENTRED
        when (id) {
            COPY -> return copyToTheDesk(writer, room)
            POWER -> writer.tuning = tuned.copy(powered = !tuned.powered)
            in 0..<Tuning.DIALS * Tuning.STEPS -> writer.tuning = tuned.withDial(id / Tuning.STEPS, id % Tuning.STEPS)
            else -> return false
        }
        sendProposal(writer, room)
        return true
    }

    /**
     * The proposed book becomes the writer's template at the desk in the room — whatever it would cost, which
     * is the desk's to say. Only while the tuner is on, since off it shows no proposal to copy.
     */
    private fun copyToTheDesk(writer: ServerPlayer, room: NearbyDesk): Boolean {
        if (writer.tuning?.powered != true) return false
        val desk = room.deskIn(writer.level()) ?: return false
        val proposed = proposalFor(writer, room)
        if (proposed.isEmpty()) return false
        desk.setTemplate(writer.uuid, DeskTemplates.typed(proposed))
        access.execute { level, pos ->
            level.playSound(null, pos, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 1f, 1f)
        }
        return true
    }

    override fun quickMoveStack(player: Player, index: Int): ItemStack = ItemStack.EMPTY

    override fun stillValid(player: Player): Boolean = stillValid(access, player, FrequencyTuner.BLOCK)

    companion object {
        const val POWERED = Tuning.DIALS
        const val DESK = Tuning.DIALS + 1
        const val READINGS = Tuning.DIALS + 2

        const val COPY = 1000
        const val POWER = 1001

        /** Proposals are short so they fit a desk short of furnished; a furnished one allows more. */
        private const val MOST_PAGES_PROPOSED = 9

        fun sendProposal(writer: ServerPlayer, room: NearbyDesk) {
            Services.NETWORK.sendToPlayer(writer, TunerProposalPayload(proposalFor(writer, room)))
        }

        /** The book the writer's signal proposes, within what the desk in the room — or a bare one — can bind. */
        private fun proposalFor(writer: ServerPlayer, room: NearbyDesk): List<Identifier> {
            val server = writer.level().server
            val study = WritersDesk.of(server)
            val deskLimit = room.deskIn(writer.level())?.capabilities(study)?.pageLimit
                ?: study.pageLimitFor(0)
            val limit = minOf(deskLimit ?: MOST_PAGES_PROPOSED, MOST_PAGES_PROPOSED)
            return ProposedBook.of(WritersDeskMenu.vocabularyFor(writer), writer.learnedWords.words, writer.writingSeed, limit)
        }
    }
}

/** The server's side of the tuner's ints, read live off the writer and the room. */
private class TunerReading(private val writer: ServerPlayer, private val room: NearbyDesk) : ContainerData {

    private var hasADesk = false

    /** When the room was last searched, or null before the first look. */
    private var deskLookedForAt: Long? = null

    override fun get(index: Int): Int = when (index) {
        FrequencyTunerMenu.POWERED -> if (writer.tuning?.powered == true) 1 else 0
        FrequencyTunerMenu.DESK -> if (deskIsInTheRoom()) 1 else 0
        else -> (writer.tuning ?: Tuning.CENTRED).dials[index]
    }

    /** Looked for once a second: data slots are read every tick, and an empty room is searched in full. */
    private fun deskIsInTheRoom(): Boolean {
        val now = writer.level().gameTime
        // Null rather than Long.MIN_VALUE, which `now -` would overflow to a negative that is never due.
        val isDue = deskLookedForAt?.let { now - it >= LOOKS_EVERY } ?: true
        if (isDue) {
            hasADesk = room.deskIn(writer.level()) != null
            deskLookedForAt = now
        }
        return hasADesk
    }

    override fun set(index: Int, value: Int) = Unit

    override fun getCount(): Int = FrequencyTunerMenu.READINGS

    private companion object {
        const val LOOKS_EVERY = 20L
    }
}

/** The book the tuner proposes for the writer's current signal, as word ids; empty where it has none. */
data class TunerProposalPayload(val words: List<Identifier>) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<TunerProposalPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<TunerProposalPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "tuner_proposal"))

        val STREAM_CODEC: StreamCodec<ByteBuf, TunerProposalPayload> =
            Identifier.STREAM_CODEC.apply(ByteBufCodecs.list()).map(::TunerProposalPayload, TunerProposalPayload::words)
    }
}

/** The tuner's registrations, for [AgeContent]'s lists. */
object FrequencyTuner {
    val ID: Identifier = "frequency_tuner".location()

    val BLOCK: FrequencyTunerBlock = FrequencyTunerBlock(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, ID))
            .mapColor(MapColor.COLOR_LIGHT_BLUE)
            .strength(AgeContent.STUDY_STRENGTH)
            .sound(SoundType.COPPER),
    )

    val ITEM: Item = DeskImplementItem(BLOCK, Item.Properties().setId(ResourceKey.create(Registries.ITEM, ID)).useBlockDescriptionPrefix())

    val MENU: MenuType<FrequencyTunerMenu> = MenuType(
        { containerId, inventory -> FrequencyTunerMenu(containerId, inventory) },
        FeatureFlags.VANILLA_SET,
    )
}
