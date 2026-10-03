package co.voik.agesandtheart.content

import co.voik.agesandtheart.age.reward.ScarabHabitat
import co.voik.agesandtheart.location
import co.voik.agesandtheart.page.Mastery
import co.voik.agesandtheart.page.MasterySubject
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.UUIDUtil
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.level.material.PushReaction
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.BlockHitResult
import java.util.UUID

/**
 * The D'ni advanced analysis machine (design §7.1.2): found broken in a city, woken with nara probes, and
 * then the place the summit words are earned. The ordinary machine names what a player has handled; this
 * one names what they have shown they can reproduce, by studying the living thing beside it.
 *
 * **Waking it is nara's mastery** (Jonah, "for now"): nara must already be running before the machine can,
 * so the first thing it names is what woke it. The scarab and the yema are each a study of a day's watching,
 * of a nest with a scarab housed in it or a heart in its band, in the machine's reach. Nests and hearts
 * cannot be carried, so one standing here was made here.
 */
class AdvancedAnalysisMachineBlock(properties: Properties) : BaseEntityBlock(properties) {

    init {
        val asFound = PARTS.values.fold(stateDefinition.any()) { state, fitted -> state.setValue(fitted, false) }
        registerDefaultState(asFound.setValue(AWAKE, false).setValue(STUDYING, false))
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        AdvancedAnalysisMachineBlockEntity(pos, state)

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(AWAKE, STUDYING)
        PARTS.values.forEach { builder.add(it) }
    }

    override fun <T : BlockEntity> getTicker(
        level: Level,
        state: BlockState,
        type: BlockEntityType<T>,
    ): BlockEntityTicker<T>? =
        if (level.isClientSide) null
        else createTickerHelper(type, AdvancedAnalysisMachine.ENTITY) { tickLevel, pos, tickState, machine ->
            (tickLevel as? ServerLevel)?.let { machine.watch(it, pos, tickState) }
        }

    override fun useItemOn(
        stack: ItemStack,
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hitResult: BlockHitResult,
    ): InteractionResult {
        val part = MachinePart.entries.firstOrNull { stack.`is`(it.item()) }
        val fitsSomething = part != null && !state.getValue(PARTS.getValue(part))
        if (!fitsSomething) return InteractionResult.TRY_WITH_EMPTY_HAND
        if (level.isClientSide) return InteractionResult.SUCCESS
        val serverLevel = level as? ServerLevel ?: return InteractionResult.FAIL
        val serverPlayer = player as? ServerPlayer ?: return InteractionResult.FAIL
        stack.consume(1, player)
        val fitted = state.setValue(PARTS.getValue(part), true)
        val isWhole = PARTS.values.all { fitted.getValue(it) }
        level.setBlock(pos, fitted.setValue(AWAKE, isWhole), UPDATE_ALL)
        if (!isWhole) {
            level.playSound(null, pos, SoundEvents.ANVIL_USE, SoundSource.BLOCKS, A_QUIET_FIT, 1.0f)
            serverPlayer.sendSystemMessage(stillWanting(fitted), true)
            return InteractionResult.CONSUME
        }
        celebrate(serverLevel, pos)
        serverPlayer.sendSystemMessage(Component.translatable(AdvancedAnalysisMachine.WOKEN))
        Mastery.grant(serverPlayer, MasterySubject.NARA)
        return InteractionResult.CONSUME
    }

