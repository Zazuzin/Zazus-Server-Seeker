package dev.zazuzin.zst;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Exercises the real packaged storage classes without a game or network connection. */
public final class ApiKeySettingsTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static Properties read(Path path) throws IOException {
        Properties result = new Properties();
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { result.load(reader); }
        return result;
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("zst-key-settings-test-");
        System.setProperty("user.dir", root.toString());
        try {
            ToolState.load();
            Path path = ToolState.configFile();
            Properties before = read(path);
            before.setProperty("custom-setting", "keep-this");
            before.setProperty("breakBlocksApiKey", "old-fake-key");
            try (var writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) { before.store(writer, "test"); }
            ToolState.reloadBreakBlocksApiKey();
            BreakBlocksAccount.configure("old-fake-key");
            BreakBlocksAccount.observe(true, "VIP Treatment");
            check(ToolState.setBreakBlocksApiKey("  \"Bearer new-fake-key\"  "), "In-game key edit failed");
            check(ToolState.breakBlocksApiKey().equals("new-fake-key"), "New key missing from memory");
            check(!BreakBlocksAccount.snapshot().observed(), "Old key's authentication was retained");
            Properties expected = new Properties(); expected.putAll(before);
            expected.setProperty("breakBlocksApiKey", "new-fake-key");
            check(read(path).equals(expected), "Key edit changed unrelated on-disk settings");
            ToolState.skipAddedHistory = !ToolState.skipAddedHistory;
            ToolState.save(); ToolState.reloadBreakBlocksApiKey();
            check(ToolState.breakBlocksApiKey().equals("new-fake-key"), "Unrelated save restored the old key");
            check(ToolState.setBreakBlocksApiKey(""), "Clear Key failed");
            ToolState.save(); ToolState.reloadBreakBlocksApiKey();
            check(ToolState.breakBlocksApiKey().isEmpty(), "Cleared key reappeared after save/reload");
            check(read(path).getProperty("breakBlocksApiKey").isEmpty(), "Clear was not persisted");
            for (String invalid : List.of("bad key", "bad\nkey", "x".repeat(513))) {
                boolean rejected = false;
                try { ToolState.setBreakBlocksApiKey(invalid); }
                catch (IllegalArgumentException safe) { rejected = true; }
                check(rejected, "Invalid key characters/length were accepted");
                check(ToolState.breakBlocksApiKey().isEmpty(), "Invalid input changed saved key");
            }
            if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
                check(Files.getPosixFilePermissions(path).equals(Set.of(
                        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE)), "Config permissions widened");
            }
            try (var paths = Files.list(path.getParent())) {
                check(paths.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")), "Temporary key file was left behind");
            }
            Files.delete(path); Files.createDirectory(path);
            check(!ToolState.setBreakBlocksApiKey("must-not-be-saved"), "Storage failure was reported as success");
            check(ToolState.breakBlocksApiKey().isEmpty(), "Storage failure changed the in-memory key");
            System.out.println("In-game key save, clear, normalization, permissions and failure checks passed.");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
