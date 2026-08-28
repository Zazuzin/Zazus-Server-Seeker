#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
property() { sed -n "s/^$1=//p" "$ROOT/gradle.properties" | tail -n 1; }
VERSION="$(property mod_version)"
MC_VERSION="$(property minecraft_version)"
JAR="${1:-$ROOT/build/libs/Zazus-Server-Tool-${VERSION}+mc${MC_VERSION}.jar}"
[[ -f "$JAR" ]] || { echo "JAR not found: $JAR" >&2; exit 1; }

grep -q 'undoLastDeleteButton' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Undo Last Delete is not category-managed" >&2; exit 1;
}
grep -q 'setVisibleActive(undoLastDelete, bulkDeleteView' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Undo Last Delete visibility is not restricted to Servers/Scanned Servers" >&2; exit 1;
}

unzip -t "$JAR" >/dev/null

MOD_JSON="$(unzip -p "$JAR" fabric.mod.json)"
grep -q '"homepage": "https://github.com/Zazuzin/Zazus-Server-Scanner"' <<<"$MOD_JSON" || {
  echo "Official project homepage is missing from Fabric metadata" >&2; exit 1;
}
grep -q '"sources": "https://github.com/Zazuzin/Zazus-Server-Scanner"' <<<"$MOD_JSON" || {
  echo "Official source repository is missing from Fabric metadata" >&2; exit 1;
}
grep -q '"issues": "https://github.com/Zazuzin/Zazus-Server-Scanner/issues"' <<<"$MOD_JSON" || {
  echo "Official issue tracker is missing from Fabric metadata" >&2; exit 1;
}

for cls in \
  MultiplayerManagementEntrypoint WhitelistAutoDeleteEntrypoint AutoJoinEntrypoint \
  TitleCreditEntrypoint ServerTabsEntrypoint; do
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

# Regression guards for the stable v0.3.41 Multiplayer repair. Category switching must
# use Minecraft's in-memory ServerSelectionList rebuild path, screen ownership
# must be 26.2-aware, and the old widget-list replacement strategy must stay gone.
grep -q 'updateOnlineServers' "$ROOT/src/client/java/dev/zazuzin/zst/ServerListAccess.java" || {
  echo "Missing in-memory category rebuild path" >&2; exit 1;
}
grep -q 'ScreenCompat.currentScreen' "$ROOT/src/client/java/dev/zazuzin/zst/Reflection.java" || {
  echo "Reflection.currentScreen is not using the 26.2-compatible screen lookup" >&2; exit 1;
}
grep -q 'getRowTop' "$ROOT/src/client/java/dev/zazuzin/zst/MultiplayerManagementEntrypoint.java" || {
  echo "Row controls are not anchored to AbstractSelectionList#getRowTop" >&2; exit 1;
}
if grep -q 'replaceWidgetList' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java"; then
  echo "Legacy widget-list replacement strategy is still present" >&2; exit 1;
fi
if grep -q 'backButton' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java"; then
  echo "Custom Back button returned; Multiplayer must use Minecraft's native Back control" >&2; exit 1;
fi
if grep -q 'refreshButton' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java"; then
  echo "Custom Refresh button returned; Multiplayer must use Minecraft's native Refresh control" >&2; exit 1;
fi
if grep -qE 'normalizeVanillaFooter|captureAndRemoveVanillaNavigation|removeVanillaNavigationDuplicates|VANILLA_BUTTON_ROW_WIDTH' \
    "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java"; then
  echo "Footer manipulation returned; Back/Refresh must stay fully vanilla" >&2; exit 1;
fi
grep -q 'Minecraft.*native Back and Refresh' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Native Back/Refresh ownership guard is missing" >&2; exit 1;
}
grep -q 'isNativeBackWidget' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Hub is not preserving Minecraft's native Back button" >&2; exit 1;
}
grep -q 'widgetLabel(widget).trim().equals("Refresh")' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Native Refresh category-preservation hook is missing" >&2; exit 1;
}
grep -q 'purgeStaleOwnedWidgetsExceptCurrent' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Stale Zazu navigation-widget cleanup is missing" >&2; exit 1;
}
grep -q 'setVisibleActive(widget, false, false)' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Ghost navigation controls are not hidden before removal" >&2; exit 1;
}
grep -q 'requestViewAfterRefresh' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Category-preserving refresh path is missing" >&2; exit 1;
}
grep -q 'currentRowControlY' "$ROOT/src/client/java/dev/zazuzin/zst/MultiplayerManagementEntrypoint.java" || {
  echo "Row controls are not vertically centered on live entries" >&2; exit 1;
}
grep -q 'centeredRowLeft' "$ROOT/src/client/java/dev/zazuzin/zst/MultiplayerManagementEntrypoint.java" || {
  echo "Row controls are missing the safe centred left-column fallback" >&2; exit 1;
}
grep -q 'ServerCategoryStore.promoteVerified(sb.endpoint)' "$ROOT/src/client/java/dev/zazuzin/zst/MultiplayerManagementEntrypoint.java" || {
  echo "Unfavourite-to-Servers promotion rule is missing" >&2; exit 1;
}
grep -q 'returningAfterFailure' "$ROOT/src/client/java/dev/zazuzin/zst/AutoJoinEntrypoint.java" || {
  echo "Auto Join cancel/failure distinction is missing" >&2; exit 1;
}
grep -q 'handleWhitelistFailure' "$ROOT/src/client/java/dev/zazuzin/zst/WhitelistAutoDeleteEntrypoint.java" || {
  echo "Shared whitelist rejection deletion path is missing" >&2; exit 1;
}
grep -q 'always continue with' "$ROOT/src/client/java/dev/zazuzin/zst/Reflection.java" || {
  echo "Ghost-widget identity sweep is missing" >&2; exit 1;
}
grep -q 'DisconnectReason.extract' "$ROOT/src/client/java/dev/zazuzin/zst/WhitelistAutoDeleteEntrypoint.java" || {
  echo "Robust disconnect-reason extraction is missing" >&2; exit 1;
}
grep -q 'allServerDataLists' "$ROOT/src/client/java/dev/zazuzin/zst/ServerListAccess.java" || {
  echo "Whitelist deletion is not sweeping all ServerList backing lists" >&2; exit 1;
}
grep -q 'dedicated left-side rail' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Dedicated non-overlapping Multiplayer control rail is missing" >&2; exit 1;
}
grep -q 'registerControlMouseInterceptor' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Left-rail click interception is missing" >&2; exit 1;
}
grep -q 'if (state.view == View.HUB) return Boolean.TRUE' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Category hub is still being intercepted instead of using normal Minecraft button dispatch" >&2; exit 1;
}
grep -q 'dispatchWidgetClick(widget, mouse)' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Left-rail interceptor is not using real widget mouseClicked dispatch" >&2; exit 1;
}
grep -q 'STATES.get(state.screen) != state' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "ServerTabs stale re-init callback guard is missing" >&2; exit 1;
}
grep -q 'STATES.get(state.screen) != state' "$ROOT/src/client/java/dev/zazuzin/zst/MultiplayerManagementEntrypoint.java" || {
  echo "MultiplayerManagement stale re-init callback guard is missing" >&2; exit 1;
}
grep -q 'screenListElements' "$ROOT/src/client/java/dev/zazuzin/zst/Reflection.java" || {
  echo "All-Screen-list duplicate widget cleanup is missing" >&2; exit 1;
}
grep -q 'END_CLIENT_TICK' "$ROOT/src/client/java/dev/zazuzin/zst/WhitelistAutoDeleteEntrypoint.java" || {
  echo "Whitelist global client-tick fallback is missing" >&2; exit 1;
}
grep -q 'WhitelistAutoDeleteEntrypoint.noteAttempt(buttons.endpoint)' "$ROOT/src/client/java/dev/zazuzin/zst/MultiplayerManagementEntrypoint.java" || {
  echo "Manual row-click whitelist attempt capture is missing" >&2; exit 1;
}