    /** What the machine still lacks, said as its broken message names it. */
    private fun stillWanting(state: BlockState): Component {
        val missing = MachinePart.entries.filterNot { state.getValue(PARTS.getValue(it)) }
            .map { Component.translatable(it.lacking) }
        val named = missing.reduce { list, next -> Component.translatable(AdvancedAnalysisMachine.AND, list, next) }
        return Component.translatable(AdvancedAnalysisMachine.BROKEN, named)
    }

    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hitResult: BlockHitResult,
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val serverLevel = level as? ServerLevel ?: return InteractionResult.FAIL
        val serverPlayer = player as? ServerPlayer ?: return InteractionResult.FAIL
        if (!state.getValue(AWAKE)) {
            serverPlayer.sendSystemMessage(stillWanting(state), true)
            return InteractionResult.CONSUME
        }
        val machine = level.getBlockEntity(pos) as? AdvancedAnalysisMachineBlockEntity ?: return InteractionResult.FAIL
        serverPlayer.sendSystemMessage(machine.consult(serverLevel, serverPlayer), true)
        return InteractionResult.CONSUME
    }

    override fun animateTick(state: BlockState, level: Level, pos: BlockPos, random: RandomSource) {
        if (!state.getValue(STUDYING)) return
        level.addParticle(
            ParticleTypes.ENCHANT,
            pos.x + random.nextDouble(),
            pos.y + ABOVE_THE_MACHINE + random.nextDouble(),
            pos.z + random.nextDouble(),
            random.nextGaussian() * DRIFT,
            -random.nextDouble(),
            random.nextGaussian() * DRIFT,
        )
    }

    companion object {
        /** Woken once every one of its [PARTS] is fitted; until then, broken. */
        val AWAKE: BooleanProperty = BooleanProperty.create("awake")

        /** One property a part, named for it: `probes`, `ink`, `paper`. */
        val PARTS: Map<MachinePart, BooleanProperty> =
            MachinePart.entries.associateWith { BooleanProperty.create(it.key) }

        private const val A_QUIET_FIT = 0.5f
        val STUDYING: BooleanProperty = BooleanProperty.create("studying")

        private const val ABOVE_THE_MACHINE = 1.2
        private const val DRIFT = 0.5

        private const val BURST = 40
        private const val BURST_SPREAD = 0.6

        /** The pomp a mastery is owed: a burst and a chime. */
        fun celebrate(level: ServerLevel, pos: BlockPos) {
            level.sendParticles(
                ParticleTypes.TOTEM_OF_UNDYING,
                pos.x + 0.5, pos.y + 1.2, pos.z + 0.5,
                BURST, BURST_SPREAD, BURST_SPREAD, BURST_SPREAD, BURST_SPREAD,
            )
            level.playSound(null, pos, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 1.0f, 1.0f)
            level.playSound(null, pos, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.BLOCKS, 1.0f, 1.0f)
        }
    }
}

/**
 * The study in hand: what is being watched, where, for whom, and for how long so far. Counted in ticks
 * the machine has actually watched, so a study cannot be left to run out somewhere unloaded — where a
 * tree neither dries nor drowns.
 */
data class MasteryStudy(val subject: MasterySubject, val at: BlockPos, val student: UUID, val watched: Int) {
    val isFinished: Boolean get() = watched >= A_DAY

    companion object {
        /** A full day (Jonah): long enough that a bonemealed tree with no flood cycle leaves its band first. */
        const val A_DAY = 24_000

        val CODEC: Codec<MasteryStudy> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.STRING.xmap({ MasterySubject.byKey(it) ?: MasterySubject.YEMA }, MasterySubject::key)
                    .fieldOf("subject").forGetter { it.subject },
                BlockPos.CODEC.fieldOf("at").forGetter { it.at },
                UUIDUtil.CODEC.fieldOf("student").forGetter { it.student },
                Codec.INT.fieldOf("watched").forGetter { it.watched },
            ).apply(instance, ::MasteryStudy)
        }
    }
}

class AdvancedAnalysisMachineBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(AdvancedAnalysisMachine.ENTITY, pos, state) {

    private var study: MasteryStudy? = null

    /** A tick: every so often, whether the subject still stands as it must, and the study a little further. */
    fun watch(level: ServerLevel, pos: BlockPos, state: BlockState) {
        val current = study
        val isStudying = current != null && !current.isFinished
        if (state.getValue(AdvancedAnalysisMachineBlock.STUDYING) != isStudying) {
            level.setBlock(pos, state.setValue(AdvancedAnalysisMachineBlock.STUDYING, isStudying), Block.UPDATE_ALL)
        }
        if (current == null) return
        if (current.isFinished) return finish(level, current)
        if ((level.gameTime + pos.asLong()) % LOOKS_EVERY != 0L) return
        if (!stillStands(level, current)) return fail(level, current)
        study = current.copy(watched = current.watched + LOOKS_EVERY.toInt())
        setChanged()
    }

