package dev.zazu.servernotes.model;

import dev.zazu.servernotes.util.TimeUtil;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ServerPlayerRecord {
    private String key;
    private String uuid;
    private String username;
    private String firstSeenAt;
    private String lastSeenAt;
    private int encounters;
    private String notes;
    private int pingSightings;
    private String lastPingSeenAt = "";

    public ServerPlayerRecord(String key, String uuid, String username) {
        String now = TimeUtil.now();
        this.key = key;
        this.uuid = uuid == null ? "" : uuid;
        this.username = username == null ? "Unknown" : username;
        this.firstSeenAt = now;
        this.lastSeenAt = now;
        this.encounters = 1;
        this.notes = "";
    }

    public String key() { return key; }
    public String uuid() { return uuid; }
    public String username() { return username; }
    public String firstSeenAt() { return firstSeenAt; }
    public String lastSeenAt() { return lastSeenAt; }
    public int encounters() { return encounters; }
    public String notes() { return notes; }
    public int pingSightings() { return pingSightings; }
    public String lastPingSeenAt() { return lastPingSeenAt; }
    public void rekey(String key) { this.key = key; }

    public static ServerPlayerRecord fromPing(String key, String uuid, String username, String observedAt) {
        ServerPlayerRecord record = new ServerPlayerRecord(key, uuid, username);
        record.encounters = 0;
        record.firstSeenAt = observedAt;
        record.lastSeenAt = observedAt;
        record.lastPingSeenAt = observedAt;
        record.pingSightings = 1;
        return record;
    }

    public boolean observePing(String uuid, String username, String observedAt) {
        java.time.Instant seen = java.time.Instant.parse(observedAt);
        boolean changed = refreshIdentity(uuid, username);
        if (!lastPingSeenAt.isBlank()) {
            java.time.Instant previous = java.time.Instant.parse(lastPingSeenAt);
            if (seen.isBefore(previous.plusSeconds(60))) return changed;
        }
        lastPingSeenAt = observedAt;
        boolean newer = lastSeenAt.isBlank();
        if (!newer) {
            try { newer = seen.isAfter(java.time.Instant.parse(lastSeenAt)); }
            catch (RuntimeException legacyTimestamp) { newer = true; }
        }
        if (newer) lastSeenAt = observedAt;
        if (pingSightings < Integer.MAX_VALUE) pingSightings++;
        return true;
    }

    public boolean beginEncounter(String uuid, String username) {
        refreshIdentity(uuid, username);
        this.lastSeenAt = TimeUtil.now();
        this.encounters = Math.max(0, this.encounters) + 1;
        return true;
    }

    public boolean refreshIdentity(String uuid, String username) {
        boolean changed = false;
        if (uuid != null && !uuid.isBlank() && !uuid.equals(this.uuid)) {
            this.uuid = uuid;
            changed = true;
        }
        if (username != null && !username.isBlank() && !username.equals(this.username)) {
            this.username = username;
            changed = true;
        }
        return changed;
    }

    public void markLastSeenNow() {
        this.lastSeenAt = TimeUtil.now();
    }

    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("key", key);
        map.put("uuid", uuid);
        map.put("username", username);
        map.put("firstSeenAt", firstSeenAt);
        map.put("lastSeenAt", lastSeenAt);
        map.put("encounters", encounters);
        map.put("notes", notes);
        map.put("pingSightings", pingSightings);
        map.put("lastPingSeenAt", lastPingSeenAt);
        return map;
    }

    public static ServerPlayerRecord fromMap(String fallbackKey, Map<String, Object> map) {
        String key = string(map, "key", fallbackKey);
        ServerPlayerRecord record = new ServerPlayerRecord(
            key,
            string(map, "uuid", ""),
            string(map, "username", "Unknown")
        );
        record.key = key;
        record.firstSeenAt = string(map, "firstSeenAt", record.firstSeenAt);
        record.lastSeenAt = string(map, "lastSeenAt", record.lastSeenAt);
        record.encounters = Math.max(0, integer(map, "encounters", 1));
        record.pingSightings = Math.max(0, integer(map, "pingSightings", 0));
        record.lastPingSeenAt = string(map, "lastPingSeenAt", "");
        if (!record.lastPingSeenAt.isBlank()) {
            try { java.time.Instant.parse(record.lastPingSeenAt); }
            catch (RuntimeException invalid) { record.lastPingSeenAt = ""; }
        }
        record.notes = string(map, "notes", "");
        return record;
    }

    private static String string(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value instanceof String string ? string : fallback;
    }

    private static int integer(Map<String, Object> map, String key, int fallback) {
        Object value = map.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }
}
