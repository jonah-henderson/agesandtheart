package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.generation.Lifeless;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ServerLevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Refuses a new living thing in an Age where nothing lives ({@link Lifeless}).
 *
 * <p><b>Why a Mixin.</b> Fabric's {@code ServerEntityEvents.ENTITY_LOAD} fires after the entity is in the
 * level, and for loads and teleports alike; NeoForge's {@code EntityJoinLevelEvent} can cancel, but also
 * fires for teleports, and neither sees an entity a structure places while its chunk is generated, which
 * goes through the region. {@code addFreshEntity} is the one method every new entity passes through and
 * nothing arriving does, on both the level and the region.
 */
@Mixin({ServerLevel.class, WorldGenRegion.class})
public abstract class LifelessLevelMixin {

    @Inject(method = "addFreshEntity", at = @At("HEAD"), cancellable = true)
    private void agesandtheart$nothingLivesHere(Entity entity, CallbackInfoReturnable<Boolean> callback) {
        if (Lifeless.INSTANCE.refuses((ServerLevelAccessor) this, entity)) {
            callback.setReturnValue(false);
        }
    }
}
