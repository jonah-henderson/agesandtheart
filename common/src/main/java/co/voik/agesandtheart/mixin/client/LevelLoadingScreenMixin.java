package co.voik.agesandtheart.mixin.client;

import co.voik.agesandtheart.client.LinkTransit;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps a link's picture behind vanilla's loading screen, which replaces the transit the moment the player is
 * moved, so the link reads as one passage rather than a picture cut to a menu background.
 *
 * <p>Vanilla chooses this background from where the player is going — a portal's for the nether or the End, and
 * the menu's for anywhere else — and an Age is anywhere else. While {@link LinkTransit#isUnderway()} it draws
 * the transit's picture instead and leaves the words and the chunk map above it to vanilla.
 *
 * <p><b>Why a Mixin, and what was checked first.</b> NeoForge's screen-opening event could swap the screen for
 * one of ours, but Fabric has no event that replaces a screen, and a substitute would have to reimplement the
 * load tracking vanilla's own screen closes itself on. Drawing the background is the whole of what differs.
 */
@Mixin(LevelLoadingScreen.class)
public abstract class LevelLoadingScreenMixin extends Screen {

    private LevelLoadingScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
    private void agesandtheart$drawTheLinksPicture(
            GuiGraphicsExtractor graphics,
            int mouseX,
            int mouseY,
            float a,
            CallbackInfo callback) {
        if (!LinkTransit.INSTANCE.isUnderway()) return;
        LinkTransit.INSTANCE.drawHeld(graphics, this.width, this.height);
        callback.cancel();
    }
}
