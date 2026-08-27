package co.voik.agesandtheart.mixin;

import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The dragon a phase belongs to, reachable from a mixin on one of its subclasses.
 *
 * <p>{@code dragon} is declared {@code protected final} on {@code AbstractDragonPhaseInstance}, and
 * {@code @Shadow} does not walk a target's hierarchy — a shadow of it on
 * {@code DragonHoldingPatternMixin} fails at apply with "field dragon was not located in the target
 * class", which is a crash on first dragon rather than a warning. An accessor on the class that really
 * declares it is the documented way round, and it changes no behaviour.
 */
@Mixin(net.minecraft.world.entity.boss.enderdragon.phases.AbstractDragonPhaseInstance.class)
public interface DragonPhaseAccessor {
    @Accessor("dragon")
    EnderDragon agesandtheart$dragon();
}
