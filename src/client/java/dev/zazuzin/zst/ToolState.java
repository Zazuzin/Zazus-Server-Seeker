package dev.zazuzin.zst;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;

/** Persistent settings and Finder history. */
final class ToolState {
    static boolean skipAddedHistory = true;
    static boolean blockDeleted = true;
    static boolean favouritesFirst = true;
    static boolean autoAddDefault = false;
    static boolean quickSearch = false;
    static int autoAddLimit = 25;
    static int versionIndex = 1;
    static int minIndex = 1;
    static int maxIndex = 7;
    static int sortIndex = 0;
    static int serverTypeIndex = 0;
    static int finderSourceIndex = 0;
    static int breakBlocksMaxAgeDays = 7;
    static long addedCount = 0;
    static long deletedCount = 0;
    private static String breakBlocksApiKey = "";

    private static final LinkedHashSet<String> ADDED_HISTORY = new LinkedHashSet<>();
    private static final LinkedHashSet<String> BLOCKED = new LinkedHashSet<>();
    private static final LinkedHashMap<String, String> ADDED_VERSIONS = new LinkedHashMap<>();
    private static final LinkedHashMap<String, String> ADDED_PROTOCOLS = new LinkedHashMap<>();
    private static final Path CONFIG_DIR = resolveConfigDir();
    private static final Path FILE = CONFIG_DIR.resolve("zazus-server-tool.properties");

    private ToolState() {}

    static { load(); }

    static synchronized void load() {
        Properties p = new Properties();
        boolean configExisted = false;
        try {
            Files.createDirectories(CONFIG_DIR);
            configExisted = Files.exists(FILE);
            if (configExisted) {
                try (BufferedReader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
                    p.load(reader);
                }
            }
        } catch (Exception ex) {
            System.err.println("[Zazu's Server Seeker] Could not load settings: " + ex);
        }

        skipAddedHistory = bool(p, "skipAddedHistory", true);
        blockDeleted = bool(p, "blockDeleted", true);
        favouritesFirst = bool(p, "favouritesFirst", true);
        autoAddDefault = bool(p, "autoAddDefault", false);
        quickSearch = bool(p, "quickSearch", false);
        autoAddLimit = integer(p, "autoAddLimit", 25);
        versionIndex = integer(p, "versionIndex", 1);
        minIndex = integer(p, "minIndex", 1);
        maxIndex = integer(p, "maxIndex", 7);
        sortIndex = integer(p, "sortIndex", 0);
        serverTypeIndex = integer(p, "serverTypeIndex", 0);
        finderSourceIndex = integer(p, "finderSourceIndex", 0);
        breakBlocksMaxAgeDays = normalizeBreakBlocksAge(integer(p, "breakBlocksMaxAgeDays", 7));
        addedCount = longValue(p, "addedCount", 0);
        deletedCount = longValue(p, "deletedCount", 0);
        breakBlocksApiKey = p.getProperty("breakBlocksApiKey", "").trim();

        ADDED_HISTORY.clear();
        BLOCKED.clear();
        ADDED_VERSIONS.clear();
        ADDED_PROTOCOLS.clear();
        decodeSet(p.getProperty("addedHistory", ""), ADDED_HISTORY);
        decodeSet(p.getProperty("blocked", ""), BLOCKED);
        decodeMap(p.getProperty("addedVersions", ""), ADDED_VERSIONS);
        decodeMap(p.getProperty("addedProtocols", ""), ADDED_PROTOCOLS);

        // Create/migrate the config so users always have an obvious blank API-key field to fill in.
        if (!configExisted || !p.containsKey("breakBlocksApiKey")
                || !p.containsKey("breakBlocksMaxAgeDays") || !p.containsKey("quickSearch")) save();
        else restrictConfigPermissions();
    }

