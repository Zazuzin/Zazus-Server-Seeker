package dev.zazu.servernotes.service;

import dev.zazu.servernotes.model.ServerLocation;
import dev.zazu.servernotes.model.ServerProfile;
import dev.zazu.servernotes.storage.ServerProfileStore;

import java.util.Optional;

public final class ServerLocationService {
    private final ServerProfileStore store;

    public ServerLocationService(ServerProfileStore store) {
        this.store = store;
    }

    public ServerLocation add(ServerProfile profile, String name, double x, double y, double z, String dimension) {
        ServerLocation location = new ServerLocation(name.trim(), x, y, z, dimension);
        profile.locations().add(0, location);
        store.saveProfile(profile);
        return location;
    }

    public boolean edit(ServerProfile profile, String id, String name, double x, double y, double z, String dimension) {
        Optional<ServerLocation> location = profile.locations().stream().filter(item -> item.id().equals(id)).findFirst();
        if (location.isEmpty()) {
            return false;
        }
        location.get().update(name.trim(), x, y, z, dimension.trim());
        store.saveProfile(profile);
        return true;
    }

    public boolean delete(ServerProfile profile, String id) {
        boolean removed = profile.locations().removeIf(location -> location.id().equals(id));
        if (removed) {
            store.saveProfile(profile);
        }
        return removed;
    }
}
