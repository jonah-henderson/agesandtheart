package co.voik.agesandtheart.mixin.client;

import co.voik.agesandtheart.content.DeepWater;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hides the seam where an Age's ordinary sea meets its abyss.
 *
 * <p>A fluid skips drawing a face only against <em>the same</em> fluid, so vanilla water puts a face against
 * deep water — and a fluid face with open space behind it is drawn with the running-water animation on it.
 * The result is a vertical stripe of what looks like a waterfall wherever the two touch sideways, which is
 * every cave mouth and every pocket the air rule opens.
 *
 * <p><b>It is one-directional on purpose, and the obvious symmetric version is wrong.</b> Only ordinary
 * water is made to skip. Deep water must go on drawing its faces against water, because the face it draws
 * where the plane meets the sea above it is exactly what stops a player backing into the shallows and
 * sighting straight down through the abyss — the opacity that took its own texture to buy.
 *
 * <p><b>Alternatives checked.</b> Fabric's {@code FluidRenderingRegistry} takes a model and has no say over
 * face culling; making our fluid report {@code isSame} as water would buy this and confuse a great deal of
 * vanilla logic that asks the same question for other reasons; and there is no loader event anywhere near
 * this. The vanilla method is identical on both sides, so one mixin in common serves both.
 */
@Mixin(LiquidBlock.class)
public class LiquidBlockMixin {

    @Inject(method = "skipRendering", at = @At("HEAD"), cancellable = true)
    private void agesandtheart$hideTheAbyssSeam(
        BlockState state,
        BlockState neighbourState,
        Direction direction,
        CallbackInfoReturnable<Boolean> callback
    ) {
        FluidState mine = state.getFluidState();
        if (mine.isEmpty() || mine.is(DeepWater.INSTANCE.getDEEP_WATER()) || !mine.is(FluidTags.WATER)) {
            return;
        }
        if (neighbourState.getFluidState().is(DeepWater.INSTANCE.getDEEP_WATER())) {
            callback.setReturnValue(true);
        }
    }
}
