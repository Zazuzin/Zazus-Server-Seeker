#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
property() { sed -n "s/^$1=//p" "$ROOT/gradle.properties" | tail -n 1; }
VERSION="$(property mod_version)"
MC_VERSION="$(property minecraft_version)"
OUT="$ROOT/build/libs/Zazus-Server-Seeker-${VERSION}-mc${MC_VERSION}.jar"

JAVA_VERSION="$(java -version 2>&1 | sed -n '1s/.*version "\([0-9][0-9]*\).*/\1/p')"
if [[ "$JAVA_VERSION" != "25" ]]; then
  echo "JDK 25 is required for the Minecraft 26.2 release build; found Java ${JAVA_VERSION:-unknown}." >&2
  exit 1
fi

if [[ -x "$ROOT/gradlew" && -f "$ROOT/gradle/wrapper/gradle-wrapper.jar" ]]; then
  GRADLE=("$ROOT/gradlew")
elif command -v gradle >/dev/null 2>&1; then
  GRADLE=(gradle)
else
  echo "Gradle 9.5.1 is required for the Minecraft 26.2 release build." >&2
  echo "Install Gradle 9.5.1 or generate/restore the Gradle wrapper, then rerun ./build.sh." >&2
  exit 1
fi

GRADLE_VERSION="$("${GRADLE[@]}" --version | sed -n 's/^Gradle \([0-9.]*\)$/\1/p' | head -n 1)"
if [[ "$GRADLE_VERSION" != "9.5.1" ]]; then
  echo "Gradle 9.5.1 is required for the release build; found ${GRADLE_VERSION:-unknown}." >&2
  exit 1
fi

cd "$ROOT"
rm -rf build
"${GRADLE[@]}" --no-daemon clean jar writeClientCompileClasspath
[[ -s "$OUT" ]] || {
  echo "Expected release JAR was not created: $OUT" >&2
  exit 1
}

# Release builds must come from Loom, never from compile stubs or patched class files.
jar tf "$OUT" | grep -q '^fabric.mod.json$' || { echo "fabric.mod.json missing from release JAR" >&2; exit 1; }
if jar tf "$OUT" | grep -Eq '(^|/)build-stubs/|(^|/)stubs/|net/minecraft/client/Minecraft.class$|net/fabricmc/api/ClientModInitializer.class$'; then
  echo "Release JAR contains forbidden compile-stub content." >&2
  exit 1
fi


python3 "$ROOT/tools/classfile_api_audit.py" \
  --classes "$ROOT/build/classes/java/client" \
  --classpath-file "$ROOT/build/client-compile-classpath.txt"
python3 "$ROOT/tools/notes_ui_audit.py" "$OUT"

echo "$OUT"
