package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.generation.IncongruousStructures;
import net.minecraft.core.Holder;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.levelgen.structure.structures.OceanMonumentStructure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * In an {@code incongruous structures} Age, a monument stands whatever surrounds it ({@link IncongruousStructures}).
 *
 * <p><b>Why a mixin, and what was checked first.</b> A monument asks, in its own {@code findGenerationPoint}, that
 * every biome within 29 blocks carry {@code #required_ocean_monument_surrounding} — a check of its own past the
 * biome test {@code ChunkGeneratorMixin} lifts, and the only one of its kind among vanilla's structures. No
 * loader event reaches it, and emptying the tag would move every monument in every level.
 */
@Mixin(OceanMonumentStructure.class)
public abstract class OceanMonumentStructureMixin {

    @Redirect(
        method = "findGenerationPoint",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/core/Holder;is(Lnet/minecraft/tags/TagKey;)Z")
    )
    private boolean agesandtheart$surroundedByAnything(Holder<?> biome, TagKey tag) {
        return IncongruousStructures.isBuildingAnywhere() || biome.is(tag);
    }
}
