#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
property() { sed -n "s/^$1=//p" "$ROOT/gradle.properties" | tail -n 1; }
VERSION="$(property mod_version)"
MC_VERSION="$(property minecraft_version)"
test -s "$ROOT/LICENSE" || { echo "LICENSE is missing or empty" >&2; exit 1; }
grep -q 'GNU GENERAL PUBLIC LICENSE' "$ROOT/LICENSE" || { echo "GPL-3.0 license text is missing" >&2; exit 1; }
grep -q '"license": "GPL-3.0-only"' "$ROOT/src/main/resources/fabric.mod.json" || {
  echo "Fabric GPL-3.0-only metadata is missing" >&2; exit 1;
}
JAR="${1:-$ROOT/build/libs/Zazus-Server-Seeker-${VERSION}-mc${MC_VERSION}.jar}"
[[ -f "$JAR" ]] || { echo "JAR not found: $JAR" >&2; exit 1; }
JAR="$(cd "$(dirname "$JAR")" && pwd)/$(basename "$JAR")"

# Minecraft 26.2's development artifact is already non-obfuscated. Applying
# Mojang mappings or remapJar makes Loom reject the build configuration.
if grep -q 'officialMojangMappings' "$ROOT/build.gradle"; then
  echo "Minecraft 26.2 build must not configure Mojang mappings" >&2; exit 1
fi
if grep -q 'remapJar' "$ROOT/build.gradle"; then
  echo "Minecraft 26.2 build must package the normal jar task" >&2; exit 1
fi
grep -q '^jar {' "$ROOT/build.gradle" || {
  echo "Minecraft 26.2 release jar task is not configured" >&2; exit 1;
}

grep -q 'undoLastDeleteButton' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Undo Last Delete is not category-managed" >&2; exit 1;
}
grep -q 'setVisibleActive(undoLastDelete, bulkDeleteView' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Undo Last Delete visibility is not restricted to Servers/Scanned Servers" >&2; exit 1;
}
grep -q 'changeResultPage(s, -1)' "$ROOT/src/client/java/dev/zazuzin/zst/ServerFinderClient.java" || {
  echo "Finder Previous-page navigation is missing" >&2; exit 1;
}
grep -q 'changeResultPage(s, 1)' "$ROOT/src/client/java/dev/zazuzin/zst/ServerFinderClient.java" || {
  echo "Finder Next-page navigation is missing" >&2; exit 1;
}
grep -q 'for (ServerRecord record : s.results) accumulated.put' "$ROOT/src/client/java/dev/zazuzin/zst/ServerFinderClient.java" || {
  echo "Finder does not retain verified results between batches" >&2; exit 1;
}
grep -Eq 'listBottom = Math.min\(listBottom, footerTop - 4\)|cachedListBottom = Math.min\(rawBottom, vanillaFooterTop\(state\) - 4\)' "$ROOT/src/client/java/dev/zazuzin/zst/MultiplayerManagementEntrypoint.java" || {
  echo "Per-server controls are not constrained above Minecraft's footer" >&2; exit 1;
}

# Performance regression guards added in beta.5.
grep -q 'FIELD_CACHE = new ConcurrentHashMap' "$ROOT/src/client/java/dev/zazuzin/zst/Reflection.java" || {
  echo "Reflection field cache is missing" >&2; exit 1;
}
grep -q 'METHOD_CANDIDATE_CACHE = new ConcurrentHashMap' "$ROOT/src/client/java/dev/zazuzin/zst/Reflection.java" || {
  echo "Reflection method cache is missing" >&2; exit 1;
}
grep -q 'SAVED_REFRESH_INTERVAL_MS = 500L' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Saved-server maintenance throttling is missing" >&2; exit 1;
}
grep -q 'lastAuthTooltip' "$ROOT/src/client/java/dev/zazuzin/zst/MultiplayerManagementEntrypoint.java" || {
  echo "Row tooltip change cache is missing" >&2; exit 1;
}
grep -q 'if (ticksUntilPoll-- > 0) return;' "$ROOT/src/client/java/dev/zazu/servernotes/service/PlayerTrackingService.java" || {
  echo "Server Notes player polling throttle is missing" >&2; exit 1;
}

grep -q 'b -> recheckAuth(state, sb)' "$ROOT/src/client/java/dev/zazuzin/zst/MultiplayerManagementEntrypoint.java" || {
  echo "Saved-server auth control is not wired to manual recheck" >&2; exit 1;
}
grep -q 'visibleAndContains(buttons.auth, x, y)' "$ROOT/src/client/java/dev/zazuzin/zst/MultiplayerManagementEntrypoint.java" || {
  echo "Saved-server auth control is missing from row mouse interception" >&2; exit 1;
}
grep -q 'ServerAuthService.recheckAsync' "$ROOT/src/client/java/dev/zazuzin/zst/MultiplayerManagementEntrypoint.java" || {
  echo "Manual auth recheck does not call ServerAuthService" >&2; exit 1;
}
grep -q 'Component.literal("Copy")' "$ROOT/src/client/java/dev/zazu/servernotes/ui/ServerNotesScreen.java" || {
  echo "Integrated Server Notes player-name Copy control is missing" >&2; exit 1;
}
grep -q 'ClipboardCompat.copy(record.username())' "$ROOT/src/client/java/dev/zazu/servernotes/ui/ServerNotesScreen.java" || {
  echo "Integrated Server Notes player-name copy action is missing" >&2; exit 1;
}
grep -q 'new ServerNotesScreen(this, profileKey, true, 0)' "$ROOT/src/client/java/dev/zazu/servernotes/ui/ServerNotesScreen.java" || {
  echo "Players navigation is not routed through the integrated ServerNotesScreen view" >&2; exit 1;
}
if grep -q 'playerTracking()' "$ROOT/src/client/java/dev/zazu/servernotes/ui/ServerNotesScreen.java"; then
  echo "Integrated Server Notes Players view still depends on live online status" >&2
  exit 1
fi
if [[ -e "$ROOT/src/client/java/dev/zazu/servernotes/ui/PlayersListScreen.java" ]]; then
  echo "Obsolete standalone PlayersListScreen remains in the RC source" >&2; exit 1;
fi
for obsolete in \
    "$ROOT/src/client/java/dev/zazu/servernotes/ui/TagInputScreen.java" \
    "$ROOT/src/client/java/dev/zazu/servernotes/ui/TagsScreen.java" \
    "$ROOT/src/client/java/dev/zazu/servernotes/service/ServerTagService.java" \
    "$ROOT/src/client/java/dev/zazu/servernotes/service/ServerProfileService.java"; do
  if [[ -e "$obsolete" ]]; then
    echo "Removed Server Notes UI/service code remains in the RC source: $obsolete" >&2
    exit 1
  fi
