#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
JAR="${1:-$ROOT/build/libs/Zazus-Server-Tool-0.3.33+mc26.2.jar}"
[[ -f "$JAR" ]] || { echo "JAR not found: $JAR" >&2; exit 1; }

unzip -t "$JAR" >/dev/null

for cls in \
  MultiplayerManagementEntrypoint WhitelistAutoDeleteEntrypoint AutoJoinEntrypoint \
  ContinuousAutoAddEntrypoint TitleCreditEntrypoint ServerTabsEntrypoint; do
  jar tf "$JAR" | grep -qx "dev/zazuzin/zst/${cls}.class" || {
    echo "Missing entrypoint class: $cls" >&2
    exit 1
  }
done

for legacy in a b c d e f g h; do
  if jar tf "$JAR" | grep -qx "dev/zazuzin/zst/${legacy}.class"; then
    echo "Legacy obfuscated class still packaged: $legacy" >&2
    exit 1
  fi
done

if jar tf "$JAR" | grep -q '^net/fabricmc/api/ClientModInitializer.class$'; then
  echo "Compile stub was accidentally packaged" >&2
  exit 1
fi

if grep -R -nE 'CoreUiStripper|EnhancementsEntrypoint|toggleButton|dev\.zazuzin\.zst\.[a-h]("|\x27)' "$ROOT/src/client/java"; then
  echo "Legacy patch-only implementation reference found" >&2
  exit 1
fi

if grep -R -n 'Minecraft.setScreen(Screen)' "$ROOT/src/client/java"; then
  echo "Obsolete Minecraft.setScreen compatibility path found" >&2
  exit 1
fi

python3 - "$JAR" <<'PY'
import json, struct, sys, zipfile
from pathlib import PurePosixPath

jar = sys.argv[1]
expected_entrypoints = {
    "dev.zazuzin.zst.MultiplayerManagementEntrypoint",
    "dev.zazuzin.zst.WhitelistAutoDeleteEntrypoint",
    "dev.zazuzin.zst.AutoJoinEntrypoint",
    "dev.zazuzin.zst.ContinuousAutoAddEntrypoint",
    "dev.zazuzin.zst.TitleCreditEntrypoint",
    "dev.zazuzin.zst.ServerTabsEntrypoint",
}

with zipfile.ZipFile(jar) as z:
    names = z.namelist()
    if len(names) != len(set(names)):
        raise SystemExit("JAR contains duplicate paths")

    meta = json.loads(z.read("fabric.mod.json"))
    if meta.get("id") != "zazus-server-tool":
        raise SystemExit("fabric.mod.json mod id mismatch")
    if meta.get("version") != "0.3.33":
        raise SystemExit("fabric.mod.json version mismatch")
    if set(meta.get("entrypoints", {}).get("client", [])) != expected_entrypoints:
        raise SystemExit("fabric.mod.json client entrypoints mismatch")

    classes = [n for n in names if n.endswith(".class")]
    if not classes:
        raise SystemExit("JAR contains no classes")
    for name in classes:
        data = z.read(name)
        if len(data) < 8 or data[:4] != b"\xca\xfe\xba\xbe":
            raise SystemExit(f"Invalid class file: {name}")
        major = struct.unpack(">H", data[6:8])[0]
        if major != 65:
            raise SystemExit(f"{name} targets class-file major {major}, expected Java 21 (65)")

    forbidden = {
        "dev/zazuzin/zst/CoreUiStripper.class",
        "dev/zazuzin/zst/EnhancementsEntrypoint.class",
    }
    if forbidden.intersection(names):
        raise SystemExit("Legacy patch-only classes are still packaged")

print(f"Verified {len(classes)} Java 21 class files and Fabric metadata.")
PY

# Verify optional BreakBlocks authentication is header-only and that anonymous
# requests remain untouched.
API_TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$API_TEST_DIR"' EXIT
mkdir -p "$API_TEST_DIR/dev/zazuzin/zst"
cat > "$API_TEST_DIR/dev/zazuzin/zst/ApiKeyRequestTest.java" <<'JAVA'
package dev.zazuzin.zst;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class ApiKeyRequestTest {
    public static void main(String[] args) throws Exception {
        URI uri = URI.create("https://api.breakblocks.com/api/v0.1/servers/find?limit=20");
        HttpRequest anonymous = ServerFinderClient.buildBreakBlocksRequest(uri, "");
        if (anonymous.headers().firstValue("Authorization").isPresent()) {
            throw new AssertionError("Anonymous BreakBlocks request unexpectedly has Authorization header");
        }

        String fakeKey = "unit-test-secret-not-a-real-key";
        HttpRequest authenticated = ServerFinderClient.buildBreakBlocksRequest(uri, fakeKey);
        String auth = authenticated.headers().firstValue("Authorization").orElse("");
        if (!auth.equals("Bearer " + fakeKey)) {
            throw new AssertionError("Authenticated BreakBlocks request did not use Bearer header");
        }
        if (authenticated.uri().toString().contains(fakeKey)) {
            throw new AssertionError("API key leaked into BreakBlocks request URI");
        }

        // ToolState must create a blank key setting, pick up a manually edited
        // key, and preserve that key when unrelated settings are subsequently saved.
        Path root = Files.createTempDirectory("zst-api-key-test-");
        System.setProperty("user.dir", root.toString());
        ToolState.hasBreakBlocksApiKey();
        Path config = root.resolve("config/zazus-server-tool.properties");
        if (!Files.exists(config)) throw new AssertionError("Config file was not created");

        Properties p = new Properties();
        try (var reader = Files.newBufferedReader(config, StandardCharsets.UTF_8)) { p.load(reader); }
        if (!p.containsKey("breakBlocksApiKey")) throw new AssertionError("Blank API-key property was not created");
        p.setProperty("breakBlocksApiKey", fakeKey);
        try (var writer = Files.newBufferedWriter(config, StandardCharsets.UTF_8)) { p.store(writer, "test"); }

        ToolState.reloadBreakBlocksApiKey();
        if (!ToolState.hasBreakBlocksApiKey()) throw new AssertionError("Manual API-key edit was not reloaded");
        ToolState.skipAddedHistory = !ToolState.skipAddedHistory;
        ToolState.save();

        Properties after = new Properties();
        try (var reader = Files.newBufferedReader(config, StandardCharsets.UTF_8)) { after.load(reader); }
        if (!fakeKey.equals(after.getProperty("breakBlocksApiKey"))) {
            throw new AssertionError("Unrelated config save overwrote the API key");
        }
    }
}
JAVA
javac --release 21 -cp "$JAR" -d "$API_TEST_DIR" "$API_TEST_DIR/dev/zazuzin/zst/ApiKeyRequestTest.java"
java -cp "$JAR:$API_TEST_DIR" dev.zazuzin.zst.ApiKeyRequestTest

echo "BreakBlocks optional-authentication regression tests passed."

echo "Verification passed: $JAR"
