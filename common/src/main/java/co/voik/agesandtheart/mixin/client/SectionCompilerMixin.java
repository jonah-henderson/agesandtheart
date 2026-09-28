package co.voik.agesandtheart.mixin.client;

import co.voik.agesandtheart.client.BelowTheLid;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Builds the section a tear's lid cuts through without the blocks under the lid, while somebody falls
 * through it — see {@link BelowTheLid}.
 *
 * <p>Only the loop that walks a section's blocks is redirected, so a block left out is simply not meshed:
 * its neighbours still cull their faces against the real world, which is what keeps the rest of the
 * section looking as it did. Runs on the section-building threads, which is why the cut is volatile.
 */
@Mixin(SectionCompiler.class)
public class SectionCompilerMixin {

    @Redirect(
            method = "compile",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState agesandtheart$leaveOutWhatIsUnderTheLid(RenderSectionRegion region, BlockPos pos) {
        BelowTheLid.Cut cut = BelowTheLid.INSTANCE.getCut();
        if (cut != null && cut.hidesBlock(pos.getX(), pos.getY(), pos.getZ())) return Blocks.AIR.defaultBlockState();
        return region.getBlockState(pos);
    }
}
