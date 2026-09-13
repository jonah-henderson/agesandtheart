package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.book.LecternOpening;
import co.voik.agesandtheart.content.DeepWaterLogging;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Keeps what {@link StateDefinitionBuilderMixin} adds switched off in a block's default state: a block out
 * of the abyss, and a lectern's book shut.
 *
 * <p><b>This is a trap rather than a refinement.</b> {@code BooleanProperty} orders its values
 * {@code List.of(true, false)} and {@code StateDefinition.any()} hands back the first state, so the moment
 * {@link StateDefinitionBuilderMixin} adds a property, every block carrying it defaults to <i>on</i> — a
 * stair placed on dry land in the overworld would carry deep water in it, and every lectern would be placed
 * with its book already open.
 *
 * <p>{@code registerDefaultState} is {@code protected final}, so it is the single funnel every block and
 * every subclass passes through, whatever it computes its own default to be. Modifying the argument means
 * a block that sets its own properties afterwards still ends up with ours off, because it is <i>this</i>
 * call that decides what {@code defaultBlockState()} returns.
 *
 * <p>No loader event exists, and could not: this is a value assigned inside a constructor.
 */
@Mixin(Block.class)
public abstract class BlockDefaultStateMixin {

    @ModifyVariable(method = "registerDefaultState", at = @At("HEAD"), argsOnly = true)
    private BlockState agesandtheart$notByDefault(BlockState state) {
        return LecternOpening.closed(DeepWaterLogging.drained(state));
    }
}