# v0.3.50 Finder/provider/supplementary-probe guards. The UI repair above must remain intact while
# discovery can fail over independently of BreakBlocks.
grep -q 'api.cornbread2100.com/v1/servers/random' "$ROOT/src/client/java/dev/zazuzin/zst/ServerFinderClient.java" || {
  echo "Cornbread provider endpoint is missing" >&2; exit 1;
}
grep -q 'data.minescan.xyz/servers/random' "$ROOT/src/client/java/dev/zazuzin/zst/ServerFinderClient.java" || {
  echo "MineScan provider endpoint is missing" >&2; exit 1;
}
grep -q 'SOURCE_LABELS = {"Auto", "All Sources", "BreakBlocks", "Cornbread", "MineScan"}' "$ROOT/src/client/java/dev/zazuzin/zst/ServerFinderClient.java" || {
  echo "Finder source modes are missing or reordered" >&2; exit 1;
}
grep -q 'finderSourceIndex' "$ROOT/src/client/java/dev/zazuzin/zst/ToolState.java" || {
  echo "Finder source selection is not persistent" >&2; exit 1;
}
grep -q 'Source: " + r.source()' "$ROOT/src/client/java/dev/zazuzin/zst/ServerFinderClient.java" || {
  echo "Finder details do not expose the discovery source" >&2; exit 1;
}
grep -q 'BREAKBLOCKS_AGE_OPTIONS = {1, 7, 14, 21, 30}' "$ROOT/src/client/java/dev/zazuzin/zst/ServerFinderClient.java" || {
  echo "BreakBlocks age choices are missing or incorrect" >&2; exit 1;
}
grep -q 'breakBlocksMaxAgeDays = 30' "$ROOT/src/client/java/dev/zazuzin/zst/ToolState.java" || {
  echo "BreakBlocks default age is not 30 days" >&2; exit 1;
}
grep -q 'int page = requestNumber + 1' "$ROOT/src/client/java/dev/zazuzin/zst/ServerFinderClient.java" || {
  echo "BreakBlocks pagination is not 1-based" >&2; exit 1;
}
grep -q 'breakBlocksProgressLabel' "$ROOT/src/client/java/dev/zazuzin/zst/ServerFinderClient.java" || {
  echo "BreakBlocks page/API/live diagnostics are missing" >&2; exit 1;
}
grep -q 'verifyProviderCandidatesTwice' "$ROOT/src/client/java/dev/zazuzin/zst/ServerFinderClient.java" || {
  echo "Strict two-pass Finder verification is missing" >&2; exit 1;
}
grep -q 'SECOND_STATUS_CONFIRM_DELAY_MS = 1_000L' "$ROOT/src/client/java/dev/zazuzin/zst/ServerFinderClient.java" || {
  echo "Second Finder verification delay is missing" >&2; exit 1;
}
grep -q 'VanillaStatusProbe.probe(s.client, s.screen, endpoints' "$ROOT/src/client/java/dev/zazuzin/zst/ServerFinderClient.java" || {
  echo "Finder does not gate candidates on a direct Minecraft status handshake" >&2; exit 1;
}
grep -q 'VanillaStatusProbe.probeOne' "$ROOT/src/client/java/dev/zazuzin/zst/FinderLatencyOverlay.java" || {
  echo "Supplementary row latency probing is missing" >&2; exit 1;
}
grep -q '| VERIFIED' "$ROOT/src/client/java/dev/zazuzin/zst/ServerFinderClient.java" || {
  echo "Double-verified Finder results are not labelled VERIFIED" >&2; exit 1;
}
grep -q 'MAX_IN_FLIGHT = 4' "$ROOT/src/client/java/dev/zazuzin/zst/VanillaStatusProbe.java" || {
  echo "Finder status-client concurrency cap is missing" >&2; exit 1;
}
grep -q 'new ThreadPoolExecutor' "$ROOT/src/client/java/dev/zazuzin/zst/VanillaStatusProbe.java" || {
  echo "Bounded Java status executor is missing" >&2; exit 1;
}
grep -q 'writeHandshake' "$ROOT/src/client/java/dev/zazuzin/zst/VanillaStatusProbe.java" || {
  echo "Minecraft Java status handshake implementation is missing" >&2; exit 1;
}
if grep -qE 'Class\.forName\("net\.minecraft\.client\.multiplayer\.ServerStatusPinger|EventLoopGroupHolder|useNativeTransport|Reflection\.invoke\(pinger' "$ROOT/src/client/java/dev/zazuzin/zst/VanillaStatusProbe.java"; then
  echo "Finder still references Minecraft/ViaFabricPlus native pinger infrastructure" >&2; exit 1;
