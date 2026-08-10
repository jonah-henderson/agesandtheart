package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.age.consequence.Hostility;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.DifficultyInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets a wound make the ground around it dangerous (design §5.1).
 *
 * <p>The per-Age weather that used to live here has moved to {@code co.voik.ephemeris}: giving a level
 * its own weather is a thing any runtime level might want, where this is a thing an <i>Age</i> does.
 *
 * <p><b>Why a Mixin.</b> There is no event for this on either loader — NeoForge's
 * {@code DifficultyChangeEvent} fires when the <i>world's</i> difficulty setting changes and knows nothing
 * of a position, and Fabric has nothing at all. Nor is there an object to substitute the way
 * {@code WeatherData} could be: local difficulty is computed on demand and returned by value, so this one
 * method is the only place it exists. Everything downstream — mob equipment, zombie reinforcements, husk
 * and drowned conversion — reads it through here.
 *
 * <p><b>And why it stays in the mod.</b> It is not per-level at all: it is positional <i>within</i> a
 * level, and driven by how near a wound is. A library hook for it would have to be either "this level is
 * harder", which is not what this does, or "anyone may rewrite difficulty anywhere", which is a difficulty
 * API and a different library.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {

    /**
     * <b>At {@code RETURN}, so vanilla decides first.</b> {@link Hostility} needs what the place would have
     * been in order to raise it rather than replace it, and injecting at the head would mean recomputing
     * the three terms a wound has no business touching.
     */
    @Inject(method = "getCurrentDifficultyAt", at = @At("RETURN"), cancellable = true)
    private void agesandtheart$harderNearAWound(BlockPos at, CallbackInfoReturnable<DifficultyInstance> callback) {
        DifficultyInstance harder = Hostility.localDifficultyAt((ServerLevel) (Object) this, at, callback.getReturnValue());
        if (harder != null) {
            callback.setReturnValue(harder);
        }
    }
}