done
if grep -Eq 'Component\.literal\("Tags"\)|Tags: |Important' \
    "$ROOT/src/client/java/dev/zazu/servernotes/ui/ServerNotesScreen.java"; then
  echo "Removed Tags/Important controls remain on the Server Notes overview" >&2; exit 1
fi
grep -q 'AutoJoinFailureNotes.record' "$ROOT/src/client/java/dev/zazuzin/zst/AutoJoinEntrypoint.java" || {
  echo "Auto Join failures are not persisted into Server Notes" >&2; exit 1;
}
if grep -q 'categoriesButton' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java"; then
  echo "Redundant category-screen Categories button remains" >&2; exit 1
fi
grep -q 'ServerListAccess.synchronizeServerName(state.screen, sb.endpoint, updated)' \
    "$ROOT/src/client/java/dev/zazuzin/zst/MultiplayerManagementEntrypoint.java" || {
  echo "Favourite toggles do not synchronize every live ServerData copy" >&2; exit 1;
}
if jar tf "$JAR" | grep -q 'dev/zazu/servernotes/ui/PlayersListScreen.class'; then
  echo "Obsolete standalone PlayersListScreen was packaged" >&2; exit 1;
fi
if jar tf "$JAR" | grep -Eq 'dev/zazu/servernotes/(ui/(TagInputScreen|TagsScreen)|service/(ServerTagService|ServerProfileService))\.class'; then
  echo "Removed Server Notes UI/service classes were packaged" >&2; exit 1
fi
grep -q 'map.put("tags", new ArrayList<>(tags))' "$ROOT/src/client/java/dev/zazu/servernotes/model/ServerProfile.java" || {
  echo "Hidden Server Notes tags are no longer preserved in schema-v2 output" >&2; exit 1;
}
grep -q 'map.put("favourite", favourite)' "$ROOT/src/client/java/dev/zazu/servernotes/model/ServerProfile.java" || {
  echo "Hidden Server Notes Important/favourite value is no longer preserved" >&2; exit 1;
}
grep -q 'registerBeforeExtract(state)' "$ROOT/src/client/java/dev/zazuzin/zst/MultiplayerManagementEntrypoint.java" || {
  echo "Per-frame row alignment registration is missing" >&2; exit 1;
}
grep -q 'updateRowPositionsOnly(state)' "$ROOT/src/client/java/dev/zazuzin/zst/MultiplayerManagementEntrypoint.java" || {
  echo "Per-frame row geometry update is missing" >&2; exit 1;
}
grep -Fq 'VisibleRange visible = visibleRange(state, listWidget)' "$ROOT/src/client/java/dev/zazuzin/zst/MultiplayerManagementEntrypoint.java" || {
  echo "Visible-window row geometry limit is missing" >&2; exit 1;
}
grep -Fq 'for (int i = Math.max(0, first); i <= last && i < state.serverButtons.size(); i++)' "$ROOT/src/client/java/dev/zazuzin/zst/MultiplayerManagementEntrypoint.java" || {
  echo "Row controls are still iterating outside the visible-window candidate range" >&2; exit 1;
}
if grep -Fq 'for (int i = 0; i < state.serverButtons.size(); i++)' "$ROOT/src/client/java/dev/zazuzin/zst/MultiplayerManagementEntrypoint.java"; then
  echo "Full-list per-frame/tick row-control scan has returned" >&2; exit 1
fi
grep -q 'ensureRowControls(state, sb)' "$ROOT/src/client/java/dev/zazuzin/zst/MultiplayerManagementEntrypoint.java" || {
  echo "Viewport-lazy row-control creation is missing" >&2; exit 1;
}
grep -q 'currentRowTop(listWidget, row.entry, middle, state.cachedListTop)' "$ROOT/src/client/java/dev/zazuzin/zst/MultiplayerManagementEntrypoint.java" || {
  echo "Authoritative-coordinate visible-row lookup is missing" >&2; exit 1;
}
grep -q 'materializeNextRowControl(state)' "$ROOT/src/client/java/dev/zazuzin/zst/MultiplayerManagementEntrypoint.java" || {
  echo "Bounded fallback row-control materialization is missing" >&2; exit 1;
}
if grep -q 'sortSavedServersFavouritesFirst(client)' "$ROOT/src/client/java/dev/zazuzin/zst/MultiplayerManagementEntrypoint.java"; then
  echo "Synchronous servers.dat sorting has returned to Multiplayer setup" >&2; exit 1
fi
grep -q 'MultiplayerManagementEntrypoint.clearRowButtons(state.screen)' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Category hub is not clearing hidden row controls" >&2; exit 1;
}
if grep -q 'restoreFullRows(state)' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java"; then
  echo "Category hub is redundantly rebuilding the full server list" >&2; exit 1
fi

unzip -t "$JAR" >/dev/null
python3 "$ROOT/tools/notes_ui_audit.py" "$JAR"

jar tf "$JAR" | grep -qx 'assets/zazus-server-tool/icon.png' || {
  echo "Zazu's Server Seeker logo is missing from the JAR" >&2; exit 1;
}

MOD_JSON="$(unzip -p "$JAR" fabric.mod.json)"
grep -q '"homepage": "https://github.com/Zazuzin/Zazus-Server-Seeker"' <<<"$MOD_JSON" || {
  echo "Official project homepage is missing from Fabric metadata" >&2; exit 1;
}
grep -q '"sources": "https://github.com/Zazuzin/Zazus-Server-Seeker"' <<<"$MOD_JSON" || {
  echo "Official source repository is missing from Fabric metadata" >&2; exit 1;
}
grep -q '"issues": "https://github.com/Zazuzin/Zazus-Server-Seeker/issues"' <<<"$MOD_JSON" || {
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

# Multiplayer regression guards. Category switching uses Minecraft's in-memory
# ServerSelectionList rebuild path and keeps screen ownership compatible with 26.2.
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
if grep -qE 'state\.(refreshButton|backButton) = makeButton' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java"; then
  echo "Replacement footer buttons still overlap Minecraft's originals" >&2; exit 1;
fi
grep -Eq 'Math.max\(rowRight \+ 16, (scrollbarX|state\.cachedScrollbarX) \+ 16\)' "$ROOT/src/client/java/dev/zazuzin/zst/MultiplayerManagementEntrypoint.java" || {
  echo "Favourite/Delete controls are not anchored beside the scrollbar" >&2; exit 1;
}
grep -q 'isNativeBackWidget' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Hub is not preserving Minecraft's native Back button" >&2; exit 1;
}
grep -q 'removeNativeRefreshControls(state)' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Native Refresh removal is missing" >&2; exit 1;
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
grep -q 'refreshCategoryInPlace(state)' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Refresh does not reload the active category in place" >&2; exit 1;
}
grep -q 'ServerListAccess.reloadCategory(state.client, state.screen' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Category Refresh does not reload servers.dat directly" >&2; exit 1;
}
if grep -A20 'private static void refreshCategoryInPlace' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" | grep -q 'invokeNoArg.*refreshServerList'; then
  echo "Category Refresh still invokes Minecraft's screen-rebuilding refreshServerList" >&2; exit 1;
