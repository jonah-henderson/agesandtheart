package co.voik.agesandtheart.optics

import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.content.RimeColour
import net.minecraft.core.BlockPos
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Prediction
import net.minecraft.world.Containers
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput

/**
 * A rime crystal slotted into an optical block: one used on it goes in, handing back any it held, and an empty hand
 * takes it out. Kept by a block entity, which drops it with the block.
 */
interface CrystalHolder {
    var crystal: RimeColour?

    companion object {
        private const val KEY = "crystal"

        fun colourOf(stack: ItemStack): RimeColour? = AgeContent.RIME_CRYSTALS.entries.firstOrNull { it.value == stack.item }?.key

        /** Slots the crystal [player] holds into [holder], or takes [holder]'s out with an empty hand. */
        fun use(holder: BlockEntity, held: ItemStack, player: Player, level: Level, pos: BlockPos): InteractionResult {
            val crystalHolder = holder as? CrystalHolder ?: return InteractionResult.PASS
            val offered = colourOf(held)
            val takingOut = held.isEmpty && crystalHolder.crystal != null
            if (offered == null && !takingOut) return InteractionResult.PASS
            if (level.isClientSide) return InteractionResult.SUCCESS
            crystalHolder.crystal?.let { player.inventory.placeItemBackInInventory(ItemStack(AgeContent.RIME_CRYSTALS.getValue(it)), Prediction.SERVER_ONLY) }
            crystalHolder.crystal = offered
            if (offered != null) held.consume(1, player)
            holder.setChanged()
            level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 1f, 1f)
            return InteractionResult.SUCCESS
        }

        fun drop(holder: CrystalHolder, level: Level, pos: BlockPos) {
            val crystal = holder.crystal ?: return
            Containers.dropItemStack(level, pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble(), ItemStack(AgeContent.RIME_CRYSTALS.getValue(crystal)))
        }

        fun load(input: ValueInput): RimeColour? =
            input.getStringOr(KEY, "").let { key -> RimeColour.entries.firstOrNull { it.key == key } }

        fun save(holder: CrystalHolder, output: ValueOutput) {
            holder.crystal?.let { output.putString(KEY, it.key) }
        }
    }
}