fi
if [[ -f "$ROOT/src/client/java/dev/zazuzin/zst/MinecraftStatusProbe.java" ]]; then
  echo "Legacy raw MinecraftStatusProbe source is still present" >&2; exit 1;
fi
grep -q 'breakBlocksDnsFailures' "$ROOT/src/client/java/dev/zazuzin/zst/ServerFinderClient.java" || {
  echo "BreakBlocks vanilla-probe failure diagnostics are missing" >&2; exit 1;
}
grep -q 'breakBlocksProbeAttempts' "$ROOT/src/client/java/dev/zazuzin/zst/ServerFinderClient.java" || {
  echo "BreakBlocks probe diagnostics are missing" >&2; exit 1;
}

# v0.3.55 whitelist deletion must remove the live Multiplayer ServerList before
# returning to the Servers tab, otherwise that stale screen can resurrect the row.
grep -q 'removeFromScreenServerList' "$ROOT/src/client/java/dev/zazuzin/zst/ServerListAccess.java" || {
  echo "Live Multiplayer ServerList whitelist deletion is missing" >&2; exit 1;
}
grep -q 'boolean removedLive = ServerListAccess.removeFromScreenServerList' "$ROOT/src/client/java/dev/zazuzin/zst/WhitelistAutoDeleteEntrypoint.java" || {
  echo "Whitelist handler is not deleting from the live Servers-tab source" >&2; exit 1;
}
grep -q 'changed |= RECENT.remove(e)' "$ROOT/src/client/java/dev/zazuzin/zst/ServerCategoryStore.java" || {
  echo "Deleted whitelist servers are not cleared from Recent Servers history" >&2; exit 1;
}
grep -q 'endpointFromAddress' "$ROOT/src/client/java/dev/zazuzin/zst/WhitelistAutoDeleteEntrypoint.java" || {
  echo "ConnectScreen ServerAddress whitelist-attempt fallback is missing" >&2; exit 1;
}
grep -q 'defaultPortIdentity' "$ROOT/src/client/java/dev/zazuzin/zst/ServerListAccess.java" || {
  echo "Whitelist deletion does not treat host and host:25565 as the same endpoint" >&2; exit 1;
}

# v0.3.55 Recent Servers history guards.
grep -q 'enum Tab { FAVOURITES, SERVERS, SCANNED, RECENT }' "$ROOT/src/client/java/dev/zazuzin/zst/ServerCategoryStore.java" || {
  echo "Recent Servers category is missing" >&2; exit 1;
}
grep -q 'MAX_RECENT = 5' "$ROOT/src/client/java/dev/zazuzin/zst/ServerCategoryStore.java" || {
  echo "Recent Servers history is not capped at five" >&2; exit 1;
}
grep -q 'recordSuccessfulJoin(candidate)' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Stable joins are not being recorded in Recent Servers" >&2; exit 1;
}
grep -q 'Recent Servers (' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Recent Servers hub button is missing" >&2; exit 1;
}
grep -q 'recentEndpoints()' "$ROOT/src/client/java/dev/zazuzin/zst/ServerListAccess.java" || {
  echo "Recent Servers are not rebuilt in newest-first order" >&2; exit 1;
}

# v0.3.49 Auto Add is owned by the Finder itself. It must not depend on a separate
# screen tick entrypoint, and completed batches must explicitly schedule the next search.
if [[ -f "$ROOT/src/client/java/dev/zazuzin/zst/ContinuousAutoAddEntrypoint.java" ]]; then
  echo "Legacy tick-based ContinuousAutoAddEntrypoint is still present" >&2; exit 1;
fi
if grep -q 'ContinuousAutoAddEntrypoint' "$ROOT/src/main/resources/fabric.mod.json"; then
  echo "Legacy tick-based Auto Add entrypoint is still registered" >&2; exit 1;
fi
grep -q 'scheduleNextAutoAddBatch' "$ROOT/src/client/java/dev/zazuzin/zst/ServerFinderClient.java" || {
  echo "Finder-owned Auto Add scheduler is missing" >&2; exit 1;
}
grep -q 'AUTO_ADD_BETWEEN_BATCHES_MS = 2_000L' "$ROOT/src/client/java/dev/zazuzin/zst/ServerFinderClient.java" || {
  echo "Auto Add normal batch cadence is not 2 seconds" >&2; exit 1;
}
grep -q 'AUTO_ADD_AFTER_EXHAUSTED_MS = 60_000L' "$ROOT/src/client/java/dev/zazuzin/zst/ServerFinderClient.java" || {
  echo "Auto Add exhausted-pool reset cadence is not 60 seconds" >&2; exit 1;
}
grep -q 'AUTO_ADD_AFTER_FAILURE_MS = 15_000L' "$ROOT/src/client/java/dev/zazuzin/zst/ServerFinderClient.java" || {
  echo "Auto Add provider-failure retry cadence is missing" >&2; exit 1;
}
grep -q 'CompletableFuture.delayedExecutor' "$ROOT/src/client/java/dev/zazuzin/zst/ServerFinderClient.java" || {
  echo "Finder Auto Add does not schedule its next batch directly" >&2; exit 1;
}
grep -q 's.autoAddScheduleToken != token' "$ROOT/src/client/java/dev/zazuzin/zst/ServerFinderClient.java" || {
  echo "Auto Add stale-schedule cancellation guard is missing" >&2; exit 1;
}

# v0.3.52 restores strict Finder admission and cleans stale Scanned Servers.
grep -q 'SCANNED_FAILURES_BEFORE_DELETE = 3' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Scanned Servers three-strike health cleanup is missing" >&2; exit 1;
}
grep -q 'tickScannedHealthCleanup(state)' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Scanned Servers health cleanup is not wired into the Multiplayer tick" >&2; exit 1;
}
grep -q 'Auto-deleted unreachable scanned server' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Scanned Servers health deletion path is missing" >&2; exit 1;
}
grep -q 'stillEligibleForScannedHealthDelete' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Favourite/category safety recheck is missing from scanned health cleanup" >&2; exit 1;
}

