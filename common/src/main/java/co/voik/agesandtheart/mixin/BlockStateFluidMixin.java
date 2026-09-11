package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.content.DeepWaterLogging;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes a deep-logged block report the abyss rather than water.
 *
 * <p><b>One seam instead of thirty-nine.</b> Every waterloggable block overrides
 * {@code getFluidState(BlockState)} to return {@code Fluids.WATER.getSource(false)}, and there are
 * thirty-nine of them — but nothing reads those overrides directly. {@code BlockStateBase.getFluidState()}
 * is a bare field read, and the field is written in exactly one line of {@code initCache}. Rewriting it
 * there covers every block, vanilla and modded, and costs nothing afterwards: this is a value settled once
 * per state at bootstrap, not a branch on the path that chunk meshing and collision take.
 *
 * <p>The one thing it cannot settle is <i>when</i>. {@code initCache} runs from a static block in
 * {@code Blocks}, which may be before a loader has registered our fluid — so
 * {@code DeepWaterLogging.settleTheCache} probes for that afterwards and redoes only what was missed.
 *
 * <p>No loader offers fluidlogging of any kind: checked against NeoForge 26.1.2.93 and Fabric API 0.155.2,
 * neither ships an API for it, so there is nothing to inherit here.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateFluidMixin {

    @Shadow
    private FluidState fluidState;

    @Inject(method = "initCache", at = @At("RETURN"))
    private void agesandtheart$reportTheAbyss(CallbackInfo callback) {
        FluidState abyss = DeepWaterLogging.fluidIn((BlockState) (Object) this);
        if (abyss != null) {
            this.fluidState = abyss;
        }
    }
}
