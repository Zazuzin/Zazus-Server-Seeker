#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
property() { sed -n "s/^$1=//p" "$ROOT/gradle.properties" | tail -n 1; }
VERSION="$(property mod_version)"
MC_VERSION="$(property minecraft_version)"
JAR="${1:-$ROOT/build/libs/Zazus-Server-Seeker-${VERSION}-mc${MC_VERSION}.jar}"
[[ -f "$JAR" ]] || { echo "JAR not found: $JAR" >&2; exit 1; }
JAR="$(cd "$(dirname "$JAR")" && pwd)/$(basename "$JAR")"

TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT

cat > "$TEST_DIR/ServerNotesCompatibilityTest.java" <<'JAVA'
import dev.zazu.servernotes.storage.ServerProfileStore;
import dev.zazu.servernotes.model.ServerProfile;
import dev.zazu.servernotes.service.AutoJoinFailureNotes;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public final class ServerNotesCompatibilityTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("zst-rc-notes-");
        Path config = root.resolve("config");
        Path notesDir = config.resolve("zazus-server-notes");
        Files.createDirectories(notesDir);
        Path file = notesDir.resolve("server-profiles.json");
        String json = """
        {
          "schemaVersion":2,
          "servers":{
            "example.net:25565":{
              "key":"example.net:25565","address":"example.net","port":25565,
              "displayAddress":"EXAMPLE.NET:25565","customName":"Test Server",
              "generalNotes":"beta10 fixture","favourite":true,
              "createdAt":"2026-09-01T10:00:00Z","updatedAt":"2026-09-08T20:00:00Z",
              "lastJoinedAt":"2026-09-08T19:00:00Z","serverVersion":"26.2",
              "authenticationType":"cracked","tags":["test","important-tag"],
              "notes":[{"id":"note-1","text":"Keep this note","category":"base","createdAt":"2026-09-01T10:01:00Z","editedAt":null}],
              "locations":[{"id":"loc-1","name":"Home","x":12.5,"y":64.0,"z":-8.25,"dimension":"minecraft:overworld","createdAt":"2026-09-01T10:02:00Z","editedAt":null}],
              "players":{"uuid:1234":{"key":"uuid:1234","uuid":"1234","username":"SavedPlayer","firstSeenAt":"2026-09-01T10:03:00Z","lastSeenAt":"2026-09-08T19:00:00Z","encounters":7,"notes":""}},
              "metadata":{"source":"beta10"}
            }
          }
        }
        """;
        Files.writeString(file, json, StandardCharsets.UTF_8);
        ServerProfileStore store = new ServerProfileStore(config);
        ServerProfile profile = store.find("example.net:25565").orElseThrow();
        if (!profile.favourite()) throw new AssertionError("Important state lost");
        if (!"cracked".equals(profile.authenticationType())) throw new AssertionError("auth metadata lost");
        if (profile.notes().size() != 1 || !"Keep this note".equals(profile.notes().get(0).text())) throw new AssertionError("note lost");
        if (profile.locations().size() != 1 || !"Home".equals(profile.locations().get(0).name())) throw new AssertionError("location lost");
        if (profile.players().size() != 1 || !"SavedPlayer".equals(profile.players().values().iterator().next().username())) throw new AssertionError("player lost");
        if (!profile.tags().contains("important-tag")) throw new AssertionError("tag lost");
        if (!"beta10".equals(profile.metadata().get("source"))) throw new AssertionError("metadata lost");

        AutoJoinFailureNotes.record(store, "EXAMPLE.NET", "Connection timed out");
        if (profile.notes().size() != 2) throw new AssertionError("Auto Join failure note not added");
        if (!"Connection timed out".equals(profile.notes().get(0).text())) throw new AssertionError("Auto Join reason changed");
        if (!AutoJoinFailureNotes.CATEGORY.equals(profile.notes().get(0).category())) throw new AssertionError("Auto Join category missing");
        if (!profile.favourite() || !profile.tags().contains("important-tag")) {
            throw new AssertionError("Hidden Important/tag data was not preserved");
        }

        store.saveProfile(profile);
        Path backup = notesDir.resolve("server-profiles.json.bak");
        if (!Files.isRegularFile(backup)) throw new AssertionError("backup not created");
        if (!Files.readString(file, StandardCharsets.UTF_8).contains("\"schemaVersion\": 2")) throw new AssertionError("schema version changed");

        Files.writeString(file, "{ definitely broken", StandardCharsets.UTF_8);
        ServerProfileStore recovered = new ServerProfileStore(config);
        ServerProfile recoveredProfile = recovered.find("example.net:25565").orElseThrow();
        if (!"SavedPlayer".equals(recoveredProfile.players().values().iterator().next().username())) throw new AssertionError("backup recovery lost player");
        try (var files = Files.list(notesDir)) {
            if (files.noneMatch(p -> p.getFileName().toString().startsWith("server-profiles.corrupt-"))) {
                throw new AssertionError("corrupt file was not preserved");
            }
        }
    }
}
JAVA
javac --release 25 -cp "$JAR" -d "$TEST_DIR" "$TEST_DIR/ServerNotesCompatibilityTest.java"
java -cp "$JAR:$TEST_DIR" ServerNotesCompatibilityTest

