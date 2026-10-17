package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.generation.StructureMaterials;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Places a structure an Age built of some material through a level that swaps its blocks as they are set
 * ({@code StructureMaterials}).
 *
 * <p>{@code placeInChunk} is the one call every structure's blocks go through, template pieces and pieces built
 * in code alike, and neither loader has a structure-placement event. A {@code StructureProcessor} would reach
 * only the template pieces. The structure is placed again through what {@code levelFor} hands back, which
 * answers null on that second call.
 */
@Mixin(StructureStart.class)
public abstract class StructureStartMixin {

    @Inject(method = "placeInChunk", at = @At("HEAD"), cancellable = true)
    private void agesandtheart$buildOfTheAgesMaterial(
        WorldGenLevel level,
        StructureManager manager,
        ChunkGenerator generator,
        RandomSource random,
        BoundingBox chunkBox,
        ChunkPos chunk,
        CallbackInfo info
    ) {
        StructureStart start = (StructureStart) (Object) this;
        WorldGenLevel through = StructureMaterials.levelFor(start, level, manager, generator);
        if (through == null) return;
        start.placeInChunk(through, manager, generator, random, chunkBox, chunk);
        info.cancel();
    }
}
