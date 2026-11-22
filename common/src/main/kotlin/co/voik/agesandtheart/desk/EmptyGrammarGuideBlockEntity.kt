package co.voik.agesandtheart.desk

import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.content.AgeComponents
import co.voik.agesandtheart.content.AgeContent
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponentGetter
import net.minecraft.core.component.DataComponentMap
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.Container
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput

/**
 * An empty grammar guide's progress, in points of [GrammarStudy], and the one slot a hopper sees. Studying what fills it
 * turns the block into the full guide where it stands.
 *
 * **The slot is a doorway, as the archive's is**: what a hopper or a pipe puts in it is studied when the insertion is
 * committed, on [setChanged], never in [setItem], which Fabric's transfer API also calls for an insert it then rolls
 * back. Nothing can be taken out through it.
 */
class EmptyGrammarGuideBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(AgeContent.EMPTY_GRAMMAR_GUIDE_ENTITY, pos, state), Container {

    var studied: Int = 0
        private set

    private var doorway: ItemStack = ItemStack.EMPTY

    /**
     * Studies [stack] one at a time, each at once, until the guide is full, and answers what is left of it: all of it
     * where it cannot be studied, and whatever the guide had no room for once full.
     */
    fun study(stack: ItemStack): ItemStack {
        val level = level as? ServerLevel ?: return stack
        val rules = GrammarStudy.of(level.server)
        val worth = rules.worthOf(stack, Vocabulary.of(level.server))
        if (stack.isEmpty || worth <= 0) return stack
        val stillToStudy = (rules.full - studied).coerceAtLeast(0)
        val wanted = (stillToStudy + worth - 1) / worth
        val taken = minOf(stack.count, wanted.coerceAtLeast(1))
        studied += taken * worth
        setChanged()
        level.playSound(null, worldPosition, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 1f, 1f)
        if (studied >= rules.full) finish(level)
        return stack.copyWithCount(stack.count - taken)
    }

    override fun setChanged() {
        studyTheDoorway()
        super.setChanged()
    }

    private fun studyTheDoorway() {
        val arrived = doorway
        if (arrived.isEmpty) return
        doorway = ItemStack.EMPTY
        doorway = study(arrived)
    }

    /** What this guide would study: pages and survey reports the server gives a worth to. */
    private fun wouldStudy(stack: ItemStack): Boolean {
        if (!GrammarStudyMenu.mayStudy(stack)) return false
        val server = (level as? ServerLevel)?.server ?: return true
        return GrammarStudy.of(server).worthOf(stack, Vocabulary.of(server)) > 0
    }

    override fun getContainerSize(): Int = 1

    /** One at a time, so a pipe's stack never outlasts the guide it fills and goes with the block. */
    override fun getMaxStackSize(): Int = 1

    override fun isEmpty(): Boolean = doorway.isEmpty

    override fun getItem(slot: Int): ItemStack = doorway

    override fun removeItem(slot: Int, count: Int): ItemStack = ItemStack.EMPTY

    override fun removeItemNoUpdate(slot: Int): ItemStack = ItemStack.EMPTY

    override fun setItem(slot: Int, stack: ItemStack) {
        doorway = stack
    }

    override fun canPlaceItem(slot: Int, stack: ItemStack): Boolean = wouldStudy(stack)

    override fun canTakeItem(target: Container, slot: Int, stack: ItemStack): Boolean = false

    override fun stillValid(player: Player): Boolean = Container.stillValidBlockEntity(this, player)

    /** What `/setblock` and structure placement call so a replaced block drops nothing. */
    override fun clearContent() {
        doorway = ItemStack.EMPTY
    }

    private fun finish(level: ServerLevel) {
        level.setBlock(worldPosition, AgeContent.GRAMMAR_GUIDE_BLOCK.defaultBlockState(), Block.UPDATE_ALL)
        level.playSound(null, worldPosition, SoundEvents.PLAYER_LEVELUP, SoundSource.BLOCKS, 1f, 1f)
        val x = worldPosition.x + HALF
        val z = worldPosition.z + HALF
        level.sendParticles(ParticleTypes.ENCHANT, x, worldPosition.y + 1.0, z, FINISHED_PARTICLES, HALF, HALF, HALF, 1.0)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        studied = input.getIntOr(STUDIED_KEY, 0)
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        if (studied > 0) output.putInt(STUDIED_KEY, studied)
    }

    override fun applyImplicitComponents(components: DataComponentGetter) {
        super.applyImplicitComponents(components)
        studied = components.getOrDefault(AgeComponents.GRAMMAR_STUDIED, 0)
    }

    override fun collectImplicitComponents(components: DataComponentMap.Builder) {
        super.collectImplicitComponents(components)
        if (studied > 0) components.set(AgeComponents.GRAMMAR_STUDIED, studied)
    }

    /** The progress travels as the item's component, so the block's own copy is not written twice. */
    @Suppress("OVERRIDE_DEPRECATION")
    override fun removeComponentsFromTag(output: ValueOutput) {
        output.discard(STUDIED_KEY)
    }

    private companion object {
        const val STUDIED_KEY = "studied"
        const val HALF = 0.5
        const val FINISHED_PARTICLES = 40
    }
}
