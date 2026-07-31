package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.age.word.LearnedWords;
import co.voik.agesandtheart.age.word.LearnedWordsHolder;
import co.voik.agesandtheart.age.word.PageLearning;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerListener;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gives a player the words they know, along the same four seams vanilla gives them their recipe book:
 * save, load, copy-on-rebuild, and a listener on every menu they hold.
 *
 * <p>{@code initMenu} is the only funnel for "this player got a menu" — it runs once for the always-open
 * inventory menu and again for each container opened. Both are needed: on close, {@code transferState}
 * copies {@code lastSlots} across, so anything picked up while a chest was open is absorbed and never
 * reported by the inventory menu's own listener.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin implements LearnedWordsHolder {

    @Unique
    private LearnedWords agesandtheart$learned;

    @Unique
    private ContainerListener agesandtheart$listener;

    /** Lazily built rather than an initialiser, which Mixin does not merge into the target constructor. */
    @Override
    public LearnedWords agesandtheart_learnedWords() {
        if (this.agesandtheart$learned == null) {
            this.agesandtheart$learned = new LearnedWords();
        }
        return this.agesandtheart$learned;
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void agesandtheart$read(ValueInput input, CallbackInfo ci) {
        input.read(LearnedWords.SAVE_KEY, LearnedWords.Packed.CODEC)
                .ifPresent(packed -> this.agesandtheart_learnedWords().load(packed));
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void agesandtheart$write(ValueOutput output, CallbackInfo ci) {
        output.store(LearnedWords.SAVE_KEY, LearnedWords.Packed.CODEC, this.agesandtheart_learnedWords().pack());
    }

    @Inject(method = "restoreFrom", at = @At("TAIL"))
    private void agesandtheart$restore(ServerPlayer oldPlayer, boolean restoreAll, CallbackInfo ci) {
        this.agesandtheart_learnedWords().copyFrom(((LearnedWordsHolder) oldPlayer).agesandtheart_learnedWords());
    }

    /** One listener per player, not per menu, so {@code addSlotListener}'s own dedupe can work. */
    @Inject(method = "initMenu", at = @At("TAIL"))
    private void agesandtheart$listen(AbstractContainerMenu container, CallbackInfo ci) {
        if (this.agesandtheart$listener == null) {
            this.agesandtheart$listener = PageLearning.listenerFor((ServerPlayer) (Object) this);
        }
        container.addSlotListener(this.agesandtheart$listener);
    }
}