    /** What the machine says to a player who uses it, starting a study where there is one to start. */
    fun consult(level: ServerLevel, player: ServerPlayer): Component {
        val current = study
        if (current != null && current.student != player.uuid) return say(OTHERS_STUDY)
        if (current != null && current.isFinished) {
            finish(level, current)
            return Component.empty()
        }
        if (current != null) return say(howFarSaid(current.watched), subjectName(current.subject))
        val unlearned = listOf(MasterySubject.SCARAB, MasterySubject.YEMA).filterNot { Mastery.knows(player, it) }
        if (unlearned.isEmpty()) return say(NOTHING_LEFT)
        val found = unlearned.firstNotNullOfOrNull { subject -> subjectNear(level, subject)?.let { subject to it } }
            ?: return say(NOTHING_TO_STUDY)
        study = MasteryStudy(found.first, found.second, player.uuid, 0)
        setChanged()
        return say(BEGINS, subjectName(found.first))
    }

    private fun finish(level: ServerLevel, finished: MasteryStudy) {
        val student = level.server.playerList.getPlayer(finished.student) ?: return
        study = null
        setChanged()
        AdvancedAnalysisMachineBlock.celebrate(level, blockPos)
        student.sendSystemMessage(say(MASTERED, subjectName(finished.subject)))
        Mastery.grant(student, finished.subject)
    }

    private fun fail(level: ServerLevel, failed: MasteryStudy) {
        study = null
        setChanged()
        level.playSound(null, blockPos, SoundEvents.BEACON_DEACTIVATE, SoundSource.BLOCKS, 1.0f, 1.0f)
        level.server.playerList.getPlayer(failed.student)
            ?.sendSystemMessage(say(failureFor(failed.subject), subjectName(failed.subject)))
    }

    /** Where in reach the machine would find [subject] standing as a study needs it, or null. */
    private fun subjectNear(level: ServerLevel, subject: MasterySubject): BlockPos? = when (subject) {
        MasterySubject.SCARAB -> ScarabHabitat.nestsNear(level, blockPos, REACH).firstOrNull { isHoused(level, it) }
        MasterySubject.YEMA -> BlockPos.betweenClosedStream(blockPos.offset(-REACH, -REACH, -REACH), blockPos.offset(REACH, REACH, REACH))
            .filter { isHealthyHeart(level, it) }
            .findFirst()
            .map(BlockPos::immutable)
            .orElse(null)
        MasterySubject.NARA -> null
    }

    private fun stillStands(level: ServerLevel, watched: MasteryStudy): Boolean = when (watched.subject) {
        MasterySubject.SCARAB -> isHoused(level, watched.at)
        MasterySubject.YEMA -> isHealthyHeart(level, watched.at)
        MasterySubject.NARA -> false
    }

    private fun isHoused(level: ServerLevel, nest: BlockPos): Boolean =
        (level.getBlockEntity(nest) as? ScarabNestBlockEntity)?.isVacant == false

    private fun isHealthyHeart(level: ServerLevel, at: BlockPos): Boolean {
        val state = level.getBlockState(at)
        if (!state.`is`(AgeContent.PAPER_TREE_ROOT_BLOCK)) return false
        return PaperTreeHealth.bandOf(state.getValue(PaperTreeHealth.MOISTURE)) == PaperTreeHealth.Band.SUITS
    }

    private fun subjectName(subject: MasterySubject): Component =
        Component.translatable("$TALK.subject.${subject.key}")

    private fun failureFor(subject: MasterySubject): String = when (subject) {
        MasterySubject.SCARAB -> FAILED_SCARAB
        MasterySubject.YEMA, MasterySubject.NARA -> FAILED_YEMA
    }

