package dev.zazuzin.zst;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Persistent classification for the Multiplayer categories. */
final class ServerCategoryStore {
    enum Tab { FAVOURITES, SERVERS, SCANNED }

    private static final LinkedHashSet<String> KNOWN = new LinkedHashSet<>();
    private static final LinkedHashSet<String> SCANNED = new LinkedHashSet<>();
    private static Path file;
    private static boolean loaded;
    private static boolean migrated;

    private ServerCategoryStore() {}

    static synchronized void load() {
        if (loaded) return;
        loaded = true;
        Properties p = new Properties();
        file = resolveConfigDir().resolve("zazus-server-tabs.properties");
        try {
            Files.createDirectories(file.getParent());
            if (Files.exists(file)) {
                try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { p.load(r); }
            }
        } catch (Throwable t) {
            System.err.println("[Zazu's Server Tool] Could not load server-tab state: " + t);
        }
        migrated = Boolean.parseBoolean(p.getProperty("migrated", "false"));
        decode(p.getProperty("known", ""), KNOWN);
        decode(p.getProperty("scanned", ""), SCANNED);
    }

    static synchronized void migrateExisting(Collection<String> endpoints) {
        load();
        if (migrated) return;
        for (String endpoint : endpoints) {
            String e = normalize(endpoint);
            if (!e.isBlank()) KNOWN.add(e);
        }
        // Existing 0.3.22 entries are treated as already-established Servers.
        migrated = true;
        save();
        System.out.println("[Zazu's Server Tool] Multiplayer tabs migration complete; existing servers kept in Servers/Favourites.");
    }

    /**
     * Classifies newly appearing saved servers that were not added through the
     * Finder. Manual/vanilla additions default to established Servers. Finder
     * additions call markScanned at the exact point they are saved.
     */
    static synchronized boolean syncNew(Collection<String> endpoints) {
        load();
        boolean changed = false;
        for (String endpoint : endpoints) {
            String e = normalize(endpoint);
            if (e.isBlank() || KNOWN.contains(e)) continue;
            KNOWN.add(e);
            changed = true;
        }
        if (changed) save();
        return changed;
    }

    static synchronized void markScanned(String endpoint) {
        load();
        String e = normalize(endpoint);
        if (e.isBlank()) return;
        boolean changed = KNOWN.add(e);
        changed |= SCANNED.add(e);
        if (changed) save();
    }

    static synchronized boolean isScanned(String endpoint) {
        load();
        return SCANNED.contains(normalize(endpoint));
    }

    static synchronized boolean promoteVerified(String endpoint) {
        load();
        String e = normalize(endpoint);
        if (e.isBlank()) return false;
        KNOWN.add(e);
        boolean changed = SCANNED.remove(e);
        if (changed) {
            save();
            System.out.println("[Zazu's Server Tool] Verified server promoted from Scanned Servers to Servers: " + endpoint);
        }
        return changed;
    }

    static synchronized void remove(String endpoint) {
        load();
        String e = normalize(endpoint);
        if (!e.isBlank() && SCANNED.remove(e)) save();
        // KNOWN intentionally remains: re-adding the same endpoint manually later
        // should not automatically classify it as a fresh scan.
    }


    private static void save() {
        if (file == null) file = resolveConfigDir().resolve("zazus-server-tabs.properties");
        Properties p = new Properties();
        p.setProperty("migrated", String.valueOf(migrated));
        p.setProperty("known", encode(KNOWN));
        p.setProperty("scanned", encode(SCANNED));
        try {
            Files.createDirectories(file.getParent());
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                p.store(w, "Zazu's Server Tool multiplayer tabs");
            }
        } catch (Throwable t) {
            System.err.println("[Zazu's Server Tool] Could not save server-tab state: " + t);
        }
    }

    private static Path resolveConfigDir() {
        Path path = ToolState.configDir();
        if (path != null) return path;
        try {
            Class<?> loader = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object instance = loader.getMethod("getInstance").invoke(null);
            Object dir = loader.getMethod("getConfigDir").invoke(instance);
            if (dir instanceof Path p) return p;
        } catch (Throwable ignored) {}
        return Path.of(System.getProperty("user.dir", "."), "config");
    }

    private static String normalize(String endpoint) {
        if (endpoint == null) return "";
        return endpoint.trim().toLowerCase(Locale.ROOT);
    }

    private static String encode(Collection<String> values) {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            if (!out.isEmpty()) out.append(',');
            out.append(enc.encodeToString(value.getBytes(StandardCharsets.UTF_8)));
        }
        return out.toString();
    }

    private static void decode(String value, Set<String> output) {
        if (value == null || value.isBlank()) return;
        Base64.Decoder dec = Base64.getUrlDecoder();
        for (String part : value.split(",")) {
            try {
                String e = new String(dec.decode(part), StandardCharsets.UTF_8);
                e = normalize(e);
                if (!e.isBlank()) output.add(e);
            } catch (Throwable ignored) {}
        }
    }
}
