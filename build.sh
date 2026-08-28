#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
property() { sed -n "s/^$1=//p" "$ROOT/gradle.properties" | tail -n 1; }
VERSION="$(property mod_version)"
MC_VERSION="$(property minecraft_version)"
[[ -n "$VERSION" && -n "$MC_VERSION" ]] || {
  echo "mod_version and minecraft_version must be set in gradle.properties." >&2
  exit 1
}
OUT="$ROOT/build/libs/Zazus-Server-Tool-${VERSION}+mc${MC_VERSION}.jar"
TMP="$ROOT/build/local-javac"

if ! command -v javac >/dev/null || ! command -v jar >/dev/null; then
  echo "JDK 21 or newer is required (javac and jar must be on PATH)." >&2
  exit 1
fi

JAVA_MAJOR="$(javac -version 2>&1 | sed -E 's/^javac ([0-9]+).*/\1/')"
if [[ ! "$JAVA_MAJOR" =~ ^[0-9]+$ ]] || (( JAVA_MAJOR < 21 )); then
  echo "JDK 21 or newer is required; found: $(javac -version 2>&1)" >&2
  exit 1
fi

rm -rf "$TMP"
mkdir -p "$TMP/stub/net/fabricmc/api" "$TMP/classes"
cat > "$TMP/stub/net/fabricmc/api/ClientModInitializer.java" <<'STUB'
package net.fabricmc.api;
public interface ClientModInitializer { void onInitializeClient(); }
STUB

mapfile -t SOURCES < <(find "$ROOT/src/client/java" -name '*.java' -type f | sort)
javac --release 21 -Xlint:all -d "$TMP/classes" \
  "$TMP/stub/net/fabricmc/api/ClientModInitializer.java" "${SOURCES[@]}"

# The stub exists only to compile this reflection-based project without fetching
# external dependencies. Fabric Loader supplies the real interface at runtime.
rm -rf "$TMP/classes/net/fabricmc"
find "$TMP/classes" -type d -empty -delete

# Copy all project resources before expanding the release version token.
for RESOURCES in "$ROOT/src/main/resources" "$ROOT/src/client/resources"; do
  if [[ -d "$RESOURCES" ]]; then
    cp -a "$RESOURCES"/. "$TMP/classes"/
  fi
done

python3 - "$TMP/classes/fabric.mod.json" "$VERSION" <<'PY'
from pathlib import Path
import sys
path, version = Path(sys.argv[1]), sys.argv[2]
text = path.read_text(encoding='utf-8').replace('${version}', version)
path.write_text(text, encoding='utf-8')
PY

mkdir -p "$(dirname "$OUT")"
rm -f "$OUT"
(
  cd "$TMP/classes"
  jar --create --file "$OUT" --date=2026-08-21T00:00:00Z .
)
echo "$OUT"
