package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.content.TemperstoneBlock;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets temperstone move as one run under a piston, on the loaders that have no hook for it.
 *
 * <p><b>This is the Fabric half only, and the injectors are {@code require = 0} for that reason.</b>
 * Vanilla hard-codes stickiness to slime and honey in two private statics here; NeoForge patches both away
 * in favour of {@code IBlockStateExtension}, so on NeoForge these targets do not exist and the injectors
 * correctly match nothing. The class itself is present on both, which is what lets one config serve both
 * without a loader-specific mixin file.
 *
 * <p>NeoForge's half needs no Mixin at all — {@link TemperstoneBlock} declares {@code isStickyBlock} and
 * {@code canStickTo} with vanilla-only signatures, which bind as overrides of NeoForge's interface at
 * runtime and are inert on Fabric.
 *
 * <p>Both halves only ever widen the answer, and neither disturbs slime or honey.
 */
@Mixin(PistonStructureResolver.class)
public abstract class PistonStructureResolverMixin {

    @Inject(method = "isSticky", at = @At("HEAD"), cancellable = true, require = 0, expect = 0)
    private static void agesandtheart$temperstoneIsSticky(
        BlockState state,
        CallbackInfoReturnable<Boolean> callback
    ) {
        if (TemperstoneBlock.bindsToItsOwnKind(state)) {
            callback.setReturnValue(true);
        }
    }

    /**
     * Temperstone binds to its own kind and to nothing else.
     *
     * <p>Returning early for any temperstone is what keeps it from behaving like slime: vanilla's rule is
     * {@code isSticky(a) || isSticky(b)}, which would have a run of it drag whatever it touched.
     */
    @Inject(method = "canStickToEachOther", at = @At("HEAD"), cancellable = true, require = 0, expect = 0)
    private static void agesandtheart$temperstoneBindsToItsOwn(
        BlockState state,
        BlockState other,
        CallbackInfoReturnable<Boolean> callback
    ) {
        boolean eitherIsTemperstone =
            TemperstoneBlock.bindsToItsOwnKind(state) || TemperstoneBlock.bindsToItsOwnKind(other);
        if (eitherIsTemperstone) {
            callback.setReturnValue(
                TemperstoneBlock.bindsToItsOwnKind(state) && TemperstoneBlock.bindsToItsOwnKind(other)
            );
        }
    }
}
