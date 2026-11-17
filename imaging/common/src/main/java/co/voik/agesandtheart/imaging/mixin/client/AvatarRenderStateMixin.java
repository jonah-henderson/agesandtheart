package co.voik.agesandtheart.imaging.mixin.client;

import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import co.voik.agesandtheart.imaging.client.RecorderRaised;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Gives a player's render state somewhere to say the recorder is raised ({@link RecorderRaised}). */
@Mixin(AvatarRenderState.class)
public class AvatarRenderStateMixin implements RecorderRaised {

    @Unique
    private boolean agesandtheart_imaging$recorderRaised;

    @Override
    public boolean agesandtheart_imaging$isRecorderRaised() {
        return agesandtheart_imaging$recorderRaised;
    }

    @Override
    public void agesandtheart_imaging$setRecorderRaised(boolean raised) {
        agesandtheart_imaging$recorderRaised = raised;
    }
}