# v0.3.51 exposes a separately scoped, confirmation-protected bulk delete in
# Scanned Servers while preserving favourites and established Servers entries.
grep -q 'Delete All Scanned' "$ROOT/src/client/java/dev/zazuzin/zst/MultiplayerManagementEntrypoint.java" || {
  echo "Scanned Servers bulk-delete control is missing" >&2; exit 1;
}
grep -q 'state.scannedDeleteMode != scanned' "$ROOT/src/client/java/dev/zazuzin/zst/MultiplayerManagementEntrypoint.java" || {
  echo "Scanned bulk-delete category boundary is missing" >&2; exit 1;
}
grep -q 'if (isFavourite(server)) continue' "$ROOT/src/client/java/dev/zazuzin/zst/MultiplayerManagementEntrypoint.java" || {
  echo "Bulk delete no longer preserves favourites" >&2; exit 1;
}
grep -q 'Confirm Delete Scanned' "$ROOT/src/client/java/dev/zazuzin/zst/MultiplayerManagementEntrypoint.java" || {
  echo "Scanned bulk-delete confirmation is missing" >&2; exit 1;
}

# v0.3.53 Auto Join authentication rate-limit cooldown guards.
grep -q 'DEFAULT_RATE_LIMIT_COOLDOWN_MS = 10_000L' "$ROOT/src/client/java/dev/zazuzin/zst/AutoJoinEntrypoint.java" || {
  echo "Auto Join default rate-limit cooldown is not 10 seconds" >&2; exit 1;
}
grep -q 'DisconnectReason.isRateLimited' "$ROOT/src/client/java/dev/zazuzin/zst/AutoJoinEntrypoint.java" || {
  echo "Auto Join rate-limit classification is missing" >&2; exit 1;
}
grep -q 'rateLimitCooldownUntil' "$ROOT/src/client/java/dev/zazuzin/zst/AutoJoinEntrypoint.java" || {
  echo "Auto Join rate-limit cooldown gate is missing" >&2; exit 1;
}
grep -q 'rateLimitCooldownSeconds' "$ROOT/src/client/java/dev/zazuzin/zst/AutoJoinEntrypoint.java" || {
  echo "Auto Join rate-limit cooldown setting is missing" >&2; exit 1;
}
grep -q 'ratelimiter' "$ROOT/src/client/java/dev/zazuzin/zst/DisconnectReason.java" || {
  echo "RateLimiter disconnect detection is missing" >&2; exit 1;
}

# v0.3.56 pause-menu favourite control guards.
grep -q '☆ Favourite Server' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Pause-menu Favourite Server button is missing" >&2; exit 1;
}
grep -q '★ Unfavourite Server' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Pause-menu Unfavourite Server state is missing" >&2; exit 1;
}
grep -q 'toggleFavouriteEndpoint' "$ROOT/src/client/java/dev/zazuzin/zst/MultiplayerManagementEntrypoint.java" || {
  echo "Pause-menu favourite persistence path is missing" >&2; exit 1;
}
grep -q 'addServerData(list, server)' "$ROOT/src/client/java/dev/zazuzin/zst/MultiplayerManagementEntrypoint.java" || {
  echo "Direct-connect favourite save path is missing" >&2; exit 1;
}
grep -q 'ServerTabsEntrypoint::onPlayDisconnect' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Connected-server favourite state is not cleared on disconnect" >&2; exit 1;
}

# v0.3.57 favourites are a hard boundary for all automatic removal paths, and
# category Auto Join is available in Servers/Scanned but never Favourites.
grep -q 'Kept favourite after whitelist rejection' "$ROOT/src/client/java/dev/zazuzin/zst/WhitelistAutoDeleteEntrypoint.java" || {
  echo "Whitelist favourite protection is missing" >&2; exit 1;
}
grep -q '!isFavouriteData(data)' "$ROOT/src/client/java/dev/zazuzin/zst/ServerListAccess.java" || {
  echo "Low-level automatic-removal favourite boundary is missing" >&2; exit 1;
}
grep -q 'Kept favourite after failed scanned health checks' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Scanned-health last-moment favourite recheck is missing" >&2; exit 1;
}
grep -q 'view == View.SERVERS || view == View.SCANNED' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Auto Join is not scoped to Servers and Scanned Servers" >&2; exit 1;
}
grep -q 'if (server == null || server.favourite()) return false' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Auto Join favourite exclusion is missing" >&2; exit 1;
}
grep -q 'targetCategory' "$ROOT/src/client/java/dev/zazuzin/zst/AutoJoinEntrypoint.java" || {
  echo "Auto Join category persistence is missing" >&2; exit 1;
}

# v0.3.58 stores favourites by endpoint, exposes Minecraft's server editor in
# the pause menu, and defers health traffic while native status rows populate.
grep -q 'p.setProperty("favourites"' "$ROOT/src/client/java/dev/zazuzin/zst/ServerCategoryStore.java" || {
  echo "Rename-safe favourite persistence is missing" >&2; exit 1;
}
grep -q 'ServerCategoryStore.isFavourite(endpoint)' "$ROOT/src/client/java/dev/zazuzin/zst/ServerListAccess.java" || {
  echo "Category filtering does not use persisted favourite identity" >&2; exit 1;
}
grep -q 'Edit Server Info' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Pause-menu Edit Server Info button is missing" >&2; exit 1;
}
grep -q 'net.minecraft.client.gui.screens.EditServerScreen' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Pause-menu editor is not routed through Minecraft's server editor" >&2; exit 1;
}
grep -q 'ServerCategoryStore.moveEndpoint(originalEndpoint, updatedEndpoint)' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Pause-menu address edits do not migrate category metadata" >&2; exit 1;
}
grep -q 'SCANNED_HEALTH_INITIAL_DELAY_MS = 20_000L' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Initial native status-ping head start is missing" >&2; exit 1;
}
grep -q 'cachedLatencyMillis(endpoint)' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Recent successful status cache is not reused by health cleanup" >&2; exit 1;
}

