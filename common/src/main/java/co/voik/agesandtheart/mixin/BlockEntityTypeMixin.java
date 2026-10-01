package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.content.PalmWood;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets vanilla's sign, hanging sign and shelf block entities stand in the palm's blocks.
 *
 * <p>Each type holds the blocks it accepts in a set built once, and refuses a block entity anywhere else —
 * so a palm sign placed without this has no text and a palm shelf holds nothing. NeoForge has an event for
 * adding to that set and Fabric does not; this is the one route both loaders take.
 */
@Mixin(BlockEntityType.class)
public class BlockEntityTypeMixin {

    @Inject(method = "isValid", at = @At("HEAD"), cancellable = true)
    private void agesandtheart$acceptPalmWood(BlockState state, CallbackInfoReturnable<Boolean> result) {
        Object self = this;
        if (self == BlockEntityTypes.SIGN && PalmWood.INSTANCE.getSigns().contains(state.getBlock())
                || self == BlockEntityTypes.HANGING_SIGN && PalmWood.INSTANCE.getHangingSigns().contains(state.getBlock())
                || self == BlockEntityTypes.SHELF && PalmWood.INSTANCE.getShelves().contains(state.getBlock())) {
            result.setReturnValue(true);
        }
    }
}
