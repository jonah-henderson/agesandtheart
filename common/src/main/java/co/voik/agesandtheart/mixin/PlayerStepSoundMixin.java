package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.content.CoconutHoofbeats;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A player with a coconut half in each hand steps like a horse ({@link CoconutHoofbeats}).
 *
 * <p>Neither loader has a step-sound event. {@code Player} is common, so this runs on the server for everyone
 * else and on the client's own {@code LocalPlayer} for the player themselves, as the vanilla step does.
 */
@Mixin(Player.class)
public abstract class PlayerStepSoundMixin {

    @Inject(method = "playStepSound", at = @At("HEAD"), cancellable = true)
    private void agesandtheart$clipClop(BlockPos onPos, BlockState onState, CallbackInfo callback) {
        Player player = (Player) (Object) this;
        if (CoconutHoofbeats.INSTANCE.isClipClopping(player)) {
            CoconutHoofbeats.INSTANCE.clipClop(player, onState);
            callback.cancel();
        }
    }
}
