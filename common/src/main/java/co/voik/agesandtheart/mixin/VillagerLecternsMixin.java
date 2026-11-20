package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.book.VillagersAtOurLecterns;
import com.google.common.collect.ImmutableList;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.VillagerGoalPackages;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.BiPredicate;

/**
 * Villagers leave lecterns holding a book of ours alone ({@link VillagersAtOurLecterns}).
 *
 * <p><b>Why a Mixin.</b> Neither loader has an event for a villager choosing or keeping a job site. The search
 * already takes a per-position check, which vanilla passes as always yes for a job site; the villager's core
 * package is the one list its brain is built from, so the behaviour that lets go of a lectern goes in there.
 * A lectern's block state cannot say whose book it holds, so the POI type's states cannot leave it out.
 */
@Mixin(VillagerGoalPackages.class)
public abstract class VillagerLecternsMixin {

    @ModifyArg(
            method = "getCorePackage",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/ai/behavior/AcquirePoi;create(Ljava/util/function/Predicate;Lnet/minecraft/world/entity/ai/memory/MemoryModuleType;Lnet/minecraft/world/entity/ai/memory/MemoryModuleType;ZLjava/util/Optional;Ljava/util/function/BiPredicate;)Lnet/minecraft/world/entity/ai/behavior/BehaviorControl;"),
            index = 5)
    private static BiPredicate<ServerLevel, BlockPos> agesandtheart$notAtOurLecterns(BiPredicate<ServerLevel, BlockPos> vanilla) {
        return (level, pos) -> vanilla.test(level, pos) && VillagersAtOurLecterns.mayWorkAt(level, pos);
    }

    @Inject(method = "getCorePackage", at = @At("RETURN"), cancellable = true)
    private static void agesandtheart$leaveOurLecterns(
            CallbackInfoReturnable<ImmutableList<Pair<Integer, ? extends BehaviorControl<? super Villager>>>> callback) {
        callback.setReturnValue(VillagersAtOurLecterns.withLeaving(callback.getReturnValue()));
    }
}
