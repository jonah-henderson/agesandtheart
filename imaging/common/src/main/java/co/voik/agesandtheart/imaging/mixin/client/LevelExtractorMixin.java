package co.voik.agesandtheart.imaging.mixin.client;

import co.voik.agesandtheart.imaging.client.RecorderPreview;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The player in their own view while they look through a set-down recorder, as the recorder sees them. Vanilla never
 * draws the local player but as the camera's own entity, and the view is the recorder's lens then; the test is inline
 * in the entity pass, and neither loader has an event for which entities are drawn.
 */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorMixin {

    @Shadow
    private @Nullable ClientLevel level;

    @Shadow
    private EntityRenderState extractEntity(Entity entity, float partialTickTime) {
        throw new AssertionError();
    }

    @Shadow
    public abstract boolean isEntityVisible(Entity entity, Frustum frustum, double camX, double camY, double camZ, float partialTicks, long chunkFadeDuration);

    @Inject(method = "extractVisibleEntities", at = @At("TAIL"))
    private void agesandtheart_imaging$theOneLookingThrough(Camera camera, Frustum frustum, DeltaTracker deltaTracker, LevelRenderState output, CallbackInfo callback) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (!RecorderPreview.isLooking() || player == null || player.level() != level) return;
        float partialTicks = deltaTracker.getGameTimeDeltaPartialTick(true);
        Vec3 eye = camera.position();
        long fadeMillis = Util.toMillis(minecraft.options.chunkSectionFadeInTime().get());
        if (!isEntityVisible(player, frustum, eye.x(), eye.y(), eye.z(), partialTicks, fadeMillis)) return;
        EntityRenderState state = extractEntity(player, partialTicks);
        // Vanilla shows a player's name to every view but their own, and this is still their own.
        state.nameTag = null;
        state.scoreText = null;
        output.entityRenderStates.add(state);
        output.lastEntityRenderStateCount = output.entityRenderStates.size();
    }
}