fi
grep -q 'SCREEN_ROUTES.put(categoryScreen, new ScreenRoute(view, hub))' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Separate category-screen route registration is missing" >&2; exit 1;
}
grep -q 'newMultiplayerScreen(state.screen.getClass(), hub)' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Category screens are not parented to the category hub" >&2; exit 1;
}
grep -q 'previous != null && route == null && hubScreen == screen' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Returning category hub does not reload servers.dat" >&2; exit 1;
}
grep -q 'state.layoutDirty = true' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Live window-resize layout invalidation is missing" >&2; exit 1;
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
grep -q 'ServerTabsEntrypoint.noteConnectionAttempt(normalized)' "$ROOT/src/client/java/dev/zazuzin/zst/WhitelistAutoDeleteEntrypoint.java" || {
  echo "Connection attempts are not shared with scanned-server promotion" >&2; exit 1;
}
grep -A35 'private static void onPlayJoin' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" | grep -q 'currentServerEndpoint(client)' || {
  echo "Stable-join promotion does not prefer Minecraft's live endpoint" >&2; exit 1;
}
if grep -A45 'private static void onPlayJoin' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" | grep -q 'final boolean scannedCandidate'; then
  echo "Stable-join promotion still snapshots stale category state" >&2; exit 1;
fi
grep -q 'setServerName(sb.serverData, updated)' "$ROOT/src/client/java/dev/zazuzin/zst/MultiplayerManagementEntrypoint.java" || {
  echo "Unfavourite does not clear the live row's stale star" >&2; exit 1;
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
grep -q 'int finderY = refreshBounds != null ? refreshBounds.y()' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Bottom-aligned non-overlapping Multiplayer control rail is missing" >&2; exit 1;
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

# Finder provider and supplementary-probe guards. Discovery must fail over
# independently of BreakBlocks without affecting the Multiplayer interface.
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
grep -q 'breakBlocksMaxAgeDays = 7' "$ROOT/src/client/java/dev/zazuzin/zst/ToolState.java" || {
  echo "BreakBlocks default age is not 7 days" >&2; exit 1;
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
grep -q '? "UNVERIFIED" : "VERIFIED"' "$ROOT/src/client/java/dev/zazuzin/zst/ServerFinderClient.java" || {
  echo "Double-verified Finder results are not labelled VERIFIED" >&2; exit 1;
}
grep -q 'MAX_IN_FLIGHT = 20' "$ROOT/src/client/java/dev/zazuzin/zst/VanillaStatusProbe.java" || {
  echo "Finder status-client concurrency cap is missing" >&2; exit 1;
}
grep -q 'CONNECT_TIMEOUT_MS = 10_000' "$ROOT/src/client/java/dev/zazuzin/zst/VanillaStatusProbe.java" || {
  echo "Finder connection timeout is incorrect" >&2; exit 1;
}
grep -q 'READ_TIMEOUT_MS = 5_000' "$ROOT/src/client/java/dev/zazuzin/zst/VanillaStatusProbe.java" || {
  echo "Finder status-response timeout is incorrect" >&2; exit 1;
}
grep -q 'visibleAndContains(state.deleteAllButton, x, y)' "$ROOT/src/client/java/dev/zazuzin/zst/MultiplayerManagementEntrypoint.java" || {
  echo "Bulk-delete mouse interception is missing" >&2; exit 1;
}
grep -q 'Search Mode:' "$ROOT/src/client/java/dev/zazuzin/zst/ServerFinderClient.java" || {
  echo "Finder search-mode control is missing" >&2; exit 1;
}
grep -q 'Quick Search accepted' "$ROOT/src/client/java/dev/zazuzin/zst/ServerFinderClient.java" || {
  echo "Finder Quick Search path is missing" >&2; exit 1;
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
grep -q 'BreakBlocksContributor.submit(record.address(), record.port(),' "$ROOT/src/client/java/dev/zazuzin/zst/ServerFinderClient.java" || {
  echo "Verified discoveries are not submitted to the BreakBlocks contribution queue" >&2; exit 1;
}
grep -q 'MAX_REFRESH_RETRIES = 3' "$ROOT/src/client/java/dev/zazuzin/zst/BreakBlocksContributor.java" || {
  echo "BreakBlocks contribution retry limit is incorrect" >&2; exit 1;
}
grep -q 'REFRESH_DELAY_SECONDS = 10L' "$ROOT/src/client/java/dev/zazuzin/zst/BreakBlocksContributor.java" || {
  echo "BreakBlocks refreshing delay is not 10 seconds" >&2; exit 1;
}
grep -q 'Retry-After' "$ROOT/src/client/java/dev/zazuzin/zst/BreakBlocksContributor.java" || {
  echo "BreakBlocks contribution queue does not respect Retry-After" >&2; exit 1;
}
grep -q 'contributeVerifiedServers = bool' "$ROOT/src/client/java/dev/zazuzin/zst/ToolState.java" || {
  echo "BreakBlocks contribution preference is not persistent" >&2; exit 1;
}
grep -q 'static boolean contributeVerifiedServers = true' "$ROOT/src/client/java/dev/zazuzin/zst/ToolState.java" || {
  echo "BreakBlocks contributions are not enabled by default" >&2; exit 1;
}
grep -q 'contributeVerifiedServers = bool(p, "contributeVerifiedServers", true)' "$ROOT/src/client/java/dev/zazuzin/zst/ToolState.java" || {
  echo "BreakBlocks contribution migration default is not enabled" >&2; exit 1;
}
grep -q 'breakblocks-contributions.csv' "$ROOT/src/client/java/dev/zazuzin/zst/BreakBlocksContributor.java" || {
  echo "BreakBlocks contribution audit log is missing" >&2; exit 1;
}
grep -q 'timestamp_utc,server,attempt,result,http_status,detail' "$ROOT/src/client/java/dev/zazuzin/zst/BreakBlocksContributor.java" || {
  echo "BreakBlocks contribution audit log header is missing" >&2; exit 1;
}
grep -q 'BreakBlocksRateBudget.recordSearchRequest(authenticated)' "$ROOT/src/client/java/dev/zazuzin/zst/ServerFinderClient.java" || {
  echo "BreakBlocks discovery requests are not included in the shared allowance" >&2; exit 1;
}
grep -q 'BreakBlocksRateBudget.contributionDelayMillis' "$ROOT/src/client/java/dev/zazuzin/zst/BreakBlocksContributor.java" || {
  echo "BreakBlocks contributions do not wait for shared allowance" >&2; exit 1;
}
grep -q 'X-RateLimit-Remaining' "$ROOT/src/client/java/dev/zazuzin/zst/BreakBlocksRateBudget.java" || {
  echo "BreakBlocks remaining allowance header is not handled" >&2; exit 1;
}
grep -q 'X-RateLimit-Reset' "$ROOT/src/client/java/dev/zazuzin/zst/BreakBlocksRateBudget.java" || {
  echo "BreakBlocks reset header is not handled" >&2; exit 1;
}
grep -q 'reportedLimit / 2' "$ROOT/src/client/java/dev/zazuzin/zst/BreakBlocksRateBudget.java" || {
  echo "BreakBlocks rate-limit estimate is not reduced after HTTP 429" >&2; exit 1;
}
grep -q 'breakblocks-contribution-queue.txt' "$ROOT/src/client/java/dev/zazuzin/zst/BreakBlocksContributor.java" || {
  echo "Persistent BreakBlocks contribution queue is missing" >&2; exit 1;
}
grep -q 'ArrayDeque<Task> READY' "$ROOT/src/client/java/dev/zazuzin/zst/BreakBlocksContributor.java" || {
  echo "Central FIFO contribution queue is missing" >&2; exit 1;
}
grep -q 'PriorityQueue<Task> DELAYED' "$ROOT/src/client/java/dev/zazuzin/zst/BreakBlocksContributor.java" || {
  echo "Delayed contribution follow-up queue is missing" >&2; exit 1;
}
grep -q 'DELAYED.peek().readyAtMs() <= now) task = DELAYED.remove()' "$ROOT/src/client/java/dev/zazuzin/zst/BreakBlocksContributor.java" || {
  echo "Due contribution follow-ups do not take priority over untouched backlog" >&2; exit 1;
}
grep -q 'boolean quotaPauseLogged' "$ROOT/src/client/java/dev/zazuzin/zst/BreakBlocksContributor.java" || {
  echo "Continuous quota pauses are not log-deduplicated" >&2; exit 1;
}
grep -q 'Contribution Stats' "$ROOT/src/client/java/dev/zazuzin/zst/ServerFinderClient.java" || {
  echo "Contribution Stats settings view is missing" >&2; exit 1;
}
grep -q 'Server Seeker Stats' "$ROOT/src/client/java/dev/zazuzin/zst/ServerFinderClient.java" || {
  echo "Server Seeker Stats settings view is missing" >&2; exit 1;
}
grep -q 'Reflection.setTooltip' "$ROOT/src/client/java/dev/zazuzin/zst/ServerFinderClient.java" || {
  echo "Finder and settings tooltips are missing" >&2; exit 1;
}
grep -q 'Retry Failed (' "$ROOT/src/client/java/dev/zazuzin/zst/ServerFinderClient.java" || {
  echo "Contribution retry control is missing" >&2; exit 1;
}
grep -q 'breakblocks-contribution-failed.txt' "$ROOT/src/client/java/dev/zazuzin/zst/BreakBlocksContributor.java" || {
  echo "Failed contribution persistence is missing" >&2; exit 1;
}
grep -q 'SUCCESS_COOLDOWN_MS = TimeUnit.HOURS.toMillis(6L)' "$ROOT/src/client/java/dev/zazuzin/zst/BreakBlocksContributor.java" || {
  echo "Six-hour successful contribution cooldown is missing" >&2; exit 1;
}
grep -q 'private-lan-servers.txt' "$ROOT/src/client/java/dev/zazuzin/zst/BreakBlocksContributor.java" || {
  echo "Private/LAN local server list is missing" >&2; exit 1;
}
grep -q 'isPrivateOrLan(parsed.host())' "$ROOT/src/client/java/dev/zazuzin/zst/BreakBlocksContributor.java" || {
  echo "Private/LAN endpoints are not separated from BreakBlocks contributions" >&2; exit 1;
}
grep -q 'BreakBlocksContributor.submitConnected(candidate)' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Stable manual/Direct Connect contribution hook is missing" >&2; exit 1;
}

# Whitelist deletion must remove the live Multiplayer ServerList before
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

# Recent Servers history guards.
grep -q 'enum Tab { FAVOURITES, SERVERS, SCANNED, RECENT }' "$ROOT/src/client/java/dev/zazuzin/zst/ServerCategoryStore.java" || {
  echo "Recent Servers category is missing" >&2; exit 1;
}
grep -q 'MAX_RECENT = 5' "$ROOT/src/client/java/dev/zazuzin/zst/ServerCategoryStore.java" || {
  echo "Recent Servers history is not capped at five" >&2; exit 1;
}
grep -q 'recordSuccessfulJoin(recordedEndpoint)' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Stable joins are not being recorded in Recent Servers" >&2; exit 1;
}
grep -q 'Recent Servers (' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Recent Servers hub button is missing" >&2; exit 1;
}
grep -q 'recentEndpoints()' "$ROOT/src/client/java/dev/zazuzin/zst/ServerListAccess.java" || {
  echo "Recent Servers are not rebuilt in newest-first order" >&2; exit 1;
}

