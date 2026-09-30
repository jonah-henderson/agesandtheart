package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.age.phenomena.Tide;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keeps an ebbing tide from refilling itself.
 *
 * <p>The ebb takes away the sources above where the tide stands, and vanilla's infinite-water rule turns a
 * flow with two sources beside it and water or ground under it straight back into a source — so the shore
 * refilled as fast as it drained. This refuses that conversion **only where {@link Tide#holdsBack} says the
 * tide is holding water back**: inside its band, above where it stands, in an Age whose tide is running.
 * Everywhere else, the rule is vanilla's.
 *
 * <p><b>A Mixin because the rule knows no position.</b> {@code canConvertToSource(ServerLevel)} is the one
 * switch, and it takes the level alone; its only caller is {@code getNewLiquid}, which has the position, so
 * the call is redirected there. The gamerule it reads is server-wide, and neither loader has an event for a
 * fluid choosing its next state.
 */
@Mixin(FlowingFluid.class)
public abstract class FlowingFluidMixin {

    @Shadow
    protected abstract boolean canConvertToSource(ServerLevel level);

    @Redirect(
        method = "getNewLiquid",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/material/FlowingFluid;canConvertToSource(Lnet/minecraft/server/level/ServerLevel;)Z"
        )
    )
    private boolean agesandtheart$notWhereTheTideHoldsItBack(
        FlowingFluid fluid,
        ServerLevel level,
        ServerLevel sameLevel,
        BlockPos at,
        BlockState state
    ) {
        if (Tide.INSTANCE.holdsBack(level, at)) return false;
        return this.canConvertToSource(level);
    }
}
