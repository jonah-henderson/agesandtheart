package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.worldgen.fissure.RubbleArrivals;
import net.minecraft.world.entity.item.FallingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets rubble the fissures let down settle where it lands before vanilla decides whether it can be placed.
 *
 * <p>{@code tick} reads the landing position once, straight after {@code move}, and drops the block as an item
 * if what is there cannot be replaced: a flower, or a slab the block came to rest inside. Neither loader has a
 * falling-block event, and a subclass would need the private {@code blockState} widened and an entity type and
 * renderer of its own. Only rubble tagged by {@code RubbleArrivals} is touched.
 */
@Mixin(FallingBlockEntity.class)
public abstract class RubbleLandingMixin {

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/item/FallingBlockEntity;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
            shift = At.Shift.AFTER
        )
    )
    private void agesandtheart$settleRubble(CallbackInfo info) {
        RubbleArrivals.settle((FallingBlockEntity) (Object) this);
    }
}
