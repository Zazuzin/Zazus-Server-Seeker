package dev.zazu.servernotes.service;

import dev.zazu.servernotes.model.ServerProfile;
import dev.zazu.servernotes.storage.ServerProfileStore;
import dev.zazu.servernotes.util.ServerAddressNormalizer;

/** Persists an Auto Join failure as a normal, user-visible server note. */
public final class AutoJoinFailureNotes {
    public static final String CATEGORY = "Auto Join Failure";

    private AutoJoinFailureNotes() {}

    public static void record(ServerProfileStore store, String endpoint, String reason) {
        if (store == null) throw new IllegalArgumentException("store");
        ServerProfile profile = store.getOrCreate(ServerAddressNormalizer.normalize(endpoint));
        new ServerNotesService(store).add(profile, normalizedReason(reason), CATEGORY);
    }

    private static String normalizedReason(String reason) {
        String clean = reason == null ? "" : reason.replace('\n', ' ').replace('\r', ' ')
                .replaceAll("\\s+", " ").trim();
        return clean.isBlank() ? "Connection failed (Minecraft did not provide a reason)." : clean;
    }
}