# Auto Add is owned by the Finder itself. It must not depend on a separate
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

# Strict Finder admission and stale Scanned Servers cleanup guards.
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

# Scanned Servers exposes a separately scoped, confirmation-protected bulk delete
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
grep -q 'Delete All Servers' "$ROOT/src/client/java/dev/zazuzin/zst/MultiplayerManagementEntrypoint.java" || {
  echo "Servers bulk-delete label is unclear" >&2; exit 1;
}

# Auto Join authentication rate-limit cooldown guards.
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

# Pause-menu favourite control guards.
grep -q '☆ Favourite Server' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Pause-menu Favourite Server button is missing" >&2; exit 1;
}
grep -q '★ Unfavourite Server' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Pause-menu Unfavourite Server state is missing" >&2; exit 1;
}
grep -q 'Server: ' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Pause-menu current-server address is missing" >&2; exit 1;
}
grep -q 'Copy IP' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Pause-menu Copy IP control is missing" >&2; exit 1;
}
grep -q 'setClipboard' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Pause-menu clipboard integration is missing" >&2; exit 1;
}
grep -q 'RuntimeAccess.width(font, fullAddressLabel)' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Pause-menu address box is not sized to its text" >&2; exit 1;
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

# Favourites are a hard boundary for all automatic removal paths, and
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

