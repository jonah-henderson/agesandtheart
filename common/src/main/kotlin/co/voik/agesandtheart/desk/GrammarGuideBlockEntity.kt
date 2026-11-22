package co.voik.agesandtheart.desk

import co.voik.agesandtheart.advancement.LearnedBy
import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.word.learnedWords
import co.voik.agesandtheart.age.words.Word
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.content.NotebookItem
import co.voik.agesandtheart.page.PageLearning
import co.voik.agesandtheart.page.PageWordFunction
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.NonNullList
import net.minecraft.core.UUIDUtil
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.ContainerHelper
import net.minecraft.world.Containers
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.ExperienceOrb
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * A full grammar guide: the notebook in its slot, and the one study it may be running, a new word of a tier for the
 * reader who began it. The study runs while the guide is loaded, as a furnace smelts, and waits once done until that
 * reader uses the guide again.
 */
class GrammarGuideBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(AgeContent.GRAMMAR_GUIDE_ENTITY, pos, state) {

    /** The notebook slot, kept in the guide as a furnace keeps what it is smelting. */
    val notebookSlot: SimpleContainer = object : SimpleContainer(1) {
        override fun setChanged() {
            super.setChanged()
            this@GrammarGuideBlockEntity.setChanged()
        }
    }

    var study: Study? = null
        private set

    /** How many more pages the notebook in the slot needs to study a word of each tier. */
    fun shortfalls(rules: GrammarStudy, vocabulary: Vocabulary): Map<StudyTier, Int> {
        val held = tiersHeld(rules, vocabulary)
        return rules.trades.mapValues { (_, trade) -> trade.shortfall(held) }
    }

    /** Begins studying a word of [tier] for [reader], paying with the notebook's pages; false where it cannot. */
    fun begin(tier: StudyTier, reader: ServerPlayer): Boolean {
        val level = level as? ServerLevel ?: return false
        if (study != null) return false
        val rules = GrammarStudy.of(level.server)
        val trade = rules.trades[tier] ?: return false
        val notebook = notebookSlot.getItem(0)
        val paid = trade.paidWith(tiersHeld(rules, Vocabulary.of(level.server))) ?: return false
        val kept = NotebookItem.pagesIn(notebook).filterIndexed { index, _ -> index !in paid }
        if (kept.isEmpty()) {
            notebookSlot.setItem(0, ItemStack.EMPTY)
        } else {
            val left = notebook.copy()
            NotebookItem.setPages(left, kept)
            notebookSlot.setItem(0, left)
        }
        study = Study(tier, reader.uuid, ticksStudied = 0, ticksToStudy = trade.ticks)
        setChanged()
        level.playSound(null, worldPosition, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 1f, 1f)
        return true
    }

    /** Hands a finished study to [reader] if they began it: a word they have not learned, or experience. */
    fun collect(reader: ServerPlayer) {
        val level = level as? ServerLevel ?: return
        val finished = study?.takeIf { it.isDone && it.reader == reader.uuid } ?: return
        study = null
        setChanged()
        val rules = GrammarStudy.of(level.server)
        val trade = rules.trades[finished.tier] ?: return
        val word = unlearnedWord(trade, reader, level)
        if (word != null) {
            PageLearning.teach(reader, listOf(word.id), LearnedBy.STUDY)
            level.playSound(null, worldPosition, SoundEvents.PLAYER_LEVELUP, SoundSource.BLOCKS, 1f, 1f)
        } else {
            val points = trade.experience.least + reader.random.nextInt(trade.experience.most - trade.experience.least + 1)
            ExperienceOrb.award(level, Vec3.atCenterOf(worldPosition).add(0.0, HALF, 0.0), points)
            reader.sendOverlayMessage(Component.translatable(ALL_KNOWN))
        }
    }

    private fun unlearnedWord(trade: GrammarTrade, reader: ServerPlayer, level: ServerLevel): Word? {
        val vocabulary = Vocabulary.of(level.server)
        val registries = level.registryAccess()
        fun mayBeGiven(word: Word): Boolean {
            val isNewToTheReader = !reader.learnedWords.knows(word.id)
            return isNewToTheReader && PageWordFunction.canBeFound(word, vocabulary, registries)
        }
        return vocabulary.rarity.draw(vocabulary, reader.random, trade.buckets, keep = ::mayBeGiven)
    }

    private fun tiersHeld(rules: GrammarStudy, vocabulary: Vocabulary): List<StudyTier> =
        NotebookItem.pagesIn(notebookSlot.getItem(0)).map { rules.tierOf(it, vocabulary) }

    fun tick(level: ServerLevel) {
        val running = study?.takeUnless { it.isDone } ?: return
        study = running.copy(ticksStudied = running.ticksStudied + 1)
        setChanged()
        if (study?.isDone == true) {
            level.playSound(null, worldPosition, SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.BLOCKS, 1f, 1f)
        }
    }

    override fun preRemoveSideEffects(pos: BlockPos, state: BlockState) {
        level?.let { Containers.dropContents(it, pos, notebookSlot) }
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        notebookSlot.clearContent()
        val items = NonNullList.withSize(1, ItemStack.EMPTY)
        ContainerHelper.loadAllItems(input, items)
        notebookSlot.setItem(0, items[0])
        study = input.read(STUDY_KEY, Study.CODEC).orElse(null)
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        ContainerHelper.saveAllItems(output, NonNullList.of(ItemStack.EMPTY, notebookSlot.getItem(0)))
        study?.let { output.store(STUDY_KEY, Study.CODEC, it) }
    }

    /** A word of [tier] being studied for [reader]. */
    data class Study(val tier: StudyTier, val reader: UUID, val ticksStudied: Int, val ticksToStudy: Int) {
        val isDone: Boolean get() = ticksStudied >= ticksToStudy

        companion object {
            val CODEC: Codec<Study> = RecordCodecBuilder.create { instance ->
                instance.group(
                    StudyTier.CODEC.fieldOf("tier").forGetter(Study::tier),
                    UUIDUtil.CODEC.fieldOf("reader").forGetter(Study::reader),
                    Codec.INT.fieldOf("studied").forGetter(Study::ticksStudied),
                    Codec.INT.fieldOf("to_study").forGetter(Study::ticksToStudy),
                ).apply(instance, ::Study)
            }
        }
    }

    private companion object {
        const val STUDY_KEY = "study"
        const val HALF = 0.5
        const val ALL_KNOWN = "container.agesandtheart.grammar_guide.all_known"
    }
}
