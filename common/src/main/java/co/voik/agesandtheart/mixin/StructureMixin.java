package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.generation.IncongruousStructures;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * In an {@code incongruous structures} Age, a structure that sits on the ground — an igloo, a temple, a monument,
 * a wreck, a mansion — stands at the height drawn for it ({@link IncongruousStructures}).
 *
 * <p><b>Why a mixin, and what was checked first.</b> Those structures all ask for their ground through two
 * shared helpers: {@code onTopOfChunkCenterWithoutBiomeCheck}, with the heightmap each passes in, and
 * {@code getCornerHeights}, which hard-codes the surface. No loader event reaches either, and the structures
 * themselves are vanilla's classes. Mineshafts, strongholds, fortresses and ruined portals work out their own
 * heights and are left as they are.
 */
@Mixin(Structure.class)
public abstract class StructureMixin {

    @Redirect(
        method = {"onTopOfChunkCenterWithoutBiomeCheck", "getCornerHeights"},
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/ChunkGenerator;getFirstOccupiedHeight(IILnet/minecraft/world/level/levelgen/Heightmap$Types;Lnet/minecraft/world/level/LevelHeightAccessor;Lnet/minecraft/world/level/levelgen/RandomState;)I"
        )
    )
    private static int agesandtheart$groundAnywhere(
        ChunkGenerator generator,
        int x,
        int z,
        Heightmap.Types heightmap,
        LevelHeightAccessor level,
        RandomState random
    ) {
        return IncongruousStructures.groundAt(generator, x, z, heightmap, level, random);
    }
}