# Favourites are stored by endpoint and health traffic is deferred while native
# status rows populate. The unverified pause-menu editor must not be exposed.
grep -q 'p.setProperty("favourites"' "$ROOT/src/client/java/dev/zazuzin/zst/ServerCategoryStore.java" || {
  echo "Rename-safe favourite persistence is missing" >&2; exit 1;
}
grep -q 'ServerCategoryStore.isFavourite(endpoint)' "$ROOT/src/client/java/dev/zazuzin/zst/ServerListAccess.java" || {
  echo "Category filtering does not use persisted favourite identity" >&2; exit 1;
}
if grep -q 'Edit Server Info' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java"; then
  echo "Unverified pause-menu Edit Server Info control is still exposed" >&2; exit 1;
fi
grep -q 'SCANNED_HEALTH_INITIAL_DELAY_MS = 20_000L' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Initial native status-ping head start is missing" >&2; exit 1;
}
grep -q 'cachedLatencyMillis(endpoint)' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Recent successful status cache is not reused by health cleanup" >&2; exit 1;
}

# Deletion recovery and persisted health-state guards.
grep -q 'zazus-server-tool-backups' "$ROOT/src/client/java/dev/zazuzin/zst/ServerCategoryStore.java" || { echo "Automatic servers.dat backups missing" >&2; exit 1; }
grep -q 'Undo Last Delete' "$ROOT/src/client/java/dev/zazuzin/zst/MultiplayerManagementEntrypoint.java" || { echo "Undo Last Delete control missing" >&2; exit 1; }
grep -q 'recordHealthFailure' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || { echo "Persistent health failure recording missing" >&2; exit 1; }
if grep -qE 'buttons\.health|sb\.health|healthLabel\(' "$ROOT/src/client/java/dev/zazuzin/zst/MultiplayerManagementEntrypoint.java"; then
  echo "Removed per-server Health controls are still present" >&2; exit 1;
fi
grep -q 'ScreenRoute route = SCREEN_ROUTES.get(screen)' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Category route is not persistent across repeated screen initialisation" >&2; exit 1;
}
grep -q 'new ScreenRoute(view, hub)' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Category Back routing is missing" >&2; exit 1;
}
grep -q 'visibleAndContains(state.undoButton' "$ROOT/src/client/java/dev/zazuzin/zst/MultiplayerManagementEntrypoint.java" || { echo "Undo click is not routed through the Multiplayer interceptor" >&2; exit 1; }
if grep -qE 'Protected Server|toggleProtected|P✓|isProtectedData' "$ROOT/src/client/java/dev/zazuzin/zst/"*.java; then echo "Removed protection feature is still present" >&2; exit 1; fi

# Server Notes native-merge regression guards.
NOTES_BOOTSTRAP="$ROOT/src/client/java/dev/zazu/servernotes/client/ZazusServerNotesClient.java"
NOTES_ROWS="$ROOT/src/client/java/dev/zazuzin/zst/MultiplayerManagementEntrypoint.java"

grep -q 'InputConstants.KEY_N' "$NOTES_BOOTSTRAP" || {
  echo "Server Notes default N keybind is missing" >&2; exit 1;
}
[[ "$(grep -c 'ClientTickEvents.END_CLIENT_TICK.register' "$NOTES_BOOTSTRAP")" -eq 1 ]] || {
  echo "Server Notes must have exactly one client tick registration" >&2; exit 1;
}
grep -q 'app.playerTracking().tick(client)' "$NOTES_BOOTSTRAP" || {
  echo "Server Notes player tracking tick is missing" >&2; exit 1;
}
grep -q 'sb.notes = makeNotesButton' "$NOTES_ROWS" || {
  echo "Dedicated native Notes row button is missing" >&2; exit 1;
}
grep -q 'int favX = notesX + 23' "$NOTES_ROWS" || {
  echo "Notes does not own a dedicated compact slot before Favourite" >&2; exit 1;
}
grep -q 'int authX = favX + 23' "$NOTES_ROWS" || {
  echo "Favourite/Auth compact row ordering changed" >&2; exit 1;
}
grep -Eq 'int deleteX = (ToolState\.authDetectionEnabled|authEnabled) \? authX \+ 23 : favX \+ 23' "$NOTES_ROWS" || {
  echo "Delete compact row slot is missing" >&2; exit 1;
}
grep -Fq '20 * 4 + 3 * 3' "$NOTES_ROWS" || {
  echo "Compact four-control row width is missing" >&2; exit 1;
}
grep -q 'Reflection.setTooltip(sb.notes, "Notes")' "$NOTES_ROWS" || {
  echo "Notes tooltip is missing" >&2; exit 1;
}
grep -q 'makeSpriteButton(x, y, "Notes", "book"' "$NOTES_ROWS" || {
  echo "Notes book sprite identifier is missing" >&2; exit 1;
}
grep -q 'authRowTooltip' "$NOTES_ROWS" || {
  echo "Compact auth tooltip mapping is missing" >&2; exit 1;
}
grep -q 'makeDeleteButton' "$NOTES_ROWS" || {
  echo "Trash-icon Delete control is missing" >&2; exit 1;
}
jar tf "$JAR" | grep -qx 'assets/zazus_server_notes/textures/gui/sprites/trash.png' || {
  echo "Trash icon resource is missing from the JAR" >&2; exit 1;
}
grep -q 'ServerAddressNormalizer' "$NOTES_ROWS" || {
  echo "Notes row open path is not using ServerAddressNormalizer" >&2; exit 1;
}
# rowWidgets must remain the historical Favourite/Delete pair contract.
python3 - "$NOTES_ROWS" <<'PY2'
from pathlib import Path
import re, sys
s=Path(sys.argv[1]).read_text()
m=re.search(r'static List<Object> rowWidgets\(Object screen\) \{(.*?)\n    \}', s, re.S)
if not m:
    raise SystemExit('rowWidgets contract method missing')
body=m.group(1)
if 'buttons.notes' in body or 'buttons.auth' in body:
    raise SystemExit('rowWidgets Favourite/Delete compatibility contract was changed')
if 'buttons.favourite' not in body or 'buttons.delete' not in body:
    raise SystemExit('rowWidgets Favourite/Delete compatibility pair missing')
