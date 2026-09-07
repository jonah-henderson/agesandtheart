package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.content.AstriteGolems;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CarvedPumpkinBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets a carved pumpkin finish an astrite golem, the way it finishes vanilla's three
 * ({@link AstriteGolems#tryAssemble}).
 *
 * <p><b>Why a mixin, and what was checked first.</b> Neither loader has a hook: NeoForge has no
 * golem-related event anywhere in its event package, and Fabric API has nothing in that area either.
 * Vanilla's own spawning is {@code trySpawnGolem}, which is private, reached only from {@code onPlace} —
 * so there is no seam to use and no method to call. Placing a block is a loader event on both sides, but
 * taking that route means the pattern check runs on <i>every</i> block placement rather than on carved
 * pumpkins, and it means two implementations behind the SPI instead of one file here.
 *
 * <p><b>Why {@code TAIL} is safe.</b> {@code onPlace} returns nothing and vanilla's own spawning happens
 * before it, so this always runs — and it cannot double-spawn: our shape is built out of a block vanilla
 * has never heard of, so a match here and a match there are mutually exclusive by their materials.
 *
 * <p><b>The guard is vanilla's own.</b> {@code onPlace} fires for state changes as well as placements, so
 * the same check it makes — that the block was not already this one — is made here before anything else.
 */
@Mixin(CarvedPumpkinBlock.class)
public abstract class CarvedPumpkinBlockMixin {

    @Inject(method = "onPlace", at = @At("TAIL"))
    private void agesandtheart$assembleAstriteGolem(
            BlockState state,
            Level level,
            BlockPos pos,
            BlockState oldState,
            boolean movedByPiston,
            CallbackInfo callback) {
        if (oldState.is(state.getBlock())) {
            return;
        }
        AstriteGolems.INSTANCE.tryAssemble(level, pos);
    }
}
