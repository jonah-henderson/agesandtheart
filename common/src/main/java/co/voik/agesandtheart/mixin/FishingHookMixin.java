package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.generation.AgeFishing;
import java.util.List;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * What fishing in an Age gives ({@code AgeFishing}): rich waters' luck, and fish drawn by what the book asked to
 * live there.
 *
 * <p>{@code retrieve} builds the catch's loot parameters and rolls vanilla's fishing table in one method. Fabric has
 * no fishing event, and NeoForge's {@code ItemFishedEvent} comes after the roll with no say in the luck it was rolled
 * with. A loot modifier could not tell one Age from another.
 */
@Mixin(FishingHook.class)
public abstract class FishingHookMixin {

    private static final String WITH_LUCK = "Lnet/minecraft/world/level/storage/loot/LootParams$Builder;"
        + "withLuck(F)Lnet/minecraft/world/level/storage/loot/LootParams$Builder;";

    @ModifyArg(method = "retrieve", at = @At(value = "INVOKE", target = WITH_LUCK))
    private float agesandtheart$richWatersFishLuckier(float luck) {
        return luck + AgeFishing.INSTANCE.luckIn(((FishingHook) (Object) this).level());
    }

    @ModifyVariable(method = "retrieve", at = @At("STORE"), name = "items")
    private List<ItemStack> agesandtheart$caughtAsTheBookStockedTheWaters(List<ItemStack> items) {
        return AgeFishing.INSTANCE.caught(items, (FishingHook) (Object) this);
    }
}
