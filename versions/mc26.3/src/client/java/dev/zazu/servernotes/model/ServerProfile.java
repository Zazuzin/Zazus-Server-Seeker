package dev.zazu.servernotes.model;

import dev.zazu.servernotes.util.TimeUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ServerProfile {
    private String key;
    private String address;
    private int port;
    private String displayAddress;
    private String customName;
    private String generalNotes;
    private boolean favourite;
    private String createdAt;
    private String updatedAt;
    private String lastJoinedAt;
    private String serverVersion;
    private String authenticationType;
    private final List<ServerNote> notes = new ArrayList<>();
    private final List<ServerLocation> locations = new ArrayList<>();
    private final LinkedHashMap<String, ServerPlayerRecord> players = new LinkedHashMap<>();
    private final LinkedHashSet<String> tags = new LinkedHashSet<>();
    private final LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();

    public ServerProfile(ServerIdentity identity) {
        String now = TimeUtil.now();
        this.key = identity.key();
        this.address = identity.host();
        this.port = identity.port();
        this.displayAddress = identity.displayAddress();
        this.customName = "";
        this.generalNotes = "";
        this.createdAt = now;
        this.updatedAt = now;
        this.lastJoinedAt = now;
        this.serverVersion = "";
        this.authenticationType = "unknown";
    }

    public String key() { return key; }
    public String address() { return address; }
    public int port() { return port; }
    public String displayAddress() { return displayAddress; }
    public String customName() { return customName; }
    public String generalNotes() { return generalNotes; }
    public boolean favourite() { return favourite; }
    public String createdAt() { return createdAt; }
    public String updatedAt() { return updatedAt; }
    public String lastJoinedAt() { return lastJoinedAt; }
    public String serverVersion() { return serverVersion; }
    public String authenticationType() { return authenticationType; }
    public List<ServerNote> notes() { return notes; }
    public List<ServerLocation> locations() { return locations; }
    public Map<String, ServerPlayerRecord> players() { return players; }
    public Set<String> tags() { return tags; }
    public Map<String, Object> metadata() { return metadata; }

    public void markJoined(String displayAddress) {
        this.displayAddress = displayAddress;
        this.lastJoinedAt = TimeUtil.now();
        touch();
    }

    public void setFavourite(boolean favourite) {
        this.favourite = favourite;
        touch();
    }

    public void touch() {
        this.updatedAt = TimeUtil.now();
    }

    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("key", key);
        map.put("address", address);
        map.put("port", port);
        map.put("displayAddress", displayAddress);
        map.put("customName", customName);
        map.put("generalNotes", generalNotes);
        map.put("favourite", favourite);
        map.put("createdAt", createdAt);
        map.put("updatedAt", updatedAt);
        map.put("lastJoinedAt", lastJoinedAt);
        map.put("serverVersion", serverVersion);
        map.put("authenticationType", authenticationType);
        map.put("tags", new ArrayList<>(tags));
        map.put("notes", notes.stream().map(ServerNote::toMap).toList());
        map.put("locations", locations.stream().map(ServerLocation::toMap).toList());
        LinkedHashMap<String, Object> playerMap = new LinkedHashMap<>();
        for (Map.Entry<String, ServerPlayerRecord> entry : players.entrySet()) {
            playerMap.put(entry.getKey(), entry.getValue().toMap());
        }
        map.put("players", playerMap);
        map.put("metadata", new LinkedHashMap<>(metadata));
        return map;
    }

    @SuppressWarnings("unchecked")
    public static ServerProfile fromMap(String fallbackKey, Map<String, Object> map) {
        String address = string(map, "address", "unknown");
        int port = integer(map, "port", 25565);
        String key = string(map, "key", fallbackKey);
        ServerProfile profile = new ServerProfile(new ServerIdentity(key, address, port, string(map, "displayAddress", key)));
        profile.key = key;
        profile.customName = string(map, "customName", "");
        profile.generalNotes = string(map, "generalNotes", "");
        profile.favourite = bool(map, "favourite", false);
        profile.createdAt = string(map, "createdAt", profile.createdAt);
        profile.updatedAt = string(map, "updatedAt", profile.updatedAt);
        profile.lastJoinedAt = string(map, "lastJoinedAt", profile.lastJoinedAt);
        profile.serverVersion = string(map, "serverVersion", "");
        profile.authenticationType = string(map, "authenticationType", "unknown");

        Object tagValue = map.get("tags");
        if (tagValue instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String tag && !tag.isBlank()) {
                    profile.tags.add(tag);
                }
            }
        }

        Object noteValue = map.get("notes");
        if (noteValue instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> rawMap) {
                    try {
                        profile.notes.add(ServerNote.fromMap((Map<String, Object>) rawMap));
                    } catch (RuntimeException ignored) {
                    }
                }
            }
        }

        Object locationValue = map.get("locations");
        if (locationValue instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> rawMap) {
                    try {
                        profile.locations.add(ServerLocation.fromMap((Map<String, Object>) rawMap));
                    } catch (RuntimeException ignored) {
                    }
                }
            }
        }


        Object playerValue = map.get("players");
        if (playerValue instanceof Map<?, ?> rawPlayers) {
            for (Map.Entry<?, ?> entry : rawPlayers.entrySet()) {
                if (!(entry.getKey() instanceof String playerKey) || !(entry.getValue() instanceof Map<?, ?> rawPlayer)) {
                    continue;
                }
                try {
                    ServerPlayerRecord record = ServerPlayerRecord.fromMap(playerKey, (Map<String, Object>) rawPlayer);
                    profile.players.put(record.key(), record);
                } catch (RuntimeException ignored) {
                }
            }
        }

        Object metadataValue = map.get("metadata");
        if (metadataValue instanceof Map<?, ?> rawMap) {
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                profile.metadata.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return profile;
    }

    private static String string(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value instanceof String string ? string : fallback;
    }

    private static int integer(Map<String, Object> map, String key, int fallback) {
        Object value = map.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static boolean bool(Map<String, Object> map, String key, boolean fallback) {
        Object value = map.get(key);
        return value instanceof Boolean bool ? bool : fallback;
    }
}
