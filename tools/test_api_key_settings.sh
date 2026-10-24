#!/usr/bin/env bash
set -euo pipefail
key_root="$(cd "$(dirname "$0")/.." && pwd)"
key_version="$(sed -n 's/^mod_version=//p' "$key_root/gradle.properties")"
key_minecraft="$(sed -n 's/^minecraft_version=//p' "$key_root/gradle.properties")"
key_jar="$key_root/build/libs/Zazus-Server-Seeker-$key_version-mc$key_minecraft.jar"
key_test_dir="$(mktemp -d)"
trap 'rm -rf "$key_test_dir"' EXIT
javac --release 25 -cp "$key_jar" -d "$key_test_dir" "$key_root/tools/ApiKeySettingsTest.java"
java -cp "$key_jar:$key_test_dir" dev.zazuzin.zst.ApiKeySettingsTest
