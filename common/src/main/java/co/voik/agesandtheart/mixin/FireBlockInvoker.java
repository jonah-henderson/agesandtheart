package co.voik.agesandtheart.mixin;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FireBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * What takes fire, and how readily — {@code FireBlock.setFlammable}, which is private and the only door.
 *
 * <p>Both loaders read the same table: Fabric's flammable registry sits beside it, and NeoForge's
 * {@code getFlammability} falls back on it for a block that does not override. So one call reaches both,
 * where either loader's own route would have been a second registration to keep in step.
 */
@Mixin(FireBlock.class)
public interface FireBlockInvoker {
    @Invoker("setFlammable")
    void agesandtheart$setFlammable(Block block, int igniteOdds, int burnOdds);
}