# v0.3.60 deletion recovery and persisted health state.
grep -q 'zazus-server-tool-backups' "$ROOT/src/client/java/dev/zazuzin/zst/ServerCategoryStore.java" || { echo "Automatic servers.dat backups missing" >&2; exit 1; }
grep -q 'Undo Last Delete' "$ROOT/src/client/java/dev/zazuzin/zst/MultiplayerManagementEntrypoint.java" || { echo "Undo Last Delete control missing" >&2; exit 1; }
grep -q 'recordHealthFailure' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || { echo "Persistent health failure recording missing" >&2; exit 1; }
grep -q 'resetHealth' "$ROOT/src/client/java/dev/zazuzin/zst/MultiplayerManagementEntrypoint.java" || { echo "Health reset control missing" >&2; exit 1; }
grep -q 'visibleAndContains(buttons.health' "$ROOT/src/client/java/dev/zazuzin/zst/MultiplayerManagementEntrypoint.java" || { echo "Health click is not routed through the Multiplayer interceptor" >&2; exit 1; }
grep -q 'visibleAndContains(state.undoButton' "$ROOT/src/client/java/dev/zazuzin/zst/MultiplayerManagementEntrypoint.java" || { echo "Undo click is not routed through the Multiplayer interceptor" >&2; exit 1; }
if grep -qE 'Protected Server|toggleProtected|P✓|isProtectedData' "$ROOT/src/client/java/dev/zazuzin/zst/"*.java; then echo "Removed protection feature is still present" >&2; exit 1; fi

python3 - "$JAR" "$VERSION" <<'PY'
import json, struct, sys, zipfile
from pathlib import PurePosixPath

jar = sys.argv[1]
expected_version = sys.argv[2]
expected_entrypoints = {
    "dev.zazuzin.zst.MultiplayerManagementEntrypoint",
    "dev.zazuzin.zst.WhitelistAutoDeleteEntrypoint",
    "dev.zazuzin.zst.AutoJoinEntrypoint",
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
    if meta.get("version") != expected_version:
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

        Path root = Files.createTempDirectory("zst-api-key-test-");
        System.setProperty("user.dir", root.toString());

        ServerFinderClient.OverlayState finder = new ServerFinderClient.OverlayState(null, null, 0, 0);
        finder.versionIndex = 1;
        finder.minIndex = 0;
        finder.maxIndex = 7;
        finder.serverTypeIndex = 0;
        finder.sortIndex = 0;
        ToolState.breakBlocksMaxAgeDays = 30;
        String firstPage = ServerFinderClient.buildBreakBlocksPageUri(finder, 1).toString();
        if (!firstPage.contains("page=1")) throw new AssertionError("BreakBlocks first page is not page=1: " + firstPage);
        if (!firstPage.contains("maxAge=30")) throw new AssertionError("BreakBlocks default age is not 30 days: " + firstPage);
        for (int age : new int[] {1, 7, 14, 21, 30}) {
            ToolState.breakBlocksMaxAgeDays = age;
            String u = ServerFinderClient.buildBreakBlocksPageUri(finder, 2).toString();
            if (!u.contains("page=2") || !u.contains("maxAge=" + age)) {
                throw new AssertionError("BreakBlocks age/page URI regression for " + age + " days: " + u);
            }
        }
        ToolState.breakBlocksMaxAgeDays = 999;
        String invalidAge = ServerFinderClient.buildBreakBlocksPageUri(finder, 0).toString();
        if (!invalidAge.contains("page=1") || !invalidAge.contains("maxAge=30")) {
            throw new AssertionError("Invalid BreakBlocks age/page was not safely normalized: " + invalidAge);
        }

        // ToolState must create a blank key setting, pick up a manually edited
        // key, and preserve that key when unrelated settings are subsequently saved.
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
        ToolState.finderSourceIndex = 4;
        ToolState.breakBlocksMaxAgeDays = 21;
        ToolState.save();

        Properties after = new Properties();
        try (var reader = Files.newBufferedReader(config, StandardCharsets.UTF_8)) { after.load(reader); }
        if (!fakeKey.equals(after.getProperty("breakBlocksApiKey"))) {
            throw new AssertionError("Unrelated config save overwrote the API key");
        }
        if (!"4".equals(after.getProperty("finderSourceIndex"))) {
            throw new AssertionError("Finder source selection was not persisted");
        }
        if (!"21".equals(after.getProperty("breakBlocksMaxAgeDays"))) {
            throw new AssertionError("BreakBlocks age selection was not persisted");
        }
    }
}
JAVA
javac --release 21 -cp "$JAR" -d "$API_TEST_DIR" "$API_TEST_DIR/dev/zazuzin/zst/ApiKeyRequestTest.java"
java -Duser.dir="$API_TEST_DIR" -cp "$JAR:$API_TEST_DIR" dev.zazuzin.zst.ApiKeyRequestTest

echo "BreakBlocks optional-authentication regression tests passed."

PROVIDER_TEST_DIR="$(mktemp -d)"
mkdir -p "$PROVIDER_TEST_DIR/dev/zazuzin/zst"
cat > "$PROVIDER_TEST_DIR/dev/zazuzin/zst/ProviderParsingTest.java" <<'JAVA'
package dev.zazuzin.zst;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;

