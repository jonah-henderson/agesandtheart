package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.age.consequence.StampedShapes;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.placement.FeaturePlacer;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Hands a feature a fixed random in an Age whose shapes are stamped ({@link StampedShapes}).
 *
 * <p><b>Why a mixin.</b> The random a feature is drawn with is the same one its placement modifiers just
 * used to choose where it goes, and the two are only told apart here, at the call between them. Neither
 * loader has a feature-placement event, and replacing the generator's whole decoration pass to reach this
 * one call would mean copying vanilla's structure-and-feature interleaving.
 *
 * <p>Both calls are redirected: one for a feature with no placement modifiers, one for each position the
 * modifiers chose. Outside a stamped Age it hands back the same random, at the cost of one thread-local read.
 */
@Mixin(FeaturePlacer.class)
public abstract class FeaturePlacerMixin {

    @Redirect(
        method = "place(Lnet/minecraft/world/level/levelgen/placement/PlacedFeature;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;Z)Z",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/feature/Feature;place(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Z"
        ),
        require = 2
    )
    private boolean agesandtheart$stamped(
        Feature feature,
        WorldGenLevel level,
        ChunkGenerator generator,
        RandomSource random,
        BlockPos at,
        PlacedFeature placed,
        RandomSource placing,
        BlockPos origin,
        boolean biomeCheck
    ) {
        return feature.place(level, generator, StampedShapes.randomFor(placed, random), at);
    }
}
