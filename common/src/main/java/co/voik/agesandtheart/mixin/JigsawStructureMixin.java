package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.generation.IncongruousStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import java.util.Optional;

/**
 * In an {@code incongruous structures} Age, a village, an ancient city or any other jigsaw structure starts at the
 * height drawn for it — on the sea floor, in the sky, buried, deep, or out on the surface ({@link
 * IncongruousStructures}).
 *
 * <p><b>Why a mixin, and what was checked first.</b> Where a jigsaw structure starts is its private
 * {@code startHeight} and {@code projectStartToHeightmap}, handed to {@code JigsawPlacement.addPieces} in this one
 * call; no loader event reaches it, and the fields are final data read from the structure's JSON. Arguments 4 and
 * 6 of that call are the start and its projection.
 */
@Mixin(JigsawStructure.class)
public abstract class JigsawStructureMixin {

    @ModifyArgs(
        method = "findGenerationPoint",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/structure/pools/JigsawPlacement;addPieces(Lnet/minecraft/world/level/levelgen/structure/Structure$GenerationContext;Lnet/minecraft/core/Holder;Ljava/util/Optional;ILnet/minecraft/core/BlockPos;ZLjava/util/Optional;Lnet/minecraft/world/level/levelgen/structure/structures/JigsawStructure$MaxDistance;Lnet/minecraft/world/level/levelgen/structure/pools/alias/PoolAliasLookup;Lnet/minecraft/world/level/levelgen/structure/pools/DimensionPadding;Lnet/minecraft/world/level/levelgen/structure/templatesystem/LiquidSettings;)Ljava/util/Optional;"
        )
    )
    private void agesandtheart$startAnywhere(Args args) {
        BlockPos start = args.get(4);
        Optional<Heightmap.Types> projection = args.get(6);
        IncongruousStructures.JigsawStart moved = IncongruousStructures.jigsawStart(start, projection);
        if (moved != null) {
            args.set(4, moved.getAt());
            args.set(6, moved.getProjection());
        }
    }
}