public final class ProviderParsingTest {
    public static void main(String[] args) {
        String cornbread = """
            {"data":[{"ip":16909060,"port":25570,"version":{"name":"26.2","protocol":999},"players":{"online":3,"max":20},"cracked":true,"whitelisted":false,"description":"Cornbread test","lastSeen":"1770000000"}]}
            """;
        List<ServerFinderClient.ServerRecord> c = ServerFinderClient.parseCornbreadResult(cornbread);
        if (c.size() != 1) throw new AssertionError("Cornbread sample did not parse");
        var cr = c.get(0);
        if (!"1.2.3.4".equals(cr.address()) || cr.port() != 25570)
            throw new AssertionError("Cornbread IPv4/port mapping failed: " + cr.endpoint());
        if (!"26.2".equals(cr.version()) || cr.playersOnline() != 3 || cr.playersMax() != 20)
            throw new AssertionError("Cornbread version/player mapping failed");
        if (!cr.offlineMode() || cr.whitelisted() || !"Cornbread".equals(cr.source()))
            throw new AssertionError("Cornbread auth/source mapping failed");

        String minescan = """
            {"servers":[{"serverip":"5.6.7.8","port":25565,"version":"26.2","authmode":"whitelist","motd":"MineScan test","onlinePlayers":7,"maxPlayers":50,"lastSeen":"2026-08-23T12:00:00Z"}]}
            """;
        List<ServerFinderClient.ServerRecord> m = ServerFinderClient.parseMineScanResult(minescan);
        if (m.size() != 1) throw new AssertionError("MineScan sample did not parse");
        var mr = m.get(0);
        if (!"5.6.7.8".equals(mr.address()) || mr.playersOnline() != 7 || mr.playersMax() != 50)
            throw new AssertionError("MineScan address/player mapping failed");
        if (!mr.whitelisted() || mr.offlineMode() || !"MineScan".equals(mr.source()))
            throw new AssertionError("MineScan auth/source mapping failed");

        HttpRequest provider = ServerFinderClient.buildProviderRequest(URI.create("https://example.invalid/servers"));
        if (provider.headers().firstValue("Authorization").isPresent())
            throw new AssertionError("No-key provider unexpectedly received Authorization header");
        String ua = provider.headers().firstValue("User-Agent").orElse("");
        if (!"ZazusServerTool/0.3.63".equals(ua))
            throw new AssertionError("Provider User-Agent version mismatch: " + ua);

        if (!"1.2.3.4".equals(ServerFinderClient.intToIpv4(16909060L)))
            throw new AssertionError("Unsigned IPv4 conversion regression");
    }
}
JAVA
javac --release 21 -cp "$JAR" -d "$PROVIDER_TEST_DIR" "$PROVIDER_TEST_DIR/dev/zazuzin/zst/ProviderParsingTest.java"
java -Duser.dir="$PROVIDER_TEST_DIR" -cp "$JAR:$PROVIDER_TEST_DIR" dev.zazuzin.zst.ProviderParsingTest
rm -rf "$PROVIDER_TEST_DIR"
echo "Multi-provider parsing/request regression tests passed."

PROBE_TEST_DIR="$(mktemp -d)"
mkdir -p "$PROBE_TEST_DIR/dev/zazuzin/zst" "$PROBE_TEST_DIR/net/minecraft"
cat > "$PROBE_TEST_DIR/net/minecraft/SharedConstants.java" <<'JAVA'
package net.minecraft;
public final class SharedConstants {
    private static final WorldVersion VERSION = new WorldVersion();
    public static WorldVersion getCurrentVersion() { return VERSION; }
    public static final class WorldVersion { public int protocolVersion() { return 776; } }
}
JAVA
cat > "$PROBE_TEST_DIR/dev/zazuzin/zst/VanillaStatusProbeTest.java" <<'JAVA'
package dev.zazuzin.zst;
import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class VanillaStatusProbeTest {
    static final class Client {
        public void execute(Runnable task) { task.run(); }
    }

    public static void main(String[] args) throws Exception {
        try (StatusServer server = new StatusServer()) {
            int deadPort;
            try (ServerSocket unused = new ServerSocket(0)) { deadPort = unused.getLocalPort(); }

            List<String> endpoints = new ArrayList<>();
            for (int i = 1; i <= 8; i++) endpoints.add("127.0.0." + i + ":" + server.port());
            endpoints.add("127.0.0.1:" + deadPort);
            endpoints.add("no-such-zazu-host.invalid:25565");

            var results = VanillaStatusProbe.probe(new Client(), new Object(), endpoints, () -> true)
                    .get(12, TimeUnit.SECONDS);
            if (results.size() != endpoints.size()) throw new AssertionError("Unexpected status result count: " + results);
            for (int i = 0; i < 8; i++) {
                var live = results.get(i);
                if (!live.replied() || live.protocol() != 776 || !"26.2 Test".equals(live.version()) || live.latencyMs() < 1L)
                    throw new AssertionError("Java status reply was not captured: " + live);
            }
            if (results.get(8).failure() != VanillaStatusProbe.Failure.UNREACHABLE)
                throw new AssertionError("Unreachable classification failed: " + results.get(8));
            if (results.get(9).failure() != VanillaStatusProbe.Failure.DNS)
                throw new AssertionError("DNS classification failed: " + results.get(9));
            if (server.maxActive() > 4)
                throw new AssertionError("Status client exceeded four in-flight requests: " + server.maxActive());
            if (server.maxActive() < 2)
                throw new AssertionError("Status client did not exercise concurrent requests");
            if (VanillaStatusProbe.currentProtocol() != 776)
                throw new AssertionError("Minecraft current protocol reflection failed");
            if (VanillaStatusProbe.cachedLatencyMillis(endpoints.get(0)) < 1L)
                throw new AssertionError("Status latency cache was not populated");
        }
    }

    static final class StatusServer implements AutoCloseable {
        private final ServerSocket server;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maxActive = new AtomicInteger();
        private final List<Thread> handlers = new CopyOnWriteArrayList<>();

        StatusServer() throws IOException {
            server = new ServerSocket(0);
            Thread accept = new Thread(this::acceptLoop, "status-test-accept");
            accept.setDaemon(true);
            accept.start();
        }

        int port() { return server.getLocalPort(); }
        int maxActive() { return maxActive.get(); }

        private void acceptLoop() {
            while (running.get()) {
                try {
                    Socket socket = server.accept();
                    Thread handler = new Thread(() -> handle(socket), "status-test-handler");
                    handler.setDaemon(true);
                    handlers.add(handler);
                    handler.start();
                } catch (IOException closed) {
                    if (running.get()) throw new RuntimeException(closed);
                }
            }
        }

        private void handle(Socket socket) {
            int now = active.incrementAndGet();
            maxActive.accumulateAndGet(now, Math::max);
            try (socket) {
                socket.setSoTimeout(5_000);
                InputStream input = socket.getInputStream();
                OutputStream output = socket.getOutputStream();
                readPacket(input); // handshake
                readPacket(input); // status request
                Thread.sleep(100L);
                String json = "{\"version\":{\"name\":\"26.2 Test\",\"protocol\":776},\"players\":{\"max\":20,\"online\":1},\"description\":\"test\"}";
                ByteArrayOutputStream response = new ByteArrayOutputStream();
                writeVarInt(response, 0);
                byte[] jsonBytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                writeVarInt(response, jsonBytes.length);
                response.write(jsonBytes);
                writePacket(output, response.toByteArray());
                output.flush();

                byte[] ping = readPacket(input);
                writePacket(output, ping);
                output.flush();
            } catch (Exception failure) {
                throw new RuntimeException(failure);
            } finally {
                active.decrementAndGet();
            }
        }

        private static byte[] readPacket(InputStream input) throws IOException {
            int length = readVarInt(input);
            return input.readNBytes(length);
        }

        private static int readVarInt(InputStream input) throws IOException {
            int value = 0;
            for (int position = 0; position < 5; position++) {
                int current = input.read();
                if (current < 0) throw new EOFException();
                value |= (current & 0x7f) << (position * 7);
                if ((current & 0x80) == 0) return value;
            }
            throw new IOException("VarInt too large");
        }

        private static void writePacket(OutputStream output, byte[] body) throws IOException {
            writeVarInt(output, body.length);
            output.write(body);
        }

        private static void writeVarInt(OutputStream output, int value) throws IOException {
            do {
                int current = value & 0x7f;
                value >>>= 7;
                if (value != 0) current |= 0x80;
                output.write(current);
            } while (value != 0);
        }

        public void close() throws Exception {
            running.set(false);
            server.close();
            for (Thread handler : handlers) handler.join(2_000L);
        }
    }
}
JAVA
javac --release 21 -cp "$JAR" -d "$PROBE_TEST_DIR" $(find "$PROBE_TEST_DIR" -name '*.java' -type f | sort)
java -Duser.dir="$PROBE_TEST_DIR" -cp "$JAR:$PROBE_TEST_DIR" dev.zazuzin.zst.VanillaStatusProbeTest
rm -rf "$PROBE_TEST_DIR"
echo "Bounded pure-Java Minecraft status-client regression test passed."

