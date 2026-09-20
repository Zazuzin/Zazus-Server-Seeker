package dev.zazuzin.zst;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** One migration-safe home for every Server Seeker configuration file. */
public final class ConfigPaths {
    private static final String DIRECTORY_NAME = "zazus-server-seeker";
    private static final Map<String, String> LEGACY_FILES = Map.ofEntries(
            Map.entry("zazus-server-tool.properties", "server-tool.properties"),
            Map.entry("zazus-server-tabs.properties", "server-tabs.properties"),
            Map.entry("zazus-server-tool-autojoin.properties", "autojoin.properties"),
            Map.entry("zazus-server-auth.properties", "server-auth.properties"),
            Map.entry("breakblocks-contributions.csv", "breakblocks-contributions.csv"),
            Map.entry("breakblocks-contribution-queue.txt", "breakblocks-contribution-queue.txt"),
            Map.entry("breakblocks-contribution-failed.txt", "breakblocks-contribution-failed.txt"),
            Map.entry("breakblocks-contribution-cooldowns.txt", "breakblocks-contribution-cooldowns.txt"),
            Map.entry("private-lan-servers.txt", "private-lan-servers.txt")
    );
    private static boolean migrated;

    private ConfigPaths() {}

    public static synchronized Path seekerDirectory(Path configRoot) {
        Path root = configRoot == null
                ? Path.of(System.getProperty("user.dir", "."), "config")
                : configRoot;
        Path target = root.resolve(DIRECTORY_NAME);
        if (!migrated) {
            migrateLegacy(root, target);
            migrated = true;
        }
        return target;
    }

    private static void migrateLegacy(Path root, Path target) {
        try {
            Files.createDirectories(target);
        } catch (Throwable t) {
            System.err.println("[Zazu's Server Seeker] Could not create unified config folder: " + t);
            return;
        }
        for (Map.Entry<String, String> entry : LEGACY_FILES.entrySet()) {
            migrateFile(root.resolve(entry.getKey()), target.resolve(entry.getValue()));
        }
        migrateDirectory(root.resolve("zazus-server-notes"), target.resolve("server-notes"));
        migrateDirectory(root.resolve("zazus-server-tool-backups"), target.resolve("backups"));
    }

    private static void migrateFile(Path source, Path destination) {
        try { moveIfPossible(source, destination); }
        catch (Throwable t) {
            System.err.println("[Zazu's Server Seeker] Could not migrate config file " + source + ": " + t);
        }
    }

    private static void migrateDirectory(Path source, Path destination) {
        try { moveDirectoryContents(source, destination); }
        catch (Throwable t) {
            System.err.println("[Zazu's Server Seeker] Could not migrate config folder " + source + ": " + t);
        }
    }

    private static void moveDirectoryContents(Path source, Path destination) throws IOException {
        if (!Files.isDirectory(source) || source.equals(destination)) return;
        Files.createDirectories(destination);
        try (var paths = Files.walk(source)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (path.equals(source)) continue;
                Path relative = source.relativize(path);
                Path target = destination.resolve(relative);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                    tryDeleteEmpty(path);
                } else {
                    Files.createDirectories(target.getParent());
                    moveIfPossible(path, target);
                }
            }
        }
        tryDeleteEmpty(source);
    }

    private static void moveIfPossible(Path source, Path destination) throws IOException {
        if (!Files.exists(source) || source.equals(destination)) return;
        if (Files.exists(destination)) {
            System.err.println("[Zazu's Server Seeker] Kept legacy config because destination already exists: " + source);
            return;
        }
        Files.createDirectories(destination.getParent());
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, destination);
        }
    }

    private static void tryDeleteEmpty(Path directory) {
        try { Files.deleteIfExists(directory); }
        catch (DirectoryNotEmptyException ignored) {}
        catch (IOException error) {
            System.err.println("[Zazu's Server Seeker] Could not remove empty legacy config folder "
                    + directory + ": " + error);
        }
    }
}
