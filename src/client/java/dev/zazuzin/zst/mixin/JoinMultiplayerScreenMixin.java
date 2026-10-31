package dev.zazuzin.zst.mixin;

import dev.zazuzin.zst.ServerFinderClient;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep Escape on the Multiplayer screen while the Seeker overlay is open. */
@Mixin(JoinMultiplayerScreen.class)
abstract class JoinMultiplayerScreenMixin {
    @Inject(method = "onClose()V", at = @At("HEAD"), cancellable = true)
    private void zazusServerSeeker$closeOverlayFirst(CallbackInfo callback) {
        if (ServerFinderClient.closeForScreen(this)) callback.cancel();
    }
}
