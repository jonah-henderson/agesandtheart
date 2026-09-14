package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.content.DeepBubbleColumnBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * In a level with an abyss, every bubble column is ours — one column that runs on through ordinary water and
 * deep water alike, each block keeping the kind of water it replaced.
 *
 * <p><b>Why a mixin, and what was checked first.</b> In 26.1 a column is raised by the liquid over the block
 * that drives it: {@code LiquidBlock.tick} calls {@code updateColumn(Blocks.BUBBLE_COLUMN, …)}, and magma and
 * soul sand only carry the tags that say which way. Neither loader has an event for a column being raised, and
 * the routine is static with private helpers. Keeping deep water off {@code #bubble_column_can_occupy} stops
 * vanilla's column entering the abyss, but vanilla then stops at the boundary — it places its blocks without
 * notifying anything, so nothing tells the deep water above to carry on. Handing the whole column to
 * {@link DeepBubbleColumnBlock#raise} is what makes it one column.
 *
 * <p>Only vanilla's own column, and only where the level has an abyss; everywhere else vanilla is untouched.
 */
@Mixin(BubbleColumnBlock.class)
public abstract class BubbleColumnBlockMixin {

    @Inject(
            method = "updateColumn(Lnet/minecraft/world/level/block/Block;Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;)V",
            at = @At("HEAD"),
            cancellable = true)
    private static void agesandtheart$raiseOurs(
            Block bubbleColumn,
            LevelAccessor level,
            BlockPos occupyAt,
            BlockState occupyState,
            BlockState belowState,
            CallbackInfo callback) {
        if (DeepBubbleColumnBlock.takesOver(bubbleColumn, level)) {
            DeepBubbleColumnBlock.raise(level, occupyAt, occupyState, belowState);
            callback.cancel();
        }
    }
}
