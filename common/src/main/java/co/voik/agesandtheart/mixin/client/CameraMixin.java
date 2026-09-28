package co.voik.agesandtheart.mixin.client;

import co.voik.agesandtheart.client.StarFissureVeil;
import net.minecraft.client.Camera;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No water, lava or snow is seen from inside a tear being fallen through.
 *
 * <p>The player's own fluid readings are already emptied ({@code EntityFluidInteractionMixin}); this is the
 * camera's, which reads the block the camera stands in and decides the fog, its colour and the narrowed
 * field of view. NeoForge has events for fog and field of view and Fabric has neither, so the one query
 * both read from is the seam that works alike on both.
 */
@Mixin(Camera.class)
public class CameraMixin {

    @Inject(method = "getFluidInCamera", at = @At("HEAD"), cancellable = true)
    private void agesandtheart$noFluidInsideATear(CallbackInfoReturnable<FogType> callback) {
        if (StarFissureVeil.INSTANCE.hidingTheTears()) callback.setReturnValue(FogType.NONE);
    }
}