DISCONNECT_TEST_DIR="$(mktemp -d)"
mkdir -p "$DISCONNECT_TEST_DIR/dev/zazuzin/zst"
cat > "$DISCONNECT_TEST_DIR/dev/zazuzin/zst/DisconnectReasonTest.java" <<'JAVA'
package dev.zazuzin.zst;

public final class DisconnectReasonTest {
    static final class FakeComponent {
        private final String text;
        FakeComponent(String text) { this.text = text; }
        public String getString() { return text; }
    }
    record FakeDetails(FakeComponent reason) {}
    static final class FakeDisconnectedScreen {
        final FakeDetails disconnectionDetails;
        FakeDisconnectedScreen(String reason) { this.disconnectionDetails = new FakeDetails(new FakeComponent(reason)); }
    }

    public static void main(String[] args) {
        FakeDisconnectedScreen screen = new FakeDisconnectedScreen("You are not white-listed on this server!");
        String extracted = DisconnectReason.extract(screen);
        if (!DisconnectReason.isWhitelistRejection(extracted)) {
            throw new AssertionError("Whitelist reason was not detected: " + extracted);
        }
        if (!DisconnectReason.isWhitelistRejection("You are not whitelisted on this server")) {
            throw new AssertionError("Unhyphenated whitelist text was not detected");
        }
        if (DisconnectReason.isWhitelistRejection("Connection timed out")) {
            throw new AssertionError("Ordinary timeout was misclassified as whitelist rejection");
        }
        if (!DisconnectReason.isRateLimited("Failed to log in: RateLimiter disallowed request")) {
            throw new AssertionError("RateLimiter disconnect was not detected");
        }
        if (!DisconnectReason.isRateLimited("Too many requests")) {
            throw new AssertionError("Generic rate-limit text was not detected");
        }
        if (DisconnectReason.isRateLimited("Connection timed out")) {
            throw new AssertionError("Ordinary timeout was misclassified as rate-limited");
        }
    }
}
JAVA
javac --release 21 -cp "$JAR" -d "$DISCONNECT_TEST_DIR" "$DISCONNECT_TEST_DIR/dev/zazuzin/zst/DisconnectReasonTest.java"
java -Duser.dir="$DISCONNECT_TEST_DIR" -cp "$JAR:$DISCONNECT_TEST_DIR" dev.zazuzin.zst.DisconnectReasonTest
rm -rf "$DISCONNECT_TEST_DIR"
echo "Disconnect/whitelist/rate-limit regression tests passed."

RUNTIME_TEST_DIR="$(mktemp -d)"
mkdir -p "$RUNTIME_TEST_DIR/dev/zazuzin/zst" "$RUNTIME_TEST_DIR/net/minecraft/client/multiplayer" "$RUNTIME_TEST_DIR/net/fabricmc/api"
cat > "$RUNTIME_TEST_DIR/net/fabricmc/api/ClientModInitializer.java" <<'JAVA'
package net.fabricmc.api;
public interface ClientModInitializer { void onInitializeClient(); }
JAVA
cat > "$RUNTIME_TEST_DIR/net/minecraft/client/multiplayer/ServerData.java" <<'JAVA'
package net.minecraft.client.multiplayer;
public final class ServerData {
    public String name;
    public String ip;
    public ServerData(String name, String ip) { this.name = name; this.ip = ip; }
}
JAVA
cat > "$RUNTIME_TEST_DIR/net/minecraft/client/multiplayer/ServerList.java" <<'JAVA'
package net.minecraft.client.multiplayer;
import java.util.*;
public final class ServerList {
    public static final List<ServerData> persistedVisible = new ArrayList<>();
    public static final List<ServerData> persistedHidden = new ArrayList<>();
    public List<ServerData> servers = new ArrayList<>();
    public List<ServerData> hiddenServers = new ArrayList<>();
    public ServerList() {}
    public void load() { servers = new ArrayList<>(persistedVisible); hiddenServers = new ArrayList<>(persistedHidden); }
    public void save() { persistedVisible.clear(); persistedVisible.addAll(servers); persistedHidden.clear(); persistedHidden.addAll(hiddenServers); }
    public int size() { return servers.size(); }
    public ServerData get(int i) { return servers.get(i); }
    public void add(ServerData data) { servers.add(data); }
    public void remove(ServerData data) { servers.remove(data); }
}
JAVA
cat > "$RUNTIME_TEST_DIR/dev/zazuzin/zst/RuntimeRegressionTest.java" <<'JAVA'
package dev.zazuzin.zst;

