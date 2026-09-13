package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.book.LecternOpening;
import co.voik.agesandtheart.content.DeepWaterLogging;
import java.util.Map;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Gives vanilla's blocks two properties they lack: every waterloggable block a second waterlogging, for the
 * abyss (design §7.1.2), and every lectern whether its book lies open (§7.8.2).
 *
 * <p><b>Why a Mixin.</b> Neither loader has an event for adding a block state property, and there could
 * not be one: a block's state definition is built inside its own constructor and frozen there. NeoForge's
 * {@code ModifyDefaultComponentsEvent} is the nearest thing and is about item components, not states.
 *
 * <p><b>Why <i>here</i>, of all the places it could go.</b> {@code Block}'s constructor makes a builder,
 * hands it to {@code createBlockStateDefinition}, and calls {@code create}. Injecting into
 * {@code createBlockStateDefinition} would reach only the blocks that call {@code super}, which most
 * overrides do not; injecting into the constructor means capturing a local. This is the one point every
 * block passes through with its properties already declared and the definition not yet built — and the
 * builder's own map is a plain mutable {@code HashMap} at that moment.
 *
 * <p>It reaches modded waterloggable blocks for free, which is the argument for gating on the interface
 * rather than on a list of ids. {@code SimpleWaterloggedBlock} is an exact gate: measured over all 1,168
 * vanilla blocks, nothing implements it without carrying {@code waterlogged} and nothing carries
 * {@code waterlogged} without implementing it ({@code ./gradlew :common:waterlogging}).
 *
 * <p>The same builder builds fluid state definitions, whose owner is a {@code Fluid} — which is why the
 * gate asks what the owner <i>is</i> rather than what it has.
 */
@Mixin(StateDefinition.Builder.class)
public abstract class StateDefinitionBuilderMixin {

    @Shadow
    @Final
    private Object owner;

    @Shadow
    @Final
    private Map<String, Property<?>> properties;

    @Inject(method = "create", at = @At("HEAD"))
    private void agesandtheart$alsoLogTheAbyss(CallbackInfoReturnable<StateDefinition<?, ?>> callback) {
        if (DeepWaterLogging.belongsOn(this.owner)) {
            this.properties.put(DeepWaterLogging.DEEP_WATERLOGGED.getName(), DeepWaterLogging.DEEP_WATERLOGGED);
        }
    }

    @Inject(method = "create", at = @At("HEAD"))
    private void agesandtheart$letALecternsBookLieOpen(CallbackInfoReturnable<StateDefinition<?, ?>> callback) {
        if (LecternOpening.belongsOn(this.owner)) {
            this.properties.put(LecternOpening.BOOK_OPEN.getName(), LecternOpening.BOOK_OPEN);
        }
    }
}