PY2
grep -q 'dev.zazu.servernotes.client.ZazusServerNotesClient' "$ROOT/src/main/resources/fabric.mod.json" || {
  echo "Merged Server Notes bootstrap entrypoint is missing" >&2; exit 1;
}
grep -q 'buttons.notes' "$ROOT/src/client/java/dev/zazuzin/zst/MultiplayerManagementEntrypoint.java" || {
  echo "Native Server Notes row control is missing" >&2; exit 1;
}
grep -q 'allRowWidgets(state.screen)' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Server Notes row control is not category-managed" >&2; exit 1;
}
if grep -R -n 'MultiplayerNotesIntegration.initialize' "$ROOT/src/client/java"; then
  echo "Standalone MultiplayerNotesIntegration is still initialized" >&2; exit 1;
fi
grep -q 'resolve("zazus-server-notes")' "$ROOT/src/client/java/dev/zazu/servernotes/storage/ServerProfileStore.java" || {
  echo "Server Notes data directory changed" >&2; exit 1;
}
grep -q 'SCHEMA_VERSION = 2' "$ROOT/src/client/java/dev/zazu/servernotes/storage/ServerProfileStore.java" || {
  echo "Server Notes schema version changed" >&2; exit 1;
}

EXPECTED_CLASS_MAJOR="${EXPECTED_CLASS_MAJOR:-69}"
python3 - "$JAR" "$VERSION" "$EXPECTED_CLASS_MAJOR" <<'PY'
import json, struct, sys, zipfile
from pathlib import PurePosixPath

jar = sys.argv[1]
expected_version = sys.argv[2]
expected_major = int(sys.argv[3])
expected_entrypoints = {
    "dev.zazu.servernotes.client.ZazusServerNotesClient",
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

    if "dev/zazu/servernotes/client/ZazusServerNotesClient.class" not in names:
        raise SystemExit("Merged Server Notes bootstrap class is missing")
    if any(n.startswith("dev/zazu/servernotes/client/MultiplayerNotesIntegration") for n in names):
        raise SystemExit("Standalone MultiplayerNotesIntegration was packaged")
    if "assets/zazus_server_notes/textures/gui/sprites/book.png" not in names:
        raise SystemExit("Server Notes book sprite is missing")

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
        if major != expected_major:
            raise SystemExit(f"{name} targets class-file major {major}, expected {expected_major}")

    # Minecraft 26.2 defines Component as an interface. A direct javac build
    # against a class-shaped stub produces a Methodref here and crashes at
    # runtime with IncompatibleClassChangeError as soon as Server Notes opens.
    def constant_pool_refs(data):
        cp_count = struct.unpack_from(">H", data, 8)[0]
        p = 10
        cp = [None] * cp_count
        i = 1
        while i < cp_count:
            tag = data[p]
            p += 1
            if tag == 1:
                length = struct.unpack_from(">H", data, p)[0]
                p += 2
                cp[i] = (tag, data[p:p + length].decode("utf-8", "replace"))
                p += length
            elif tag in (3, 4):
                cp[i] = (tag,)
                p += 4
            elif tag in (5, 6):
                cp[i] = (tag,)
                p += 8
                i += 1
            elif tag in (7, 8, 16, 19, 20):
                cp[i] = (tag, struct.unpack_from(">H", data, p)[0])
                p += 2
            elif tag in (9, 10, 11, 12, 17, 18):
                a, b = struct.unpack_from(">HH", data, p)
                cp[i] = (tag, a, b)
                p += 4
            elif tag == 15:
                cp[i] = (tag, data[p], struct.unpack_from(">H", data, p + 1)[0])
                p += 3
            else:
                raise SystemExit(f"Unsupported constant-pool tag {tag}")
            i += 1

        def utf8(index):
            return cp[index][1]
        def class_name(index):
            return utf8(cp[index][1])

        refs = []
        for item in cp:
            if item and item[0] in (9, 10, 11):
                tag, class_index, nt_index = item
                nt = cp[nt_index]
                refs.append((tag, class_name(class_index), utf8(nt[1]), utf8(nt[2])))
        return refs

    notes_screen = z.read("dev/zazu/servernotes/ui/ServerNotesScreen.class")
    component_literal = [
        ref for ref in constant_pool_refs(notes_screen)
        if ref[1] == "net/minecraft/network/chat/Component" and ref[2] == "literal"
    ]
    if not component_literal:
        raise SystemExit("ServerNotesScreen has no Component.literal reference")
    if any(ref[0] != 11 for ref in component_literal):
        raise SystemExit(
            "ServerNotesScreen Component.literal is not InterfaceMethodref; "
            "this will crash on Minecraft 26.2"
        )

    forbidden = {
        "dev/zazuzin/zst/CoreUiStripper.class",
        "dev/zazuzin/zst/EnhancementsEntrypoint.class",
    }
    if forbidden.intersection(names):
        raise SystemExit("Legacy patch-only classes are still packaged")

print(f"Verified {len(classes)} Java 25 class files and Fabric metadata.")
PY

# Release identity must be centralized and match the packaged Gradle version.
grep -q "static final String VERSION = \"$VERSION\"" "$ROOT/src/client/java/dev/zazuzin/zst/ReleaseInfo.java" || {
  echo "ReleaseInfo version mismatch" >&2; exit 1;
}
for source in ServerFinderClient BreakBlocksContributor; do
  grep -q 'USER_AGENT = ReleaseInfo.USER_AGENT' \
    "$ROOT/src/client/java/dev/zazuzin/zst/${source}.java" || {
    echo "$source is not using centralized release User-Agent" >&2
    exit 1
  }
done
if grep -R -n 'ZazusServerSeeker/0\.4\.1-beta' "$ROOT/src/client/java"; then
  echo "Stale beta User-Agent remains in source" >&2; exit 1;
fi
grep -q 'Build: " + ReleaseInfo.VERSION' "$ROOT/src/client/java/dev/zazuzin/zst/ServerFinderClient.java" || {
  echo "Finder Settings build/version indicator is missing" >&2; exit 1;
}

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
javac --release 25 -cp "$JAR" -d "$API_TEST_DIR" "$API_TEST_DIR/dev/zazuzin/zst/ApiKeyRequestTest.java"
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
        if (!ReleaseInfo.USER_AGENT.equals(ua))
            throw new AssertionError("Provider User-Agent version mismatch: " + ua);

        if (!"1.2.3.4".equals(ServerFinderClient.intToIpv4(16909060L)))
            throw new AssertionError("Unsigned IPv4 conversion regression");
    }
}
JAVA
javac --release 25 -cp "$JAR" -d "$PROVIDER_TEST_DIR" "$PROVIDER_TEST_DIR/dev/zazuzin/zst/ProviderParsingTest.java"
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
            for (int i = 1; i <= 20; i++) endpoints.add("127.0.0." + i + ":" + server.port());
            endpoints.add("127.0.0.1:" + deadPort);
            endpoints.add("no-such-zazu-host.invalid:25565");

            var results = VanillaStatusProbe.probe(new Client(), new Object(), endpoints, () -> true)
                    .get(12, TimeUnit.SECONDS);
            if (results.size() != endpoints.size()) throw new AssertionError("Unexpected status result count: " + results);
            for (int i = 0; i < 20; i++) {
                var live = results.get(i);
                if (!live.replied() || live.protocol() != 776 || !"26.2 Test".equals(live.version()) || live.latencyMs() < 1L)
                    throw new AssertionError("Java status reply was not captured: " + live);
            }
            if (results.get(20).failure() != VanillaStatusProbe.Failure.UNREACHABLE)
                throw new AssertionError("Unreachable classification failed: " + results.get(20));
            if (results.get(21).failure() != VanillaStatusProbe.Failure.DNS)
                throw new AssertionError("DNS classification failed: " + results.get(21));
            if (server.maxActive() > 20)
                throw new AssertionError("Status client exceeded twenty in-flight requests: " + server.maxActive());
            if (server.maxActive() < 16)
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
javac --release 25 -cp "$JAR" -d "$PROBE_TEST_DIR" $(find "$PROBE_TEST_DIR" -name '*.java' -type f | sort)
java -Duser.dir="$PROBE_TEST_DIR" -cp "$JAR:$PROBE_TEST_DIR" dev.zazuzin.zst.VanillaStatusProbeTest
rm -rf "$PROBE_TEST_DIR"
echo "Bounded pure-Java Minecraft status-client regression test passed."

