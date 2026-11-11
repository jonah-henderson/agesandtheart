package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.content.PlasmaBoil;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Water and ink boil away in a plasma sea's heat ({@link PlasmaBoil}).
 *
 * <p>{@code shouldSpreadLiquid} is where vanilla answers a liquid meeting another — water and lava making obsidian —
 * and both {@code onPlace} and {@code neighborChanged} ask it, so it sees a bucket poured, every block of a flow as it
 * is set, and a source the moment anything beside it changes. A liquid it removes does not spread, as vanilla's own
 * obsidian does not. Neither loader has an event for a liquid being placed; NeoForge's fires only for what lava makes.
 */
@Mixin(LiquidBlock.class)
public abstract class LiquidBlockMixin {

    @Inject(method = "shouldSpreadLiquid", at = @At("HEAD"), cancellable = true)
    private void agesandtheart$boilAwayInPlasmasHeat(Level level, BlockPos at, BlockState state, CallbackInfoReturnable<Boolean> cir) {
        if (PlasmaBoil.INSTANCE.boilsAway(level, at, state)) cir.setReturnValue(false);
    }
}
