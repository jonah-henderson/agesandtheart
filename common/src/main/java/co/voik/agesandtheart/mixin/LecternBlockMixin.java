package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.book.LecternBooks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * What a lectern does with a book of ours on it (design §7.8.2) — opened, read, linked from, shut and taken
 * back — and shutting it again when nobody is near.
 *
 * <p><b>Why a mixin, and what was checked first.</b> Both loaders have a block-use event (NeoForge's
 * {@code RightClickBlock}, Fabric API's {@code UseBlockCallback}), so the click alone could have gone that
 * way — as two implementations of one rule behind the SPI, which is the trade
 * {@link CarvedPumpkinBlockMixin} declined for the same reason. The close has no event on either.
 *
 * <p><b>{@code useWithoutItem} is the whole funnel.</b> {@code useItemOn} answers
 * {@code TRY_WITH_EMPTY_HAND} whenever the lectern holds a book, so a click with a full hand arrives here
 * too. It is also the only place vanilla opens its own lectern screen, which is what our books must not get.
 * Anything that is not one of ours is left to vanilla untouched.
 *
 * <p><b>The close rides the lectern's scheduled tick</b>, which vanilla already has for its redstone
 * pulse, rather than a ticker it does not have. At {@code TAIL}, because vanilla's own write in there is
 * made from the state it was handed, and would put back a book this had already shut.
 */
@Mixin(LecternBlock.class)
public abstract class LecternBlockMixin {

    @Inject(method = "useWithoutItem", at = @At("HEAD"), cancellable = true)
    private void agesandtheart$useOurBook(
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            BlockHitResult hit,
            CallbackInfoReturnable<InteractionResult> result) {
        InteractionResult ours = LecternBooks.use(state, level, pos, player, hit);
        if (ours != null) {
            result.setReturnValue(ours);
        }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void agesandtheart$shutAnUnwatchedBook(
            BlockState state,
            ServerLevel level,
            BlockPos pos,
            RandomSource random,
            CallbackInfo callback) {
        LecternBooks.shutIfUnwatched(level, pos);
    }
}
