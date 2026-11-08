package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.age.consequence.StrangeGround;
import co.voik.agesandtheart.worldgen.feature.FlagTrees;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import net.minecraft.world.level.levelgen.feature.foliageplacers.FoliagePlacer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Leaves a tree bare in an Age whose instability bought leafless trees ({@link StrangeGround#isLeafless}), and grows
 * it with another block in place of each leaf where its book said so ({@link StrangeGround#grownWithItsFoliage}).
 *
 * <p><b>Why a mixin.</b> Every leaf a tree places goes through the one foliage setter {@code place} builds and
 * hands to {@code doPlace}; handing it one that places nothing leaves the trunk, branches, roots and
 * decorations exactly as they would have been. Neither loader has a tree or feature event, and stripping
 * leaves after decoration would miss those a neighbouring chunk's trees reach in with later.
 *
 * <p>The trunk setter beside it bends a tree in the wind ({@link FlagTrees}), the two setters then moving each block
 * before vanilla records it, so the decorations and the leaves' distances follow the bent tree.
 */
@Mixin(TreeFeature.class)
public abstract class TreeFeatureMixin {

    @ModifyArg(
        method = "place",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/feature/TreeFeature;doPlace(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;Ljava/util/function/BiConsumer;Ljava/util/function/BiConsumer;Lnet/minecraft/world/level/levelgen/feature/foliageplacers/FoliagePlacer$FoliageSetter;)Z"
        ),
        index = 5
    )
    private FoliagePlacer.FoliageSetter agesandtheart$bare(FoliagePlacer.FoliageSetter setter) {
        return StrangeGround.isLeafless() ? BARE : FlagTrees.foliage(StrangeGround.grownWithItsFoliage(setter));
    }

    @ModifyArg(
        method = "place",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/feature/TreeFeature;doPlace(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;Ljava/util/function/BiConsumer;Ljava/util/function/BiConsumer;Lnet/minecraft/world/level/levelgen/feature/foliageplacers/FoliagePlacer$FoliageSetter;)Z"
        ),
        index = 4
    )
    private BiConsumer<BlockPos, BlockState> agesandtheart$bentTrunk(BiConsumer<BlockPos, BlockState> setter) {
        return FlagTrees.trunk(setter);
    }

    @Inject(method = "place", at = @At("HEAD"))
    private void agesandtheart$beginTree(
        WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin, CallbackInfoReturnable<Boolean> callback
    ) {
        FlagTrees.beginTree(level, origin);
    }

    @Inject(method = "place", at = @At("RETURN"))
    private void agesandtheart$endTree(
        WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin, CallbackInfoReturnable<Boolean> callback
    ) {
        FlagTrees.endTree();
    }

    private static final FoliagePlacer.FoliageSetter BARE = new FoliagePlacer.FoliageSetter() {
        @Override
        public void set(BlockPos pos, BlockState state) {
        }

        @Override
        public boolean isSet(BlockPos pos) {
            return false;
        }
    };
}
