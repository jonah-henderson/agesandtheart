package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.generation.GrownTrees;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.grower.TreeGrower;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Grows a sapling of the block its Age grew its trees of, where its book said one ({@link GrownTrees}), so
 * {@code diamond_block trees} still holds of a tree planted after the chunk was made.
 *
 * <p><b>Why a mixin.</b> {@code growTree} is the one call every sapling goes through — a random tick, bone meal, a
 * propagule, an azalea — and the last place that knows which tree it is: past it {@code TreeFeature} holds only the
 * shape it was given. Fabric has no sapling event; NeoForge's {@code BlockGrowFeatureEvent} would serve it alone.
 * Both the single tree and the two-by-two one are redirected.
 */
@Mixin(TreeGrower.class)
public abstract class TreeGrowerMixin {

    @Redirect(
        method = "growTree",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/feature/Feature;place(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Z"
        )
    )
    private boolean agesandtheart$grown(Feature tree, WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos at) {
        return GrownTrees.grown(level, tree, () -> tree.place(level, generator, random, at));
    }
}
