package dev.zazu.servernotes.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.zazu.servernotes.model.ServerProfile;
import dev.zazu.servernotes.storage.ServerProfileStore;
import dev.zazu.servernotes.ui.ServerNotesScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.Optional;

public final class ZazusServerNotesClient implements ClientModInitializer {
    public static final String MOD_ID = "zazus_server_notes";
    private static ServerNotesApp app;

    @Override
    public void onInitializeClient() {
        ServerProfileStore store = new ServerProfileStore(FabricLoader.getInstance().getConfigDir());
        app = new ServerNotesApp(store);
        KeyMapping.Category category = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath(MOD_ID, "main")
        );
        KeyMapping openNotes = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.zazus_server_notes.open",
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_N,
            category
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            app.currentServer().tick(client);
            app.playerTracking().tick(client);

            while (openNotes.consumeClick()) {
                if (client.gui.screen() != null) {
                    continue;
                }
                Optional<ServerProfile> profile = app.currentServer().currentProfile(client);
                if (profile.isPresent()) {
                    client.gui.setScreen(new ServerNotesScreen(null, profile.get().key()));
                } else if (client.player != null) {
                    client.player.sendSystemMessage(Component.literal("Zazu's Server Notes: join a multiplayer server first."));
                }
            }
        });
    }

    public static ServerNotesApp app() {
        if (app == null) {
            throw new IllegalStateException("Zazu's Server Notes has not initialized yet");
        }
        return app;
    }
}