echo "Server Notes beta10 schema-v2 compatibility/recovery passed."

mkdir -p "$TEST_DIR/dev/zazuzin/zst"
cat > "$TEST_DIR/dev/zazuzin/zst/ToolStateUpgradeTest.java" <<'JAVA'
package dev.zazuzin.zst;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Properties;

public final class ToolStateUpgradeTest {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(System.getProperty("user.dir"));
        Path configDir = root.resolve("config");
        Files.createDirectories(configDir);
        Path file = configDir.resolve("zazus-server-tool.properties");
        Properties p = new Properties();
        p.setProperty("skipAddedHistory", "false"); p.setProperty("blockDeleted", "false");
        p.setProperty("favouritesFirst", "false"); p.setProperty("autoAddDefault", "true");
        p.setProperty("quickSearch", "true"); p.setProperty("contributeVerifiedServers", "false");
        p.setProperty("authDetectionEnabled", "false"); p.setProperty("autoAddLimit", "50");
        p.setProperty("versionIndex", "3"); p.setProperty("minIndex", "2"); p.setProperty("maxIndex", "5");
        p.setProperty("sortIndex", "2"); p.setProperty("serverTypeIndex", "2"); p.setProperty("finderSourceIndex", "4");
        p.setProperty("breakBlocksMaxAgeDays", "14"); p.setProperty("addedCount", "123"); p.setProperty("deletedCount", "45");
        p.setProperty("breakBlocksApiKey", "");
        try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) { p.store(writer, "beta10 fixture"); }

        if (ToolState.skipAddedHistory || ToolState.blockDeleted || ToolState.favouritesFirst) throw new AssertionError("boolean settings changed");
        if (!ToolState.autoAddDefault || !ToolState.quickSearch) throw new AssertionError("search settings changed");
        if (ToolState.contributeVerifiedServers || ToolState.authDetectionEnabled) throw new AssertionError("opt-outs changed");
        if (ToolState.autoAddLimit != 50 || ToolState.finderSourceIndex != 4 || ToolState.breakBlocksMaxAgeDays != 14) throw new AssertionError("numeric settings changed");
        if (ToolState.addedCount != 123 || ToolState.deletedCount != 45) throw new AssertionError("stats changed");
        ToolState.save();
        Properties after = new Properties();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { after.load(reader); }
        if (!"false".equals(after.getProperty("authDetectionEnabled"))) throw new AssertionError("auth opt-out not preserved");
        if (!"false".equals(after.getProperty("contributeVerifiedServers"))) throw new AssertionError("contribution opt-out not preserved");
        if (!"14".equals(after.getProperty("breakBlocksMaxAgeDays"))) throw new AssertionError("age not preserved");
    }
}
JAVA
javac --release 25 -cp "$JAR" -d "$TEST_DIR" "$TEST_DIR/dev/zazuzin/zst/ToolStateUpgradeTest.java"
TMP_ROOT="$(mktemp -d)"
java -Duser.dir="$TMP_ROOT" -cp "$JAR:$TEST_DIR" dev.zazuzin.zst.ToolStateUpgradeTest
rm -rf "$TMP_ROOT"
echo "Server Seeker beta10 settings compatibility passed."