import java.util.*;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;

public final class RuntimeRegressionTest {
    static final class FakeScreen {
        final List<Object> children = new ArrayList<>();
        final List<Object> renderables = new ArrayList<>();
        final List<Object> narratables = new ArrayList<>();
        public void removeWidget(Object widget) { children.remove(widget); }
    }

    public static void main(String[] args) {
        Object ghost = new Object();
        FakeScreen screen = new FakeScreen();
        screen.children.add(ghost); screen.renderables.add(ghost); screen.narratables.add(ghost);
        Reflection.removeWidget(screen, ghost);
        if (screen.children.contains(ghost) || screen.renderables.contains(ghost) || screen.narratables.contains(ghost)) {
            throw new AssertionError("removeWidget left a ghost widget in a Screen list");
        }

        ServerList.persistedVisible.clear();
        ServerList.persistedHidden.clear();
        ServerList.persistedVisible.add(new ServerData("★ Favourite", "visible.example:25565"));
        ServerList.persistedHidden.add(new ServerData("Hidden", "hidden.example:25565"));
        if (ServerListAccess.forceRemove(null, "visible.example:25565")) {
            throw new AssertionError("Automatic forceRemove deleted a favourite server");
        }
        if (!ServerListAccess.isFavouriteEndpoint(null, null, "visible.example:25565")) {
            throw new AssertionError("Persisted favourite safety check did not recognize the server");
        }
        if (!ServerListAccess.forceRemove(null, "hidden.example:25565")) {
            throw new AssertionError("Whitelist forceRemove failed for hidden ServerList backing list");
        }
        if (ServerList.persistedVisible.size() != 1 || !ServerList.persistedHidden.isEmpty()) {
            throw new AssertionError("Automatic removal did not preserve only the favourite entry");
        }

        ServerList.persistedVisible.add(new ServerData("Undo Test", "undo.example:25565"));
        if (!ServerListAccess.forceRemove(null, "undo.example:25565")) throw new AssertionError("Undo test server was not deleted");
        if (!ServerCategoryStore.undoLastDelete(null)) throw new AssertionError("Undo Last Delete failed");
        if (ServerList.persistedVisible.stream().noneMatch(s -> s.ip.equals("undo.example:25565")))
            throw new AssertionError("Undo did not restore deleted server");

        if (ServerCategoryStore.recordHealthFailure("health.example:25565") != 1) throw new AssertionError("Health failure not recorded");
        ServerCategoryStore.recordHealthSuccess("health.example:25565");
        if (ServerCategoryStore.healthFailures("health.example:25565") != 0) throw new AssertionError("Health success did not reset failures");

        String oldEndpoint = "old-edit.example:25565", newEndpoint = "new-edit.example:25565";
        ServerCategoryStore.markScanned(oldEndpoint);
        ServerCategoryStore.setFavourite(oldEndpoint, true);
        ServerCategoryStore.recordHealthFailure(oldEndpoint);
        ServerCategoryStore.recordSuccessfulJoin(oldEndpoint);
        ServerCategoryStore.moveEndpoint(oldEndpoint, newEndpoint);
        if (ServerCategoryStore.isScanned(oldEndpoint) || ServerCategoryStore.isFavourite(oldEndpoint)
                || ServerCategoryStore.isRecent(oldEndpoint) || ServerCategoryStore.healthFailures(oldEndpoint) != 0)
            throw new AssertionError("Old endpoint metadata remained after edit");
        if (!ServerCategoryStore.isScanned(newEndpoint) || !ServerCategoryStore.isFavourite(newEndpoint)
                || !ServerCategoryStore.isRecent(newEndpoint) || ServerCategoryStore.healthFailures(newEndpoint) != 1)
            throw new AssertionError("Edited endpoint did not inherit category metadata");

        FakeMouseEvent mouse = new FakeMouseEvent();
        FakeClickable button = new FakeClickable();
        if (!ServerTabsEntrypoint.dispatchWidgetClick(button, mouse) || !button.clicked) {
            throw new AssertionError("Custom button dispatch did not use mouseClicked(MouseButtonEvent, boolean) semantics");
        }
        if (ServerTabsEntrypoint.dispatchWidgetClick(new Object(), mouse)) {
            throw new AssertionError("Failed custom-button dispatch was incorrectly treated as consumed");
        }
    }

    static final class FakeMouseEvent {}
    static final class FakeClickable {
        boolean clicked;
        public boolean mouseClicked(FakeMouseEvent event, boolean doubleClick) { clicked = true; return true; }
    }
}
JAVA
javac --release 21 -cp "$JAR" -d "$RUNTIME_TEST_DIR"   "$RUNTIME_TEST_DIR/net/fabricmc/api/ClientModInitializer.java"   "$RUNTIME_TEST_DIR/net/minecraft/client/multiplayer/ServerData.java"   "$RUNTIME_TEST_DIR/net/minecraft/client/multiplayer/ServerList.java"   "$RUNTIME_TEST_DIR/dev/zazuzin/zst/RuntimeRegressionTest.java"
java -Duser.dir="$RUNTIME_TEST_DIR" -cp "$JAR:$RUNTIME_TEST_DIR" dev.zazuzin.zst.RuntimeRegressionTest
rm -rf "$RUNTIME_TEST_DIR"
echo "Widget-removal and persisted whitelist-deletion regression tests passed."

echo "Verification passed: $JAR"
