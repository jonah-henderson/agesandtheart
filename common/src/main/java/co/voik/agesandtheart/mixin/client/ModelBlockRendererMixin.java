package co.voik.agesandtheart.mixin.client;

import co.voik.agesandtheart.client.light.TintedLightPainter;
import com.mojang.blaze3d.vertex.QuadInstance;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.core.BlockPos;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets a block tint the colour of the light it casts on what is around it — see
 * {@code notes/coloured-light-research.md} and {@code TintedLightPainter}.
 *
 * <p><b>This is the only place per-position colour can enter terrain rendering.</b> The lightmap is a
 * 16&times;16 texture indexed by (block light, sky light) with no position in it, so every lightmap route
 * tints the whole view at once; the chunk mesh, by contrast, carries a colour per vertex, which is where
 * biome tint already lives. {@code putQuadWithTint} is the moment that colour is decided.
 *
 * <p><b>One injector at HEAD, and nothing else.</b> Injecting before vanilla's own tint rather than after
 * is deliberate and free: both are multiplies, so the order does not matter, and HEAD needs no knowledge
 * of the method's shape beyond its parameters. Everything the painter touches is public API on
 * {@link QuadInstance} — {@code getColor}, {@code setColor} and {@code getLightCoords} — so the Mixin
 * exists only to reach a private method, and carries no logic of its own.
 *
 * <p><b>Alternatives checked.</b> Neither loader has a hook here: Fabric's rendering API covers model
 * baking and quad emission rather than the lighting of a placed quad, and NeoForge's extended model API
 * likewise decides what quads a model has, not what colour the world paints them. A post-effect chain was
 * rejected in {@code notes/corruption-research.md} — {@code GameRenderer.setPostEffect} is private and a
 * full-screen filter cannot be a gradient. A custom terrain pipeline would fight every other mod and every
 * shader pack.
 *
 * <p><b>It is in a hot path and the painter knows it.</b> This runs per quad of every block in every
 * section, so {@code paint} is written to leave on a field read where nothing is registered.
 */
@Mixin(ModelBlockRenderer.class)
public class ModelBlockRendererMixin {

    @Shadow
    @Final
    private QuadInstance quadInstance;

    @Inject(method = "putQuadWithTint", at = @At("HEAD"))
    private void agesandtheart$tintByColouredLight(
            BlockQuadOutput output,
            float x,
            float y,
            float z,
            BlockAndTintGetter level,
            BlockState state,
            BlockPos pos,
            BakedQuad quad,
            CallbackInfo callback) {
        TintedLightPainter.INSTANCE.paint(level, pos, this.quadInstance);
    }
}
