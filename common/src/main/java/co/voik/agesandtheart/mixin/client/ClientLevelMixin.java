package co.voik.agesandtheart.mixin.client;

import co.voik.agesandtheart.age.consequence.Wounds;
import co.voik.agesandtheart.client.WoundField;
import co.voik.agesandtheart.client.light.DeepLights;
import co.voik.agesandtheart.client.light.TintedLights;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells the client's wound index about a wound that arrives after its chunk did.
 *
 * <p>A Mixin because there is no client block-change event on either loader — Fabric API has none and
 * NeoForge's block events are all server-side — and because the alternative is worse in a specific way:
 * the index could be fed by a payload of our own, but then the mod would be sending a second message
 * about a block the server already sent, and the two could disagree.
 *
 * <p>{@code setBlocksDirty} is the seam rather than {@code LevelRenderer.blockChanged} because it is
 * called on the {@code ClientLevel} itself, and the index is keyed by level. Vanilla calls it for every
 * state change that actually changed something, ahead of the update-flag tests, so nothing about which
 * flags placed the block matters here.
 *
 * <p>Why the index needs telling at all: {@code WoundBlock.onPlace} feeds it, and
 * {@code LevelChunk.setBlockState} skips {@code onPlace} on the client.
 */
@Mixin(ClientLevel.class)
public class ClientLevelMixin {

    @Inject(method = "setBlocksDirty", at = @At("HEAD"))
    private void agesandtheart$noticeAWound(
            BlockPos pos,
            BlockState oldState,
            BlockState newState,
            CallbackInfo callback) {
        ClientLevel level = (ClientLevel) (Object) this;
        if (Wounds.INSTANCE.noticed(level, pos, oldState, newState)) WoundField.INSTANCE.opened(level, pos);
        TintedLights.INSTANCE.noticed(level, pos, oldState, newState);
        DeepLights.INSTANCE.noticed(level, pos, oldState, newState);
    }
}
