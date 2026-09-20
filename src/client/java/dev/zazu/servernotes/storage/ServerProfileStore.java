package dev.zazu.servernotes.storage;

import dev.zazu.servernotes.model.ServerIdentity;
import dev.zazu.servernotes.model.ServerProfile;
import dev.zazu.servernotes.util.SimpleJson;
import dev.zazuzin.zst.ConfigPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class ServerProfileStore {
    public static final int SCHEMA_VERSION = 2;

    private final Path directory;
    private final Path file;
    private final Path backupFile;
    private final LinkedHashMap<String, ServerProfile> profiles = new LinkedHashMap<>();

    public ServerProfileStore(Path configDirectory) {
        this.directory = ConfigPaths.seekerDirectory(configDirectory).resolve("server-notes");
        this.file = directory.resolve("server-profiles.json");
        this.backupFile = directory.resolve("server-profiles.json.bak");
        load();
    }

    public synchronized ServerProfile getOrCreate(ServerIdentity identity) {
        ServerProfile profile = profiles.get(identity.key());
        if (profile == null) {
            profile = new ServerProfile(identity);
            profiles.put(identity.key(), profile);
            save();
        }
        return profile;
    }

    public synchronized Optional<ServerProfile> find(String key) {
        return Optional.ofNullable(profiles.get(key));
    }

    public synchronized void saveProfile(ServerProfile profile) {
        profile.touch();
        profiles.put(profile.key(), profile);
        save();
    }

    public synchronized Map<String, ServerProfile> snapshot() {
        return Map.copyOf(profiles);
    }

    public Path file() {
        return file;
    }

    @SuppressWarnings("unchecked")
    private synchronized void load() {
        profiles.clear();
        if (!Files.exists(file)) {
            return;
        }

        try {
            loadFrom(file);
        } catch (RuntimeException | IOException primaryFailure) {
            preserveCorruptFile();
            profiles.clear();
            if (Files.exists(backupFile)) {
                try {
                    loadFrom(backupFile);
                    Files.deleteIfExists(file);
                    save();
                    return;
                } catch (RuntimeException | IOException ignored) {
                    profiles.clear();
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void loadFrom(Path source) throws IOException {
        String json = Files.readString(source, StandardCharsets.UTF_8);
        Object parsed = SimpleJson.parse(json);
        if (!(parsed instanceof Map<?, ?> rootRaw)) {
            throw new IllegalArgumentException("Root JSON value is not an object");
        }

        Map<String, Object> root = (Map<String, Object>) rootRaw;
        int schemaVersion = root.get("schemaVersion") instanceof Number number ? number.intValue() : 1;
        if (schemaVersion > SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported future schemaVersion " + schemaVersion);
        }

        Object serversValue = root.get("servers");
        if (!(serversValue instanceof Map<?, ?> serverMap)) {
            return;
        }

        for (Map.Entry<?, ?> entry : serverMap.entrySet()) {
            if (!(entry.getKey() instanceof String key) || !(entry.getValue() instanceof Map<?, ?> rawProfile)) {
                continue;
            }
            try {
                ServerProfile profile = ServerProfile.fromMap(key, (Map<String, Object>) rawProfile);
                profiles.put(profile.key(), profile);
            } catch (RuntimeException ignored) {
                // One malformed profile must not block the remaining profiles.
            }
        }
    }

    private synchronized void save() {
        try {
            Files.createDirectories(directory);
            LinkedHashMap<String, Object> root = new LinkedHashMap<>();
            root.put("schemaVersion", SCHEMA_VERSION);
            root.put("servers", serializeProfiles());

            String json = SimpleJson.stringify(root);
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, json, StandardCharsets.UTF_8);

            if (Files.exists(file)) {
                Files.copy(file, backupFile, StandardCopyOption.REPLACE_EXISTING);
            }

            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            System.err.println("[Zazu's Server Notes] Failed to save profiles: " + exception.getMessage());
        }
    }

    private Map<String, Object> serializeProfiles() {
        LinkedHashMap<String, Object> serverMap = new LinkedHashMap<>();
        for (Map.Entry<String, ServerProfile> entry : profiles.entrySet()) {
            serverMap.put(entry.getKey(), entry.getValue().toMap());
        }
        return serverMap;
    }

    private void preserveCorruptFile() {
        try {
            Files.createDirectories(directory);
            if (!Files.exists(file)) {
                return;
            }
            String suffix = Instant.now().toString().replace(':', '-');
            Path corrupt = directory.resolve("server-profiles.corrupt-" + suffix + ".json");
            Files.copy(file, corrupt, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        }
    }
}
