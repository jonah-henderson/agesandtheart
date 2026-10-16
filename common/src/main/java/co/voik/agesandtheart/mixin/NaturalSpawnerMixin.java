package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.generation.IncongruousSpawns;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.ServerLevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * In an {@code incongruous spawns} Age, any creature turns up anywhere — fish on hillsides, monsters at noon
 * ({@link IncongruousSpawns}).
 *
 * <p><b>Why a mixin, and what was checked first.</b> A natural spawn passes three rules before it is made: the
 * placement its type was registered with ({@code SpawnPlacements.isSpawnPositionOk} — on the ground, in water,
 * in lava), the type's own predicate ({@code SpawnPlacements.checkSpawnRules} — dark enough, grass underfoot), and
 * the creature's ({@code Mob.checkSpawnRules}). Neither loader has an event between them and the spawn: Fabric
 * has none for natural spawning at all, and NeoForge's spawn-placement event fires per type at registration,
 * not per level. The menu is ours already ({@code AgeChunkGenerator.getMobsAt}); these are the rules after it.
 *
 * <p>The peaceful rule is kept, and so are the collision checks, so nothing spawns inside a wall. Everywhere
 * that is not such an Age, and for a scarab anywhere, vanilla's own answer is returned untouched.
 */
@Mixin(NaturalSpawner.class)
public abstract class NaturalSpawnerMixin {

    @Redirect(
        method = "isValidSpawnPostitionForType",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/SpawnPlacements;isSpawnPositionOk(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)Z"
        )
    )
    private static boolean agesandtheart$placedAnywhere(EntityType<?> type, LevelReader level, BlockPos at) {
        return IncongruousSpawns.anywhere(level, type) || SpawnPlacements.isSpawnPositionOk(type, level, at);
    }

    @Redirect(
        method = "isValidSpawnPostitionForType",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/SpawnPlacements;checkSpawnRules(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/world/entity/EntitySpawnReason;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)Z"
        )
    )
    private static boolean agesandtheart$ruledAnywhere(
        EntityType<?> type,
        ServerLevelAccessor level,
        EntitySpawnReason reason,
        BlockPos at,
        RandomSource random
    ) {
        if (IncongruousSpawns.anywhere(level, type)) {
            return IncongruousSpawns.allowedAtThisDifficulty(level.getLevel(), type);
        }
        return SpawnPlacements.checkSpawnRules(type, level, reason, at, random);
    }

    @Redirect(
        method = "isValidPositionForMob",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Mob;checkSpawnRules(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/world/entity/EntitySpawnReason;)Z"
        )
    )
    private static boolean agesandtheart$willingAnywhere(Mob mob, LevelAccessor level, EntitySpawnReason reason) {
        return (level instanceof ServerLevel server && IncongruousSpawns.anywhere(server, mob.getType()))
            || mob.checkSpawnRules(level, reason);
    }
}
