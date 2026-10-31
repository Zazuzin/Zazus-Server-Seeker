package dev.zazuzin.zst.mixin;

import dev.zazuzin.zst.ServerPingPlayerCollector;
import net.minecraft.network.protocol.status.ClientStatusPacketListener;
import net.minecraft.network.protocol.status.ClientboundStatusResponsePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observe the reply already requested by Minecraft's saved-server pinger. */
@Mixin(ClientboundStatusResponsePacket.class)
abstract class StatusResponsePacketMixin {
    @Inject(method = "handle(Lnet/minecraft/network/protocol/status/ClientStatusPacketListener;)V", at = @At("TAIL"))
    private void zazusServerSeeker$recordSample(ClientStatusPacketListener listener, CallbackInfo callback) {
        ServerPingPlayerCollector.nativeStatus(listener, ((ClientboundStatusResponsePacket) (Object) this).status());
    }
}