    static synchronized void save() {
        // The API key is intentionally editable outside the game. Preserve the latest
        // on-disk value when unrelated settings/history are saved.
        String diskApiKey = readApiKeyFromDisk();
        if (diskApiKey != null) breakBlocksApiKey = diskApiKey;

        Properties p = new Properties();
        p.setProperty("skipAddedHistory", String.valueOf(skipAddedHistory));
        p.setProperty("blockDeleted", String.valueOf(blockDeleted));
        p.setProperty("favouritesFirst", String.valueOf(favouritesFirst));
        p.setProperty("autoAddDefault", String.valueOf(autoAddDefault));
        p.setProperty("quickSearch", String.valueOf(quickSearch));
        p.setProperty("autoAddLimit", String.valueOf(autoAddLimit));
        p.setProperty("versionIndex", String.valueOf(versionIndex));
        p.setProperty("minIndex", String.valueOf(minIndex));
        p.setProperty("maxIndex", String.valueOf(maxIndex));
        p.setProperty("sortIndex", String.valueOf(sortIndex));
        p.setProperty("serverTypeIndex", String.valueOf(serverTypeIndex));
        p.setProperty("finderSourceIndex", String.valueOf(finderSourceIndex));
        breakBlocksMaxAgeDays = normalizeBreakBlocksAge(breakBlocksMaxAgeDays);
        p.setProperty("breakBlocksMaxAgeDays", String.valueOf(breakBlocksMaxAgeDays));
        p.setProperty("addedCount", String.valueOf(addedCount));
        p.setProperty("deletedCount", String.valueOf(deletedCount));
        p.setProperty("breakBlocksApiKey", breakBlocksApiKey == null ? "" : breakBlocksApiKey.trim());
        p.setProperty("addedHistory", encodeSet(ADDED_HISTORY));
        p.setProperty("blocked", encodeSet(BLOCKED));
        p.setProperty("addedVersions", encodeMap(ADDED_VERSIONS));
        p.setProperty("addedProtocols", encodeMap(ADDED_PROTOCOLS));
        try {
            Files.createDirectories(CONFIG_DIR);
            try (BufferedWriter writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
                p.store(writer, "Zazu's Server Seeker");
            }
            restrictConfigPermissions();
        } catch (Exception ex) {
            System.err.println("[Zazu's Server Seeker] Could not save settings: " + ex);
        }
    }

    static synchronized boolean wasAdded(String endpoint) { return ADDED_HISTORY.contains(normalize(endpoint)); }
    static synchronized int addedHistoryCount() { return ADDED_HISTORY.size(); }
    static synchronized void clearAddedHistory() {
        ADDED_HISTORY.clear();
        ADDED_VERSIONS.clear();
        ADDED_PROTOCOLS.clear();
        save();
    }

    static synchronized boolean isBlocked(String endpoint) { return BLOCKED.contains(normalize(endpoint)); }
    static synchronized void block(String endpoint) {
        String e = normalize(endpoint);
        if (!e.isBlank() && BLOCKED.add(e)) save();
    }
    static synchronized void unblock(String endpoint) { if (BLOCKED.remove(normalize(endpoint))) save(); }
    static synchronized void clearBlocked() { BLOCKED.clear(); save(); }
    static synchronized List<String> blockedSnapshot() {
        ArrayList<String> out = new ArrayList<>(BLOCKED);
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }
    static synchronized int blockedCount() { return BLOCKED.size(); }

    static synchronized void recordAdded(String endpoint) { recordAdded(endpoint, "", -1); }
    static synchronized void recordAdded(String endpoint, String version) { recordAdded(endpoint, version, -1); }
    static synchronized void recordAdded(String endpoint, String version, int protocol) {
        addedCount++;
        String e = normalize(endpoint);
        if (!e.isBlank()) {
            ADDED_HISTORY.add(e);
            if (version != null && !version.isBlank()) ADDED_VERSIONS.put(e, version.trim());
            if (protocol > 0) ADDED_PROTOCOLS.put(e, String.valueOf(protocol));
        }
        save();
    }

    static synchronized String versionFor(String endpoint) {
        return ADDED_VERSIONS.getOrDefault(normalize(endpoint), "");
    }

    static synchronized int protocolFor(String endpoint) {
        String v = ADDED_PROTOCOLS.get(normalize(endpoint));
        if (v == null || v.isBlank()) return -1;
        try { return Integer.parseInt(v); } catch (NumberFormatException ignored) { return -1; }
    }

    static synchronized void recordDeleted(String endpoint) {
        deletedCount++;
        String e = normalize(endpoint);
        if (blockDeleted && !e.isBlank()) BLOCKED.add(e);
        save();
    }

