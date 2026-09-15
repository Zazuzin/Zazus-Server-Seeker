package dev.zazu.servernotes.service;

import dev.zazu.servernotes.model.ServerNote;
import dev.zazu.servernotes.model.ServerProfile;
import dev.zazu.servernotes.storage.ServerProfileStore;

import java.util.Optional;

public final class ServerNotesService {
    private final ServerProfileStore store;

    public ServerNotesService(ServerProfileStore store) {
        this.store = store;
    }

    public ServerNote add(ServerProfile profile, String text, String category) {
        ServerNote note = new ServerNote(text.trim(), category == null ? "" : category.trim());
        profile.notes().add(0, note);
        store.saveProfile(profile);
        return note;
    }

    public boolean edit(ServerProfile profile, String noteId, String text, String category) {
        Optional<ServerNote> note = profile.notes().stream().filter(item -> item.id().equals(noteId)).findFirst();
        if (note.isEmpty()) {
            return false;
        }
        note.get().update(text.trim(), category == null ? "" : category.trim());
        store.saveProfile(profile);
        return true;
    }

    public boolean delete(ServerProfile profile, String noteId) {
        boolean removed = profile.notes().removeIf(note -> note.id().equals(noteId));
        if (removed) {
            store.saveProfile(profile);
        }
        return removed;
    }
}
