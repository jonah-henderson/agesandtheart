package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.age.consequence.StrangeGround;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Starts every village as the abandoned one in an Age whose instability bought it ({@link StrangeGround#startFrom}).
 *
 * <p><b>Why a mixin.</b> A village's start pool holds its zombie town centres beside the living ones, and which
 * is drawn decides the whole village, since each start leads only into its own kind's streets and houses. The
 * draw is this one call; neither loader has a structure-assembly event, and a second, zombie-only start pool
 * would have to be registered for every kind of village a datapack might add.
 */
@Mixin(JigsawPlacement.class)
public abstract class JigsawPlacementMixin {

    @Redirect(
        method = "addPieces",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/structure/pools/StructureTemplatePool;getRandomTemplate(Lnet/minecraft/util/RandomSource;)Lnet/minecraft/world/level/levelgen/structure/pools/StructurePoolElement;"
        )
    )
    private static StructurePoolElement agesandtheart$abandoned(StructureTemplatePool pool, RandomSource random) {
        return StrangeGround.startFrom(pool, random, pool.getRandomTemplate(random));
    }
}
