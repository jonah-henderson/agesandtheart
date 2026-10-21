package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.generation.SpawnCaps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LocalMobCapCalculator;
import net.minecraft.world.level.NaturalSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Takes the mob caps an Age's book moved ({@link SpawnCaps}) as a level begins its spawning, for {@link SpawnStateMixin}
 * and {@link MobCountsMixin} to lay on vanilla's.
 *
 * <p><b>Why a mixin.</b> Vanilla's caps are {@code MobCategory.getMaxInstancesPerChunk}, a constant read in two
 * places that are handed no level — the whole level's cap in {@code SpawnState} and each player's in
 * {@code LocalMobCapCalculator}. {@code createState} is where a level's spawning starts and the one call with the level
 * in it. Neither loader has an event for a cap: Fabric none for natural spawning, NeoForge's spawn events per mob.
 */
@Mixin(NaturalSpawner.class)
public abstract class SpawnCapsMixin {

    @Inject(method = "createState", at = @At("HEAD"))
    private static void agesandtheart$countingFor(
        int spawnableChunkCount,
        ServerLevel level,
        NaturalSpawner.ChunkGetter chunkGetter,
        LocalMobCapCalculator localMobCapCalculator,
        CallbackInfoReturnable<NaturalSpawner.SpawnState> callback
    ) {
        SpawnCaps.countingFor(level);
    }
}
