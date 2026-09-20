package dev.zazuzin.zst.mixin;

import dev.zazuzin.zst.MultiplayerManagementEntrypoint;
import dev.zazuzin.zst.RowDoubleClick;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ServerData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Restores saved-server double-click joining at Minecraft's own row boundary.
 *
 * Server Seeker's category UI adds screen-level widgets around the vanilla
 * list. If Minecraft does not mark the second row click as a double-click, this
 * per-entry fallback calls the same join() action used by vanilla and by the
 * server-logo play button.
 */
@Mixin(ServerSelectionList.OnlineServerEntry.class)
abstract class OnlineServerEntryMixin {
    private static final int PRIMARY_MOUSE_BUTTON = 1;

    @Unique
    private String zazusServerSeeker$lastEndpoint = "";

    @Unique
    private long zazusServerSeeker$lastClickAtNanos;

    @Shadow
    public abstract ServerData getServerData();

    @Shadow
    public abstract void join();

    @Inject(
            method = "mouseClicked(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z",
            at = @At("HEAD"),
            cancellable = true
    )
    private void zazusServerSeeker$restoreDoubleClick(MouseButtonEvent mouse, boolean vanillaDoubleClick,
                                                       CallbackInfoReturnable<Boolean> callback) {
        if (mouse == null || mouse.button() != PRIMARY_MOUSE_BUTTON) return;

        String endpoint = MultiplayerManagementEntrypoint.prepareNativeServerEntryClick(getServerData());
        if (endpoint.isBlank()) return;

        long now = System.nanoTime();
        boolean fallbackDoubleClick = !vanillaDoubleClick && RowDoubleClick.isSecondClick(
                zazusServerSeeker$lastEndpoint, zazusServerSeeker$lastClickAtNanos, endpoint, now);

        if (vanillaDoubleClick || fallbackDoubleClick) {
            zazusServerSeeker$lastEndpoint = "";
            zazusServerSeeker$lastClickAtNanos = 0L;
        } else {
            zazusServerSeeker$lastEndpoint = endpoint;
            zazusServerSeeker$lastClickAtNanos = now;
        }

        if (!fallbackDoubleClick) return;

        join();
        callback.setReturnValue(true);
    }
}
