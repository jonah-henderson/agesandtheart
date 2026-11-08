package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.generation.AgeChunkGenerator;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * An Age searches for its stronghold rings when a chunk first needs them, not as it opens.
 *
 * <p>A level starts the search in its constructor: 128 places, each a wide biome search, on the pool that generates
 * chunks. A boot reopens every saved Age, so a server with a couple of hundred of them queued minutes of searching
 * ahead of every chunk, and a player joining waited on "Joining world" for the chunks they logged out in.
 * {@code getRingPositionsFor} starts the same search itself the first time it is asked, so skipping the eager call
 * costs an Age nothing but the wait, paid when it first generates.
 *
 * <p><b>Why a Mixin.</b> The call is inline in {@code ServerLevel}'s constructor, the state is built by vanilla
 * around a private constructor, and neither loader has an event between the two.
 */
@Mixin(ServerLevel.class)
public abstract class AgeStructureRingsMixin {

    @Redirect(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/chunk/ChunkGeneratorStructureState;ensureStructuresGenerated()V"))
    private void agesandtheart$onlyWhenAChunkAsks(ChunkGeneratorStructureState state) {
        boolean isAnAge = ((ServerLevel) (Object) this).getChunkSource().getGenerator() instanceof AgeChunkGenerator;
        if (!isAnAge) {
            state.ensureStructuresGenerated();
        }
    }
}
