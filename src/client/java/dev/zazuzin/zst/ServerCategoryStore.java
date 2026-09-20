package dev.zazuzin.zst;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Persistent classification for the Multiplayer categories. */
final class ServerCategoryStore {
    enum Tab { FAVOURITES, SERVERS, SCANNED, RECENT }

    private static final LinkedHashSet<String> KNOWN = new LinkedHashSet<>();
    private static final LinkedHashSet<String> SCANNED = new LinkedHashSet<>();
    private static final LinkedHashSet<String> FAVOURITES = new LinkedHashSet<>();
    private static final Map<String, Health> HEALTH = new LinkedHashMap<>();
    private static List<Undo> undo = List.of();
    private static final LinkedList<String> RECENT = new LinkedList<>();
    private static final LinkedList<RecentlyRemoved> RECENTLY_REMOVED = new LinkedList<>();
    private static final int MAX_RECENT = 5;
    private static final int MAX_RECENTLY_REMOVED = 50;
    private static final int CLASSIFICATION_REVISION = 1;
    private static final int CLEANUP_HEALTH_REVISION = 1;
    private static final int BULK_IMPORT_RECOVERY_MINIMUM = 25;
    private static Path file;
    private static boolean loaded;
    private static boolean migrated;
    private static int classificationRevision;
    private static int cleanupHealthRevision;

    private ServerCategoryStore() {}