# Authentication classification must remain non-blocking and protocol-driven.
grep -q 'RECHECK_DAYS = 14' "$ROOT/src/client/java/dev/zazuzin/zst/ServerAuthService.java" || {
  echo "Authentication recheck interval is not 14 days" >&2; exit 1;
}
grep -q 'WORKERS = 8' "$ROOT/src/client/java/dev/zazuzin/zst/ServerAuthService.java" || {
  echo "Authentication probe worker pool is not bounded to 8" >&2; exit 1;
}
grep -q 'TIMEOUT_MS = 3_000' "$ROOT/src/client/java/dev/zazuzin/zst/ServerAuthService.java" || {
  echo "Authentication probe timeout is not 3 seconds" >&2; exit 1;
}
grep -q 'Auth Detection: ' "$ROOT/src/client/java/dev/zazuzin/zst/ServerFinderClient.java" || {
  echo "Authentication detection setting is missing" >&2; exit 1;
}
grep -q 'ServerAuthService.ensureAsync' "$ROOT/src/client/java/dev/zazuzin/zst/ServerTabsEntrypoint.java" || {
  echo "Existing Scanned Servers are not feeding the auth classifier" >&2; exit 1;
}

AUTH_TEST_DIR="$(mktemp -d)"
mkdir -p "$AUTH_TEST_DIR/dev/zazuzin/zst"
cat > "$AUTH_TEST_DIR/dev/zazuzin/zst/AuthProbeRegressionTest.java" <<'JAVA'
package dev.zazuzin.zst;

import java.io.*;
import java.lang.reflect.Method;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

public final class AuthProbeRegressionTest {
    enum Mode { MICROSOFT, OFFLINE_ENCRYPTED, CRACKED, COMPRESSED_CRACKED }

    public static void main(String[] args) throws Exception {
        if (ServerAuthService.RECHECK_DAYS != 14) throw new AssertionError("Auth cache must recheck after 14 days");
        if (ServerAuthService.WORKERS != 8) throw new AssertionError("Auth worker pool must stay bounded to 8");
        if (ServerAuthService.TIMEOUT_MS != 3000) throw new AssertionError("Auth probe timeout must stay at 3 seconds");
        assertType(Mode.MICROSOFT, ServerAuthService.Type.MICROSOFT);
        assertType(Mode.OFFLINE_ENCRYPTED, ServerAuthService.Type.CRACKED);
        assertType(Mode.CRACKED, ServerAuthService.Type.CRACKED);
        assertType(Mode.COMPRESSED_CRACKED, ServerAuthService.Type.CRACKED);
    }

