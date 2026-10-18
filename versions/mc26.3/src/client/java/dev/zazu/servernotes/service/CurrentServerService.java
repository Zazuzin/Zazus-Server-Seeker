package dev.zazu.servernotes.service;

import dev.zazu.servernotes.model.ServerIdentity;
import dev.zazu.servernotes.model.ServerProfile;
import dev.zazu.servernotes.storage.ServerProfileStore;
import dev.zazu.servernotes.util.ServerAddressNormalizer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.util.Optional;

public final class CurrentServerService {
    private final ServerProfileStore store;
    private String lastObservedKey;
    private String lastObservedAddress;

    public CurrentServerService(ServerProfileStore store) {
        this.store = store;
    }

    public Optional<ServerIdentity> currentIdentity(Minecraft minecraft) {
        ServerData serverData = minecraft.getCurrentServer();
        if (serverData == null || serverData.ip == null || serverData.ip.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(ServerAddressNormalizer.normalize(serverData.ip));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public Optional<ServerProfile> currentProfile(Minecraft minecraft) {
        return currentIdentity(minecraft).map(store::getOrCreate);
    }

    public void tick(Minecraft minecraft) {
        ServerData serverData = minecraft.getCurrentServer();
        String address = serverData == null || serverData.ip == null ? "" : serverData.ip.trim();
        if (address.isBlank()) {
            lastObservedKey = null;
            lastObservedAddress = null;
            return;
        }

        // The connected address is stable for the whole session. Avoid parsing,
        // IDN-normalising and allocating a ServerIdentity every client tick.
        if (address.equals(lastObservedAddress)) return;

        Optional<ServerIdentity> identity = currentIdentity(minecraft);
        if (identity.isEmpty()) return;
        ServerIdentity value = identity.get();
        if (!value.key().equals(lastObservedKey)) {
            ServerProfile profile = store.getOrCreate(value);
            profile.markJoined(value.displayAddress());
            store.saveProfile(profile);
            lastObservedKey = value.key();
        }
        lastObservedAddress = address;
    }
}
