package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.age.consequence.StrangeGround;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import net.minecraft.world.level.levelgen.feature.foliageplacers.FoliagePlacer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Leaves a tree bare in an Age whose instability bought leafless trees ({@link StrangeGround#isLeafless}), and grows
 * it with another block in place of each leaf where its book said so ({@link StrangeGround#grownWithItsFoliage}).
 *
 * <p><b>Why a mixin.</b> Every leaf a tree places goes through the one foliage setter {@code place} builds and
 * hands to {@code doPlace}; handing it one that places nothing leaves the trunk, branches, roots and
 * decorations exactly as they would have been. Neither loader has a tree or feature event, and stripping
 * leaves after decoration would miss those a neighbouring chunk's trees reach in with later.
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
        return StrangeGround.isLeafless() ? BARE : StrangeGround.grownWithItsFoliage(setter);
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
