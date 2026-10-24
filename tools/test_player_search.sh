#!/usr/bin/env bash
set -euo pipefail
player_root="$(cd "$(dirname "$0")/.." && pwd)"
player_test_dir="$(mktemp -d)"
trap 'rm -rf "$player_test_dir"' EXIT
javac -d "$player_test_dir" \
  "$player_root/src/client/java/dev/zazu/servernotes/util/SimpleJson.java" \
  "$player_root/src/client/java/dev/zazuzin/zst/ReleaseInfo.java" \
  "$player_root/src/client/java/dev/zazuzin/zst/BreakBlocksPlayers.java" \
  "$player_root/tools/BreakBlocksPlayersTest.java"
java -cp "$player_test_dir" dev.zazuzin.zst.BreakBlocksPlayersTest