    static synchronized void resetStats() { addedCount = 0; deletedCount = 0; save(); }


    static Path configDir() { return CONFIG_DIR; }

    static synchronized String breakBlocksApiKey() {
        return breakBlocksApiKey == null ? "" : breakBlocksApiKey.trim();
    }

    static synchronized boolean hasBreakBlocksApiKey() {
        return !breakBlocksApiKey().isBlank();
    }

    static synchronized void reloadBreakBlocksApiKey() {
        String diskApiKey = readApiKeyFromDisk();
        if (diskApiKey != null) breakBlocksApiKey = diskApiKey;
    }

    static String normalize(String endpoint) {
        if (endpoint == null) return "";
        return endpoint.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean bool(Properties p, String key, boolean fallback) {
        String v = p.getProperty(key);
        return v == null ? fallback : Boolean.parseBoolean(v.trim());
    }

    private static int integer(Properties p, String key, int fallback) {
        try { return Integer.parseInt(p.getProperty(key, String.valueOf(fallback)).trim()); }
        catch (Exception ignored) { return fallback; }
    }

    private static long longValue(Properties p, String key, long fallback) {
        try { return Long.parseLong(p.getProperty(key, String.valueOf(fallback)).trim()); }
        catch (Exception ignored) { return fallback; }
    }

    private static int normalizeBreakBlocksAge(int days) {
        return switch (days) {
            case 1, 7, 14, 21, 30 -> days;
            default -> 30;
        };
    }

    private static String encodeSet(Collection<String> values) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        ArrayList<String> out = new ArrayList<>();
        for (String value : values) {
            out.add(encoder.encodeToString(value.getBytes(StandardCharsets.UTF_8)));
        }
        return String.join(",", out);
    }

    private static void decodeSet(String encoded, Set<String> output) {
        if (encoded == null || encoded.isBlank()) return;
        Base64.Decoder decoder = Base64.getUrlDecoder();
        for (String part : encoded.split(",")) {
            if (part.isBlank()) continue;
            try { output.add(new String(decoder.decode(part), StandardCharsets.UTF_8)); }
            catch (Exception ignored) {}
        }
    }

    private static String encodeMap(Map<String, String> values) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        ArrayList<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : values.entrySet()) {
            String k = encoder.encodeToString(e.getKey().getBytes(StandardCharsets.UTF_8));
            String v = encoder.encodeToString(e.getValue().getBytes(StandardCharsets.UTF_8));
            out.add(k + "." + v);
        }
        return String.join(",", out);
    }

    private static void decodeMap(String encoded, Map<String, String> output) {
        if (encoded == null || encoded.isBlank()) return;
        Base64.Decoder decoder = Base64.getUrlDecoder();
        for (String part : encoded.split(",")) {
            int dot = part.indexOf('.');
            if (dot <= 0 || dot >= part.length() - 1) continue;
            try {
                String k = new String(decoder.decode(part.substring(0, dot)), StandardCharsets.UTF_8);
                String v = new String(decoder.decode(part.substring(dot + 1)), StandardCharsets.UTF_8);
                output.put(k, v);
            } catch (Exception ignored) {}
        }
    }

    private static String readApiKeyFromDisk() {
        if (!Files.exists(FILE)) return null;
        Properties p = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            p.load(reader);
            return p.getProperty("breakBlocksApiKey", "").trim();
        } catch (Exception ex) {
            System.err.println("[Zazu's Server Seeker] Could not reload BreakBlocks API-key setting: " + ex.getClass().getSimpleName());
            return null;
        }
    }

    private static void restrictConfigPermissions() {
        try {
            Files.setPosixFilePermissions(FILE, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX platform (for example Windows).
        } catch (Exception ex) {
            System.err.println("[Zazu's Server Seeker] Could not tighten config-file permissions: " + ex.getClass().getSimpleName());
        }
    }

    private static Path resolveConfigDir() {
        try {
            Class<?> loader = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object instance = loader.getMethod("getInstance").invoke(null);
            Object configDir = loader.getMethod("getConfigDir").invoke(instance);
            if (configDir instanceof Path p) return p;
        } catch (Throwable ignored) {}
        return Path.of(System.getProperty("user.dir", "."), "config");
    }
}
