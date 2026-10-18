package dev.zazu.servernotes.service;

import dev.zazu.servernotes.model.ServerPlayerRecord;
import dev.zazu.servernotes.model.ServerProfile;
import dev.zazu.servernotes.storage.ServerProfileStore;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;

public final class ServerPlayerService {
    private final ServerProfileStore store;

    public ServerPlayerService(ServerProfileStore store) {
        this.store = store;
    }

    public void sync(ServerProfile profile, Collection<ObservedPlayer> observedPlayers, Set<String> previouslyOnline) {
        LinkedHashMap<String, ObservedPlayer> current = new LinkedHashMap<>();
        for (ObservedPlayer observed : observedPlayers) {
            if (observed == null || observed.key() == null || observed.key().isBlank()) {
                continue;
            }
            current.put(observed.key(), observed);
        }

        boolean changed = false;
        for (ObservedPlayer observed : current.values()) {
            ServerPlayerRecord record = profile.players().get(observed.key());
            if (record == null) {
                profile.players().put(observed.key(), new ServerPlayerRecord(observed.key(), observed.uuid(), observed.username()));
                changed = true;
            } else if (!previouslyOnline.contains(observed.key())) {
                record.beginEncounter(observed.uuid(), observed.username());
                changed = true;
            } else if (record.refreshIdentity(observed.uuid(), observed.username())) {
                changed = true;
            }
        }

        for (String departedKey : previouslyOnline) {
            if (current.containsKey(departedKey)) {
                continue;
            }
            ServerPlayerRecord record = profile.players().get(departedKey);
            if (record != null) {
                record.markLastSeenNow();
                changed = true;
            }
        }

        if (changed) {
            store.saveProfile(profile);
        }
    }


    public void removeLocalPlayer(ServerProfile profile, String uuid, String username) {
        String normalizedUuid = uuid == null ? "" : uuid.trim();
        String normalizedName = username == null ? "" : username.trim();
        if (normalizedUuid.isBlank() && normalizedName.isBlank()) {
            return;
        }

        boolean changed = profile.players().entrySet().removeIf(entry -> {
            ServerPlayerRecord record = entry.getValue();
            if (record == null) {
                return false;
            }
            if (!normalizedUuid.isBlank() && record.uuid() != null
                && normalizedUuid.equalsIgnoreCase(record.uuid())) {
                return true;
            }
            return !normalizedName.isBlank() && record.username() != null
                && normalizedName.equalsIgnoreCase(record.username());
        });

        if (changed) {
            store.saveProfile(profile);
        }
    }

    public Set<String> keys(Collection<ObservedPlayer> players) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (ObservedPlayer player : players) {
            if (player != null && player.key() != null && !player.key().isBlank()) {
                keys.add(player.key());
            }
        }
        return keys;
    }

    public record ObservedPlayer(String key, String uuid, String username) {
    }
}
