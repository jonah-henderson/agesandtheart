package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.age.phenomena.TornadoWinds;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A player a tornado holds up is not kicked for flying ({@link TornadoWinds#isHeldUp}).
 *
 * <p>A server disconnects a player who has not fallen for eighty ticks, and a tornado's orbit keeps one off the
 * ground for as long as it pleases. {@code getMaximumFlyingTicks} is the one method that says how long anything may
 * float, a player or the vehicle they ride, and neither loader has an event for the check or a way to excuse a
 * player from it; vanilla's own exemptions (flight allowed, levitation, an elytra) would each change how the player
 * moves.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {

    @Inject(method = "getMaximumFlyingTicks", at = @At("HEAD"), cancellable = true)
    private void agesandtheart$heldUpByATornado(Entity floating, CallbackInfoReturnable<Integer> mostTicks) {
        if (TornadoWinds.isHeldUp(floating)) {
            mostTicks.setReturnValue(Integer.MAX_VALUE);
        }
    }
}
