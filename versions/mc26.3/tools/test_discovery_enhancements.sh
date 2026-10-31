#!/usr/bin/env bash
set -euo pipefail
discovery_root="$(cd "$(dirname "$0")/.." && pwd)"
discovery_version="$(sed -n 's/^mod_version=//p' "$discovery_root/gradle.properties")"
discovery_mc="$(sed -n 's/^minecraft_version=//p' "$discovery_root/gradle.properties")"
discovery_jar="$discovery_root/build/libs/Zazus-Server-Seeker-$discovery_version-mc$discovery_mc.jar"
discovery_test_dir="$(mktemp -d)"
trap 'rm -rf "$discovery_test_dir"' EXIT
javac --release 25 -cp "$discovery_jar" -d "$discovery_test_dir" "$discovery_root/tools/DiscoveryEnhancementsTest.java"
java -cp "$discovery_jar:$discovery_test_dir" dev.zazuzin.zst.DiscoveryEnhancementsTest
