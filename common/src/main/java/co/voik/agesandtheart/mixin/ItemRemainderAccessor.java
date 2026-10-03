package co.voik.agesandtheart.mixin;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStackTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * What an item leaves behind — {@code Item.craftingRemainingItem}, which is final and set only from
 * {@code Properties.craftRemainder} at construction.
 *
 * <p>That builder makes its template at once, and a template needs the remainder already registered: vanilla
 * registers its items one at a time, so the bucket exists before the lava bucket is made, where ours are all
 * made before any is registered. So contained plasma is given its empty unit here, once both exist. The
 * getter is final, and the loaders' own per-stack remainder hooks have different names on each.
 */
@Mixin(Item.class)
public interface ItemRemainderAccessor {
    @Mutable
    @Accessor("craftingRemainingItem")
    void agesandtheart$setCraftingRemainder(ItemStackTemplate remainder);
}
