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
    private static Undo undo;
    private static final LinkedList<String> RECENT = new LinkedList<>();
    private static final int MAX_RECENT = 5;
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
        decode(p.getProperty("favourites", ""), FAVOURITES);
        decodeHealth(p);
        String undoEndpoint = decodeOne(p.getProperty("undo.endpoint", ""));
        if (!undoEndpoint.isBlank()) undo = new Undo(decodeOne(p.getProperty("undo.name", "")), undoEndpoint,
                Boolean.parseBoolean(p.getProperty("undo.favourite", "false")),
                Boolean.parseBoolean(p.getProperty("undo.scanned", "false")));
        decodeList(p.getProperty("recent", ""), RECENT);
        while (RECENT.size() > MAX_RECENT) RECENT.removeLast();
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

    static synchronized int healthFailures(String endpoint) { load(); return HEALTH.getOrDefault(normalize(endpoint), Health.EMPTY).failures; }
    static synchronized void recordHealthSuccess(String endpoint) {
        load(); String e=normalize(endpoint); if (e.isBlank()) return;
        HEALTH.put(e, new Health(0, System.currentTimeMillis(), HEALTH.getOrDefault(e, Health.EMPTY).lastFailure)); save();
    }
    static synchronized int recordHealthFailure(String endpoint) {
        load(); String e=normalize(endpoint); if (e.isBlank()) return 0;
        Health h=HEALTH.getOrDefault(e, Health.EMPTY); Health n=new Health(h.failures+1,h.lastSuccess,System.currentTimeMillis()); HEALTH.put(e,n); save(); return n.failures;
    }
    static synchronized void resetHealth(String endpoint) { load(); HEALTH.remove(normalize(endpoint)); save(); }
    static synchronized String healthSummary(String endpoint) {
        load(); Health h=HEALTH.getOrDefault(normalize(endpoint), Health.EMPTY);
        return h.failures == 0 ? "Health OK" : "Health " + h.failures + "/3";
    }

    static synchronized void recordUndo(String name, String endpoint) {
        load(); String e=normalize(endpoint); if (e.isBlank()) return;
        backupServersFile();
        undo = new Undo(name == null ? "" : name, endpoint, isFavourite(endpoint), isScanned(endpoint));
        save();
    }

    static synchronized boolean hasUndo() { load(); return undo != null; }
    static synchronized boolean undoLastDelete(Object client) {
        load(); if (undo == null) return false;
        try {
            Object list=ServerFinderClient.ServerListBridge.createLoadedList(client);
            if (ServerFinderClient.ServerListBridge.findServer(list, undo.endpoint) == null) {
                String name=undo.name.isBlank()?"Restored "+undo.endpoint:undo.name;
                Object data=ServerFinderClient.ServerListBridge.createServerData(name, undo.endpoint);
                ServerFinderClient.ServerListBridge.addServerData(list,data); ServerFinderClient.ServerListBridge.save(list);
            }
            setFavourite(undo.endpoint,undo.favourite);
            if (undo.scanned) markScanned(undo.endpoint); else promoteVerified(undo.endpoint);
            undo=null; save(); return true;
        } catch (Throwable t) { System.err.println("[Zazu's Server Tool] Undo failed: "+t); return false; }
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
            System.out.println("[Zazu's Server Tool] Verified server promoted from Scanned Servers to Servers: " + endpoint);
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
            System.out.println("[Zazu's Server Tool] Added to Recent Servers: " + endpoint);
        }
    }

    static synchronized boolean isRecent(String endpoint) {
        load();
        return RECENT.contains(normalize(endpoint));
    }

    /** Moves all persisted category metadata when Edit Server Info changes an address. */
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
        if (file == null) file = resolveConfigDir().resolve("zazus-server-tabs.properties");
        Properties p = new Properties();
        p.setProperty("migrated", String.valueOf(migrated));
        p.setProperty("known", encode(KNOWN));
        p.setProperty("scanned", encode(SCANNED));
        p.setProperty("favourites", encode(FAVOURITES));
        for (Map.Entry<String,Health> e:HEALTH.entrySet()) p.setProperty("health."+encodeOne(e.getKey()),e.getValue().failures+","+e.getValue().lastSuccess+","+e.getValue().lastFailure);
        if (undo != null) {
            p.setProperty("undo.name",encodeOne(undo.name)); p.setProperty("undo.endpoint",encodeOne(undo.endpoint));
            p.setProperty("undo.favourite",String.valueOf(undo.favourite)); p.setProperty("undo.scanned",String.valueOf(undo.scanned));
        }
        p.setProperty("recent", encode(RECENT));
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
            Path root=resolveConfigDir().getParent(); if(root==null)return; Path source=root.resolve("servers.dat"); if(!Files.exists(source))return;
            Path dir=resolveConfigDir().resolve("zazus-server-tool-backups"); Files.createDirectories(dir);
            Files.copy(source,dir.resolve("servers-"+System.currentTimeMillis()+"-"+Math.abs(System.nanoTime())+".dat"));
            try(var files=Files.list(dir)){ List<Path> all=files.filter(Files::isRegularFile).sorted(Comparator.reverseOrder()).toList(); for(int i=10;i<all.size();i++) Files.deleteIfExists(all.get(i)); }
        } catch(Throwable t){ System.err.println("[Zazu's Server Tool] Server-list backup failed: "+t); }
    }
    private record Health(int failures,long lastSuccess,long lastFailure){ static final Health EMPTY=new Health(0,0,0); }
    private record Undo(String name,String endpoint,boolean favourite,boolean scanned){}

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
}