    static synchronized void load() {
        if (loaded) return;
        loaded = true;
        Properties p = new Properties();
        file = resolveConfigDir().resolve("server-tabs.properties");
        try {
            Files.createDirectories(file.getParent());
            if (Files.exists(file)) {
                try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { p.load(r); }
            }
        } catch (Throwable t) {
            System.err.println("[Zazu's Server Seeker] Could not load server-tab state: " + t);
        }
        migrated = Boolean.parseBoolean(p.getProperty("migrated", "false"));
        classificationRevision = integer(p.getProperty("classificationRevision", "0"), 0);
        cleanupHealthRevision = integer(p.getProperty("cleanupHealthRevision", "0"), 0);
        decode(p.getProperty("known", ""), KNOWN);
        decode(p.getProperty("scanned", ""), SCANNED);
        decode(p.getProperty("favourites", ""), FAVOURITES);
        decodeHealth(p);
        boolean resetLegacyHealth = cleanupHealthRevision < CLEANUP_HEALTH_REVISION;
        if (resetLegacyHealth) {
            // Earlier builds counted timeouts in the same failure streak. Do
            // not let those unclassified failures contribute to safer cleanup.
            HEALTH.clear();
            cleanupHealthRevision = CLEANUP_HEALTH_REVISION;
        }
        int undoCount = integer(p.getProperty("undo.count", "0"), 0);
        ArrayList<Undo> loadedUndo = new ArrayList<>();
        for (int i = 0; i < undoCount; i++) {
            String prefix = "undo." + i + ".";
            String endpoint = decodeOne(p.getProperty(prefix + "endpoint", ""));
            if (!endpoint.isBlank()) loadedUndo.add(new Undo(decodeOne(p.getProperty(prefix + "name", "")), endpoint,
                    Boolean.parseBoolean(p.getProperty(prefix + "favourite", "false")),
                    Boolean.parseBoolean(p.getProperty(prefix + "scanned", "false"))));
        }
        if (loadedUndo.isEmpty()) {
            String legacyEndpoint = decodeOne(p.getProperty("undo.endpoint", ""));
            if (!legacyEndpoint.isBlank()) loadedUndo.add(new Undo(decodeOne(p.getProperty("undo.name", "")), legacyEndpoint,
                    Boolean.parseBoolean(p.getProperty("undo.favourite", "false")),
                    Boolean.parseBoolean(p.getProperty("undo.scanned", "false"))));
        }
        undo = List.copyOf(loadedUndo);
        decodeList(p.getProperty("recent", ""), RECENT);
        while (RECENT.size() > MAX_RECENT) RECENT.removeLast();
        int removedCount = Math.min(MAX_RECENTLY_REMOVED,
                integer(p.getProperty("removed.count", "0"), 0));
        for (int i = 0; i < removedCount; i++) {
            String prefix = "removed." + i + ".";
            String endpoint = decodeOne(p.getProperty(prefix + "endpoint", ""));
            if (endpoint.isBlank()) continue;
            RECENTLY_REMOVED.add(new RecentlyRemoved(
                    decodeOne(p.getProperty(prefix + "name", "")), endpoint,
                    decodeOne(p.getProperty(prefix + "reason", "")),
                    decodeOne(p.getProperty(prefix + "detail", "")),
                    longValue(p.getProperty(prefix + "at", "0"), 0L),
                    Boolean.parseBoolean(p.getProperty(prefix + "scanned", "true"))));
        }
        if (resetLegacyHealth) save();
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

    /**
     * Reconciles servers.dat with the portable information available in each
     * saved row. Category metadata normally lives in this config file, but a
     * user may copy only servers.dat to another instance. Finder-created rows
     * retain the exact "endpoint [version]" name, allowing their Scanned
     * classification to be recovered without moving ordinary named servers.
     */
    static synchronized void reconcileSaved(Collection<ServerListAccess.Saved> saved, boolean initialMigration) {
        load();
        if (saved == null) saved = List.of();
        boolean firstMigration = initialMigration && !migrated;
        boolean changed = false;
        ArrayList<String> inferred = new ArrayList<>();

        for (ServerListAccess.Saved server : saved) {
            if (server == null) continue;
            String endpoint = normalize(server.endpoint());
            if (endpoint.isBlank()) continue;
            boolean wasKnown = KNOWN.contains(endpoint);
            changed |= KNOWN.add(endpoint);
            if (server.favourite() || FAVOURITES.contains(endpoint) || RECENT.contains(endpoint)) continue;
            if (ToolState.wasAdded(endpoint) || looksLikeFinderSavedName(server.name(), endpoint)) {
                if (firstMigration || !wasKnown) inferred.add(endpoint);
            }
        }

        // The previous 1.0.0 build may already have marked a servers.dat-only
        // import as known. Run
        // one conservative repair for a clearly bulk-sized Finder import.
        if (classificationRevision < CLASSIFICATION_REVISION) {
            ArrayList<String> bulkCandidates = new ArrayList<>();
            for (ServerListAccess.Saved server : saved) {
                if (server == null || server.favourite()) continue;
                String endpoint = normalize(server.endpoint());
                if (endpoint.isBlank() || SCANNED.contains(endpoint) || FAVOURITES.contains(endpoint)
                        || RECENT.contains(endpoint)) continue;
                if (ToolState.wasAdded(endpoint) || looksLikeFinderSavedName(server.name(), endpoint)) {
                    bulkCandidates.add(endpoint);
                }
            }
            if (bulkCandidates.size() >= BULK_IMPORT_RECOVERY_MINIMUM) {
                inferred.addAll(bulkCandidates);
                System.out.println("[Zazu's Server Seeker] Recovered " + bulkCandidates.size()
                        + " imported Finder server(s) into Scanned Servers.");
            }
            classificationRevision = CLASSIFICATION_REVISION;
            changed = true;
        }

        for (String endpoint : inferred) changed |= SCANNED.add(endpoint);
        if (firstMigration) {
            migrated = true;
            changed = true;
        }
        if (changed) save();
    }

    static boolean looksLikeFinderSavedName(String name, String endpoint) {
        String normalizedEndpoint = normalize(endpoint);
        if (name == null || normalizedEndpoint.isBlank()) return false;
        String normalizedName = name.trim().toLowerCase(Locale.ROOT);
        if (normalizedName.startsWith("★ ")) normalizedName = normalizedName.substring(2).trim();
        return normalizedName.startsWith(normalizedEndpoint + " [") && normalizedName.endsWith("]");
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

    static synchronized boolean isFavourite(String endpoint) {
        load();
        return FAVOURITES.contains(normalize(endpoint));
    }

    static synchronized void setFavourite(String endpoint, boolean favourite) {
        load();
        String e = normalize(endpoint);
        if (e.isBlank()) return;
        boolean changed = favourite ? FAVOURITES.add(e) : FAVOURITES.remove(e);
        if (changed) save();
    }

    static synchronized int healthFailures(String endpoint) {
        load();
        return HEALTH.getOrDefault(normalize(endpoint), Health.EMPTY).failures;
    }

    static synchronized void recordHealthSuccess(String endpoint) {
        load();
        String e = normalize(endpoint);
        if (e.isBlank()) return;
        HEALTH.put(e, new Health(0, System.currentTimeMillis(), HEALTH.getOrDefault(e, Health.EMPTY).lastFailure));
        save();
    }

    static synchronized int recordHealthFailure(String endpoint) {
        load();
        String e = normalize(endpoint);
        if (e.isBlank()) return 0;
        Health current = HEALTH.getOrDefault(e, Health.EMPTY);
        Health updated = new Health(current.failures + 1, current.lastSuccess, System.currentTimeMillis());
        HEALTH.put(e, updated);
        save();
        return updated.failures;
    }

    static synchronized void recordUndo(String name, String endpoint) {
        load();
        String e = normalize(endpoint);
        if (e.isBlank()) return;
        backupServersFile();
        undo = List.of(new Undo(name == null ? "" : name, endpoint, isFavourite(endpoint), isScanned(endpoint)));
        save();
    }

    static synchronized void recordUndoBatch(Collection<DeletedServer> deleted) {
        load();
        ArrayList<Undo> batch = new ArrayList<>();
        if (deleted != null) {
            for (DeletedServer server : deleted) {
                if (server == null) continue;
                String endpoint = normalize(server.endpoint);
                if (endpoint.isBlank()) continue;
                batch.add(new Undo(server.name == null ? "" : server.name, endpoint,
                        server.favourite, server.scanned));
            }
        }
        if (batch.isEmpty()) return;
        backupServersFile();
        undo = List.copyOf(batch);
        save();
    }

    static synchronized boolean hasUndo() {
        load();
        return !undo.isEmpty();
    }

    static synchronized void recordAutomaticRemoval(String name, String endpoint, String reason, String detail) {
        load();
        String e = normalize(endpoint);
        if (e.isBlank()) return;
        RECENTLY_REMOVED.removeIf(entry -> entry.endpoint.equals(e));
        RECENTLY_REMOVED.addFirst(new RecentlyRemoved(
                name == null ? "" : name, e,
                reason == null ? "Automatic cleanup" : reason,
                detail == null ? "" : detail,
                System.currentTimeMillis(), SCANNED.contains(e)));
        while (RECENTLY_REMOVED.size() > MAX_RECENTLY_REMOVED) RECENTLY_REMOVED.removeLast();
        save();
    }

    static synchronized List<RecentlyRemoved> recentlyRemovedSnapshot() {
        load();
        return List.copyOf(RECENTLY_REMOVED);
    }

    static synchronized void backupBeforeAutomaticRemoval() {
        load();
        backupServersFile();
    }

    static synchronized boolean restoreRecentlyRemoved(Object client, String endpoint) {
        load();
        String e = normalize(endpoint);
        RecentlyRemoved removed = null;
        for (RecentlyRemoved candidate : RECENTLY_REMOVED) {
            if (candidate.endpoint.equals(e)) { removed = candidate; break; }
        }
        if (removed == null) return false;
        try {
            Object list = ServerFinderClient.ServerListBridge.createLoadedList(client);
            if (ServerFinderClient.ServerListBridge.findServer(list, e) == null) {
                String name = removed.name.isBlank() ? "Restored " + e : removed.name;
                Object data = ServerFinderClient.ServerListBridge.createServerData(name, e);
                ServerFinderClient.ServerListBridge.addServerData(list, data);
                ServerFinderClient.ServerListBridge.save(list);
            }
            KNOWN.add(e);
            if (removed.scanned) SCANNED.add(e);
            HEALTH.remove(e);
            RECENTLY_REMOVED.remove(removed);
            ToolState.unblock(e);
            save();
            System.out.println("[Zazu's Server Seeker] Restored automatically removed server: " + e);
            return true;
        } catch (Throwable t) {
            System.err.println("[Zazu's Server Seeker] Could not restore automatically removed server " + e + ": " + t);
            return false;
        }
    }

    static synchronized void clearRecentlyRemoved() {
        load();
        if (RECENTLY_REMOVED.isEmpty()) return;
        RECENTLY_REMOVED.clear();
        save();
    }

    static synchronized boolean undoLastDelete(Object client) {
        load();
        if (undo.isEmpty()) return false;
        try {
            Object list = ServerFinderClient.ServerListBridge.createLoadedList(client);
            int restored = 0;
            for (Undo entry : undo) {
                if (ServerFinderClient.ServerListBridge.findServer(list, entry.endpoint) == null) {
                    String name = entry.name.isBlank() ? "Restored " + entry.endpoint : entry.name;
                    Object data = ServerFinderClient.ServerListBridge.createServerData(name, entry.endpoint);
                    ServerFinderClient.ServerListBridge.addServerData(list, data);
                    restored++;
                }
                KNOWN.add(entry.endpoint);
                if (entry.favourite) FAVOURITES.add(entry.endpoint); else FAVOURITES.remove(entry.endpoint);
                if (entry.scanned) SCANNED.add(entry.endpoint); else SCANNED.remove(entry.endpoint);
                RECENTLY_REMOVED.removeIf(removed -> removed.endpoint.equals(entry.endpoint));
                HEALTH.remove(entry.endpoint);
                ToolState.unblock(entry.endpoint);
            }
            ServerFinderClient.ServerListBridge.save(list);
            int batchSize = undo.size();
            undo = List.of();
            save();
            System.out.println("[Zazu's Server Seeker] Restored " + restored + "/" + batchSize
                    + " server(s) from the last delete action.");
            return true;
        } catch (Throwable t) {
            System.err.println("[Zazu's Server Seeker] Undo failed: " + t);
            return false;
        }
    }

    static synchronized void migrateFavourite(String endpoint, boolean starredName) {
        if (starredName) setFavourite(endpoint, true);
    }

    static synchronized boolean promoteVerified(String endpoint) {
        load();
        String e = normalize(endpoint);
        if (e.isBlank()) return false;
        KNOWN.add(e);
        boolean changed = SCANNED.remove(e);
        if (changed) {
            save();
            System.out.println("[Zazu's Server Seeker] Verified server promoted from Scanned Servers to Servers: " + endpoint);
        }
        return changed;
    }


    static synchronized void recordSuccessfulJoin(String endpoint) {
        load();
        String e = normalize(endpoint);
        if (e.isBlank()) return;
        boolean changed = RECENT.remove(e);
        if (RECENT.isEmpty() || !RECENT.getFirst().equals(e)) {
            RECENT.addFirst(e);
            changed = true;
        }
        while (RECENT.size() > MAX_RECENT) {
            RECENT.removeLast();
            changed = true;
        }
        if (changed) {
            save();
            System.out.println("[Zazu's Server Seeker] Added to Recent Servers: " + endpoint);
        }
    }

    static synchronized boolean isRecent(String endpoint) {
        load();
        return RECENT.contains(normalize(endpoint));
    }

    /** Moves all persisted category metadata when a saved server address changes. */
    static synchronized void moveEndpoint(String oldEndpoint, String newEndpoint) {
        load();
        String oldValue = normalize(oldEndpoint);
        String newValue = normalize(newEndpoint);
        if (oldValue.isBlank() || newValue.isBlank() || oldValue.equals(newValue)) return;

        boolean known = KNOWN.remove(oldValue);
        boolean scanned = SCANNED.remove(oldValue);
        boolean favourite = FAVOURITES.remove(oldValue);
        Health health = HEALTH.remove(oldValue);
        boolean recent = RECENT.remove(oldValue);

        if (known) KNOWN.add(newValue);
        if (scanned) SCANNED.add(newValue);
        if (favourite) FAVOURITES.add(newValue);
        if (health != null) HEALTH.put(newValue, health);
        if (recent) RECENT.addFirst(newValue);
        while (RECENT.size() > MAX_RECENT) RECENT.removeLast();
        save();
    }

    static synchronized List<String> recentEndpoints() {
        load();
        return List.copyOf(RECENT);
    }

    static synchronized void remove(String endpoint) {
        load();
        String e = normalize(endpoint);
        if (e.isBlank()) return;
        boolean changed = SCANNED.remove(e);
        changed |= RECENT.remove(e);
        if (changed) save();
        // KNOWN intentionally remains: re-adding the same endpoint manually later
        // should not automatically classify it as a fresh scan.
    }


    private static void save() {
        if (file == null) file = resolveConfigDir().resolve("server-tabs.properties");
        Properties p = new Properties();
        p.setProperty("migrated", String.valueOf(migrated));
        p.setProperty("classificationRevision", String.valueOf(classificationRevision));
        p.setProperty("cleanupHealthRevision", String.valueOf(cleanupHealthRevision));
        p.setProperty("known", encode(KNOWN));
        p.setProperty("scanned", encode(SCANNED));
        p.setProperty("favourites", encode(FAVOURITES));
        for (Map.Entry<String,Health> e:HEALTH.entrySet()) p.setProperty("health."+encodeOne(e.getKey()),e.getValue().failures+","+e.getValue().lastSuccess+","+e.getValue().lastFailure);
        p.setProperty("undo.count", String.valueOf(undo.size()));
        for (int i = 0; i < undo.size(); i++) {
            Undo entry = undo.get(i);
            String prefix = "undo." + i + ".";
            p.setProperty(prefix + "name", encodeOne(entry.name));
            p.setProperty(prefix + "endpoint", encodeOne(entry.endpoint));
            p.setProperty(prefix + "favourite", String.valueOf(entry.favourite));
            p.setProperty(prefix + "scanned", String.valueOf(entry.scanned));
        }
        p.setProperty("recent", encode(RECENT));
        p.setProperty("removed.count", String.valueOf(RECENTLY_REMOVED.size()));
        for (int i = 0; i < RECENTLY_REMOVED.size(); i++) {
            RecentlyRemoved entry = RECENTLY_REMOVED.get(i);
            String prefix = "removed." + i + ".";
            p.setProperty(prefix + "name", encodeOne(entry.name));
            p.setProperty(prefix + "endpoint", encodeOne(entry.endpoint));
            p.setProperty(prefix + "reason", encodeOne(entry.reason));
            p.setProperty(prefix + "detail", encodeOne(entry.detail));
            p.setProperty(prefix + "at", String.valueOf(entry.atMillis));
            p.setProperty(prefix + "scanned", String.valueOf(entry.scanned));
        }
        try {
            Files.createDirectories(file.getParent());
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                p.store(w, "Zazu's Server Seeker multiplayer tabs");
            }
        } catch (Throwable t) {
            System.err.println("[Zazu's Server Seeker] Could not save server-tab state: " + t);
        }
    }

    private static Path resolveConfigDir() {
        Path path = ToolState.configDir();
        if (path != null) return path;
        try {
            Class<?> loader = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object instance = loader.getMethod("getInstance").invoke(null);
            Object dir = loader.getMethod("getConfigDir").invoke(instance);
            if (dir instanceof Path p) return ConfigPaths.seekerDirectory(p);
        } catch (Throwable ignored) {}
        return ConfigPaths.seekerDirectory(Path.of(System.getProperty("user.dir", "."), "config"));
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

    private static String encodeOne(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString((value==null?"":value).getBytes(StandardCharsets.UTF_8)); }
    private static String decodeOne(String value) { try { return new String(Base64.getUrlDecoder().decode(value),StandardCharsets.UTF_8); } catch(Throwable ignored){ return ""; } }
    private static void decodeHealth(Properties p) {
        for (String key:p.stringPropertyNames()) if (key.startsWith("health.")) try {
            String endpoint=decodeOne(key.substring(7)); String[] v=p.getProperty(key,"0,0,0").split(",");
            HEALTH.put(endpoint,new Health(Integer.parseInt(v[0]),Long.parseLong(v[1]),Long.parseLong(v[2])));
        } catch(Throwable ignored){}
    }
    private static void backupServersFile() {
        try {
            Path seekerDir = resolveConfigDir();
            Path configRoot = seekerDir.getParent();
            Path instanceRoot = configRoot == null ? null : configRoot.getParent();
            if (instanceRoot == null) return;
            Path source = instanceRoot.resolve("servers.dat");
            if (!Files.exists(source)) return;
            Path dir=seekerDir.resolve("backups"); Files.createDirectories(dir);
            Files.copy(source,dir.resolve("servers-"+System.currentTimeMillis()+"-"+Math.abs(System.nanoTime())+".dat"));
            try(var files=Files.list(dir)){ List<Path> all=files.filter(Files::isRegularFile).sorted(Comparator.reverseOrder()).toList(); for(int i=10;i<all.size();i++) Files.deleteIfExists(all.get(i)); }
        } catch(Throwable t){ System.err.println("[Zazu's Server Seeker] Server-list backup failed: "+t); }
    }
    private record Health(int failures,long lastSuccess,long lastFailure){ static final Health EMPTY=new Health(0,0,0); }
    record DeletedServer(String name, String endpoint, boolean favourite, boolean scanned) {}
    record RecentlyRemoved(String name, String endpoint, String reason, String detail,
                           long atMillis, boolean scanned) {}
    private record Undo(String name,String endpoint,boolean favourite,boolean scanned){}

    private static int integer(String value, int fallback) {
        try { return Integer.parseInt(value); } catch (RuntimeException ignored) { return fallback; }
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

    private static void decodeList(String value, List<String> output) {
        if (value == null || value.isBlank()) return;
        Base64.Decoder dec = Base64.getUrlDecoder();
        for (String part : value.split(",")) {
            try {
                String e = new String(dec.decode(part), StandardCharsets.UTF_8);
                e = normalize(e);
                if (!e.isBlank() && !output.contains(e)) output.add(e);
            } catch (Throwable ignored) {}
        }
    }

    private static long longValue(String value, long fallback) {
        try { return Long.parseLong(value == null ? "" : value.trim()); }
        catch (Exception ignored) { return fallback; }
    }
}
