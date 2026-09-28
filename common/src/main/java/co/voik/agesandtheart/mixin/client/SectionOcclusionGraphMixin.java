package co.voik.agesandtheart.mixin.client;

import co.voik.agesandtheart.client.BelowTheLid;
import java.util.List;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drops every section wholly under a tear's lid from the frame while somebody falls through it — see
 * {@link BelowTheLid}.
 *
 * <p>A Mixin because neither loader has an event for which sections a frame draws. This is where vanilla
 * fills the list every pass reads, so filtering it once here takes the terrain out of all of them.
 */
@Mixin(SectionOcclusionGraph.class)
public class SectionOcclusionGraphMixin {

    @Inject(method = "addSectionsInFrustum", at = @At("TAIL"))
    private void agesandtheart$dropWhatIsUnderTheLid(
            Frustum frustum,
            List<SectionRenderDispatcher.RenderSection> visibleSections,
            List<SectionRenderDispatcher.RenderSection> nearbyVisibleSections,
            CallbackInfo callback) {
        BelowTheLid.Cut cut = BelowTheLid.INSTANCE.getCut();
        if (cut == null) return;
        visibleSections.removeIf(section -> agesandtheart$isUnder(cut, section));
        nearbyVisibleSections.removeIf(section -> agesandtheart$isUnder(cut, section));
    }

    private static boolean agesandtheart$isUnder(BelowTheLid.Cut cut, SectionRenderDispatcher.RenderSection section) {
        int bottomY = SectionPos.sectionToBlockCoord(SectionPos.y(section.getSectionNode()));
        return cut.dropsSection(bottomY);
    }
}