    /** No numbers to a player: how far through the day, said. */
    private fun howFarSaid(watched: Int): String = when {
        watched < MasteryStudy.A_DAY / QUARTERS -> JUST_BEGUN
        watched < MasteryStudy.A_DAY / HALVES -> A_QUARTER
        watched < MasteryStudy.A_DAY * THREE / QUARTERS -> HALF
        else -> MOST
    }

    private fun say(key: String, vararg arguments: Any): Component = Component.translatable(key, *arguments)

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        study = input.read(STUDY_KEY, MasteryStudy.CODEC).orElse(null)
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        study?.let { output.store(STUDY_KEY, MasteryStudy.CODEC, it) }
    }

    private companion object {
        /** How far the machine looks for what it studies, every way. */
        const val REACH = 8

        /** Once a second is plenty for a study a day long. */
        const val LOOKS_EVERY = 20L

        const val QUARTERS = 4
        const val HALVES = 2
        const val THREE = 3

        const val STUDY_KEY = "study"

        const val TALK = "block.agesandtheart.advanced_analysis_machine"
        const val OTHERS_STUDY = "$TALK.others_study"
        const val NOTHING_LEFT = "$TALK.nothing_left"
        const val NOTHING_TO_STUDY = "$TALK.nothing_to_study"
        const val BEGINS = "$TALK.begins"
        const val MASTERED = "$TALK.mastered"
        const val FAILED_SCARAB = "$TALK.failed.scarab"
        const val FAILED_YEMA = "$TALK.failed.yema"
        const val JUST_BEGUN = "$TALK.progress.just_begun"
        const val A_QUARTER = "$TALK.progress.a_quarter"
        const val HALF = "$TALK.progress.half"
        const val MOST = "$TALK.progress.most"
    }
}

/**
 * What the machine is found without, fitted one at a time in any order (design §7.1.2): nara probes, and the
 * masterwork ink and paper its readouts are made of. The repair is made once.
 */
enum class MachinePart(val key: String, val item: () -> Item) {
    PROBES("probes", { AdvancedAnalysisMachine.PROBES }),
    INK("ink", { AgeContent.MASTERWORK_INK_BOTTLE }),
    PAPER("paper", { AgeContent.MASTERWORK_PAPER }),
    ;

    val lacking: String get() = "block.agesandtheart.advanced_analysis_machine.lacks.$key"
}

/** The machine's registrations, for [AgeContent]'s lists. */
object AdvancedAnalysisMachine {
    val ID: Identifier = "advanced_analysis_machine".location()
    val PROBES_ID: Identifier = "compounded_stone_probes".location()

    /** Bedrock's numbers, as the compounder's: it stands where the city put it. */
    private const val UNBREAKABLE = -1.0f
    private const val UNBLASTABLE = 3_600_000.0f
    private const val PROBES_A_STACK = 16

    val BLOCK: AdvancedAnalysisMachineBlock = AdvancedAnalysisMachineBlock(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, ID))
            .mapColor(MapColor.COLOR_BLACK)
            .strength(UNBREAKABLE, UNBLASTABLE)
            .sound(SoundType.LODESTONE)
            .pushReaction(PushReaction.IMMOVEABLE)
            .noLootTable(),
    )

    /** For an operator's hand; a player only ever finds one standing. */
    val ITEM: Item = BlockItem(
        BLOCK,
        Item.Properties().setId(ResourceKey.create(Registries.ITEM, ID)).useBlockDescriptionPrefix(),
    )

    /** The machine's missing parts, which only nara can be (Jonah): its repair, and nara's mastery. */
    val PROBES: Item = Item(Item.Properties().setId(ResourceKey.create(Registries.ITEM, PROBES_ID)).stacksTo(PROBES_A_STACK))

    val ENTITY: BlockEntityType<AdvancedAnalysisMachineBlockEntity> =
        BlockEntityType({ pos, state -> AdvancedAnalysisMachineBlockEntity(pos, state) }, setOf(BLOCK))

    const val WOKEN = "block.agesandtheart.advanced_analysis_machine.woken"
    const val BROKEN = "block.agesandtheart.advanced_analysis_machine.broken"
    const val AND = "block.agesandtheart.advanced_analysis_machine.and"
}
