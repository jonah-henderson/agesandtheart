package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.station.GrindstoneWear;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Draws a worn grindstone's runs left as a durability bar, read off the wear its {@code BLOCK_STATE}
 * component carries.
 *
 * <p><b>Why a Mixin.</b> Fabric has no item-decoration event, and NeoForge's {@code IItemDecorator} would
 * leave the two loaders drawing it differently. Giving the item real {@code DAMAGE} instead would draw the
 * bar for free, but would make it repairable at an anvil, a grindstone and the crafting grid, and leave a
 * second copy of the wear that those repairs move and placing it ignores.
 *
 * <p>The grindstone's item is a plain {@code BlockItem}, which overrides none of the three.
 */
@Mixin(Item.class)
public abstract class GrindstoneBarMixin {

    @Inject(method = "isBarVisible", at = @At("HEAD"), cancellable = true)
    private void agesandtheart$showAGrindstonesWear(ItemStack stack, CallbackInfoReturnable<Boolean> callback) {
        if (GrindstoneWear.wearCarriedBy(stack) != null) callback.setReturnValue(true);
    }

    @Inject(method = "getBarWidth", at = @At("HEAD"), cancellable = true)
    private void agesandtheart$measureAGrindstonesWear(ItemStack stack, CallbackInfoReturnable<Integer> callback) {
        Integer worn = GrindstoneWear.wearCarriedBy(stack);
        if (worn != null) callback.setReturnValue(GrindstoneWear.barWidthFor(worn));
    }

    @Inject(method = "getBarColor", at = @At("HEAD"), cancellable = true)
    private void agesandtheart$colourAGrindstonesWear(ItemStack stack, CallbackInfoReturnable<Integer> callback) {
        Integer worn = GrindstoneWear.wearCarriedBy(stack);
        if (worn != null) callback.setReturnValue(GrindstoneWear.barColourFor(worn));
    }
}