    private static void assertType(Mode mode, ServerAuthService.Type expected) throws Exception {
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread responder = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    InputStream in = new BufferedInputStream(socket.getInputStream());
                    OutputStream out = new BufferedOutputStream(socket.getOutputStream());
                    readFrame(in); // Handshake
                    byte[] login = readFrame(in);
                    ByteArrayInputStream loginIn = new ByteArrayInputStream(login);
                    if (readVarInt(loginIn) != 0) throw new AssertionError("Expected Login Start packet");
                    String username = readString(loginIn);
                    if (!username.startsWith("ZazuAuth_") || username.length() != 16)
                        throw new AssertionError("Probe username is not bounded/non-identifying: " + username);

                    switch (mode) {
                        case MICROSOFT -> sendEncryption(out, true);
                        case OFFLINE_ENCRYPTED -> sendEncryption(out, false);
                        case CRACKED -> writeFrame(out, new byte[]{2});
                        case COMPRESSED_CRACKED -> {
                            ByteArrayOutputStream compression = new ByteArrayOutputStream();
                            writeVarInt(compression, 3);
                            writeVarInt(compression, 256);
                            writeFrame(out, compression.toByteArray());
                            ByteArrayOutputStream success = new ByteArrayOutputStream();
                            writeVarInt(success, 0); // below compression threshold
                            writeVarInt(success, 2); // Login Success
                            writeFrame(out, success.toByteArray());
                        }
                    }
                    out.flush();
                } catch (Throwable failure) {
                    serverFailure.set(failure);
                }
            }, "auth-probe-regression-server");
            responder.start();

            Method probe = ServerAuthService.class.getDeclaredMethod("probe", String.class, int.class);
            probe.setAccessible(true);
            ServerAuthService.Entry result = (ServerAuthService.Entry) probe.invoke(null,
                    "127.0.0.1:" + server.getLocalPort(), 776);
            responder.join(5000L);
            if (responder.isAlive()) throw new AssertionError("Auth test server did not finish");
            if (serverFailure.get() != null) throw new AssertionError("Auth test server failed", serverFailure.get());
            if (result.type() != expected) throw new AssertionError(mode + " => " + result.type() + ", expected " + expected);
        }
    }

    private static void sendEncryption(OutputStream out, boolean shouldAuthenticate) throws IOException {
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        writeVarInt(p, 1);
        writeString(p, "");
        writeVarInt(p, 1); p.write('K');
        writeVarInt(p, 4); p.write("TEST".getBytes(StandardCharsets.UTF_8));
        p.write(shouldAuthenticate ? 1 : 0);
        writeFrame(out, p.toByteArray());
    }

    private static byte[] readFrame(InputStream in) throws IOException {
        int length = readVarInt(in);
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) throw new EOFException();
        return bytes;
    }

    private static void writeFrame(OutputStream out, byte[] payload) throws IOException {
        writeVarInt(out, payload.length); out.write(payload);
    }

    private static String readString(InputStream in) throws IOException {
        int length = readVarInt(in);
        return new String(in.readNBytes(length), StandardCharsets.UTF_8);
    }

    private static int readVarInt(InputStream in) throws IOException {
        int value = 0;
        for (int pos = 0; pos < 5; pos++) {
            int b = in.read(); if (b < 0) throw new EOFException();
            value |= (b & 0x7f) << (pos * 7);
            if ((b & 0x80) == 0) return value;
        }
        throw new IOException("VarInt too large");
    }

    private static void writeVarInt(OutputStream out, int value) throws IOException {
        do {
            int b = value & 0x7f; value >>>= 7;
            if (value != 0) b |= 0x80;
            out.write(b);
        } while (value != 0);
    }

    private static void writeString(OutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length); out.write(bytes);
    }
}
JAVA
javac --release 25 -cp "$JAR" -d "$AUTH_TEST_DIR" "$AUTH_TEST_DIR/dev/zazuzin/zst/AuthProbeRegressionTest.java"
java -Duser.dir="$AUTH_TEST_DIR" -cp "$JAR:$AUTH_TEST_DIR" dev.zazuzin.zst.AuthProbeRegressionTest
rm -rf "$AUTH_TEST_DIR"
echo "Asynchronous Microsoft/Cracked authentication-classifier regression tests passed."

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
        String concise = DisconnectReason.concise("Connection Lost | Failed to connect to the server | Connection timed out | Back to Server List");
        if (!"Connection timed out".equals(concise)) {
            throw new AssertionError("Disconnect reason was not reduced to the useful detail: " + concise);
        }
    }
}
JAVA
javac --release 25 -cp "$JAR" -d "$DISCONNECT_TEST_DIR" "$DISCONNECT_TEST_DIR/dev/zazuzin/zst/DisconnectReasonTest.java"
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
        final ServerList servers = new ServerList();
        final FakeListWidget serverSelectionList = new FakeListWidget();
        public void removeWidget(Object widget) { children.remove(widget); }
    }

    static final class FakeListWidget {
        final List<Object> onlineServers = new ArrayList<>();
    }

    static final class FakeServerEntry {
        final ServerData serverData;
        FakeServerEntry(ServerData serverData) { this.serverData = serverData; }
    }

    public static void main(String[] args) {
        Object ghost = new Object();
        FakeScreen screen = new FakeScreen();
        screen.children.add(ghost); screen.renderables.add(ghost); screen.narratables.add(ghost);
        Reflection.removeWidget(screen, ghost);
        if (screen.children.contains(ghost) || screen.renderables.contains(ghost) || screen.narratables.contains(ghost)) {
            throw new AssertionError("removeWidget left a ghost widget in a Screen list");
        }

        ServerData sourceCopy = new ServerData("★ Pause Favourite", "favourite.example:25565");
        ServerData rowCopy = new ServerData("★ Pause Favourite", "favourite.example:25565");
        screen.servers.servers.add(sourceCopy);
        screen.serverSelectionList.onlineServers.add(new FakeServerEntry(rowCopy));
        ServerListAccess.synchronizeServerName(screen, "favourite.example:25565", "Pause Favourite");
        if (sourceCopy.name.startsWith("★ ") || rowCopy.name.startsWith("★ ")) {
            throw new AssertionError("Unfavourite left a stale starred ServerData copy");
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

        var batch = List.of(
                new ServerCategoryStore.DeletedServer("Batch One", "batch-one.example:25565", false, true),
                new ServerCategoryStore.DeletedServer("Batch Two", "batch-two.example:25565", false, false));
        ServerCategoryStore.recordUndoBatch(batch);
        if (!ServerCategoryStore.undoLastDelete(null)) throw new AssertionError("Bulk Undo Last Delete failed");
        if (ServerList.persistedVisible.stream().noneMatch(s -> s.ip.equals("batch-one.example:25565"))
                || ServerList.persistedVisible.stream().noneMatch(s -> s.ip.equals("batch-two.example:25565")))
            throw new AssertionError("Bulk undo did not restore every deleted server");
        if (!ServerCategoryStore.isScanned("batch-one.example:25565")
                || ServerCategoryStore.isScanned("batch-two.example:25565"))
            throw new AssertionError("Bulk undo did not restore server categories");

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

        String joinedEndpoint = "joined-scanned.example:25565";
        ServerCategoryStore.markScanned(joinedEndpoint);
        WhitelistAutoDeleteEntrypoint.noteAttempt(joinedEndpoint);
        if (!joinedEndpoint.equals(ServerTabsEntrypoint.recentAttemptEndpoint()))
            throw new AssertionError("Connection attempt was not shared with stable-join promotion");
        if (!ServerCategoryStore.promoteVerified(ServerTabsEntrypoint.recentAttemptEndpoint())
                || ServerCategoryStore.isScanned(joinedEndpoint))
            throw new AssertionError("Captured stable join did not promote the scanned endpoint");

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
javac --release 25 -cp "$JAR" -d "$RUNTIME_TEST_DIR"   "$RUNTIME_TEST_DIR/net/fabricmc/api/ClientModInitializer.java"   "$RUNTIME_TEST_DIR/net/minecraft/client/multiplayer/ServerData.java"   "$RUNTIME_TEST_DIR/net/minecraft/client/multiplayer/ServerList.java"   "$RUNTIME_TEST_DIR/dev/zazuzin/zst/RuntimeRegressionTest.java"
java -Duser.dir="$RUNTIME_TEST_DIR" -cp "$JAR:$RUNTIME_TEST_DIR" dev.zazuzin.zst.RuntimeRegressionTest
rm -rf "$RUNTIME_TEST_DIR"
echo "Widget-removal and persisted whitelist-deletion regression tests passed."

echo "Verification passed: $JAR"
