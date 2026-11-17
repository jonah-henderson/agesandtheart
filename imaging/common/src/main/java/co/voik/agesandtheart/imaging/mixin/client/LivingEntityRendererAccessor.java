package co.voik.agesandtheart.imaging.mixin.client;

import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.item.ItemModelResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The resolver a living renderer draws held items with, for {@link AvatarRendererMixin}, which cannot shadow an inherited field. */
@Mixin(LivingEntityRenderer.class)
public interface LivingEntityRendererAccessor {

    @Accessor("itemModelResolver")
    ItemModelResolver agesandtheart_imaging$itemModelResolver();
}
