package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.content.RimeSkates;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.PowderSnowBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets a skater cross powder snow the way leather boots do ({@link RimeSkates#wornBy}).
 *
 * <p><b>Why a mixin, and the three alternatives that were checked.</b> There is <b>no item tag</b>:
 * {@code ItemTags} carries {@code FREEZE_IMMUNE_WEARABLES}, which is how the skates answer the freezing
 * half of this, and nothing at all for walking on snow — a datapack line was the wanted answer and does
 * not exist. {@code ItemStack.canWalkOnPowderedSnow} looks like the seam and is <b>NeoForge's</b>, not
 * vanilla's: searching the whole source jar, {@code PowderSnowBlock} is the only file that so much as
 * mentions it, the declaration living in NeoForge's own classes. Vanilla's test is
 * {@code stack.is(Items.LEATHER_BOOTS)}, hard-coded, and Fabric API adds nothing in its place.
 *
 * <p>So the choice was this — one vanilla seam serving both loaders — or a NeoForge {@code Item} override
 * <i>plus</i> a Fabric-only mixin doing the same thing, which is two implementations of one rule and still
 * a mixin. Worse, taking the NeoForge override <i>alone</i> compiles here (common builds against the
 * patched jar) and would silently do nothing on Fabric.
 *
 * <p><b>What it is for.</b> Skates are the cold Age's movement answer, and powder snow is the cold Age's
 * floor; a pair that let you cross an ice field and dropped you into the first drift would be answering
 * half of the thing it was made for. Freezing was already covered — the skates carry the freeze-immune
 * tag — so this is the other half of the same promise.
 */
@Mixin(PowderSnowBlock.class)
public abstract class PowderSnowBlockMixin {

    @Inject(method = "canEntityWalkOnPowderSnow", at = @At("HEAD"), cancellable = true)
    private static void agesandtheart$skatersStayOnTop(Entity entity, CallbackInfoReturnable<Boolean> walks) {
        if (RimeSkates.wornBy(entity)) {
            walks.setReturnValue(true);
        }
    }
}
