package dev.zazuzin.zst;

import java.lang.reflect.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * Core finder overlay and multi-provider server discovery client.
 *
 * Minecraft GUI/server-list interaction is deliberately reflection-based to
 * tolerate mapping/layout changes across the supported 26.2 client stack.
 */
public final class ServerFinderClient {
    private static final String USER_AGENT = ReleaseInfo.USER_AGENT;
    private static final String BREAKBLOCKS_API_URL = "https://api.breakblocks.com/api/v0.1/servers/find";
    private static final String CORNBREAD_API_URL = "https://api.cornbread2100.com/v1/servers/random";
    private static final String MINESCAN_API_URL = "https://data.minescan.xyz/servers/random";
    private static final int DISPLAY_RESULTS = 8;
    private static final int API_LIMIT = 20;
    private static final int MAX_PUBLIC_PAGES = 10;
    private static final int MAX_RANDOM_PROVIDER_REQUESTS = 25;
    private static final int ALL_SOURCE_SLICE = 3;
    private static final long AUTO_ADD_BETWEEN_BATCHES_MS = 2_000L;
    private static final long AUTO_ADD_AFTER_EXHAUSTED_MS = 60_000L;
    private static final long AUTO_ADD_AFTER_FAILURE_MS = 15_000L;
    private static final long SECOND_STATUS_CONFIRM_DELAY_MS = 1_000L;

    private static final String[] VERSION_OPTIONS = {
            "*", "26.3", "26.2", "26.1", "1.21*", "1.20*", "1.19*", "1.18*", "1.16*", "1.12*", "1.8*"
    };
    private static final int[] MIN_PLAYER_OPTIONS = {0, 1, 2, 5, 10, 20, 50, 100};
    private static final int[] MAX_PLAYER_OPTIONS = {10, 20, 50, 100, 200, 500, 1000, 999999};
    private static final String[] SERVER_TYPE_LABELS = {"Any", "Premium", "Cracked"};
    private static final String[] SORT_LABELS = {"Recent", "Players", "Version", "Address", "Port"};
    private static final String[] SORT_API_VALUES = {"", "users", "version", "address", "port"};
    private static final String[] SOURCE_LABELS = {"Auto", "All Sources", "BreakBlocks", "Cornbread", "MineScan"};
    private static final int[] BREAKBLOCKS_AGE_OPTIONS = {1, 7, 14, 21, 30};
    private static final int[] AUTO_ADD_LIMITS = {10, 25, 50, 0};

    private enum Provider {
        BREAKBLOCKS("BreakBlocks"),
        CORNBREAD("Cornbread"),
        MINESCAN("MineScan");

        final String label;
        Provider(String label) { this.label = label; }
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final Map<Object, OverlayState> STATES = Collections.synchronizedMap(new WeakHashMap<>());

    private ServerFinderClient() {}

    static boolean isOverlayOpen(Object screen) {
        OverlayState state = STATES.get(screen);
        return state != null && state.open;
    }

    static void openOverlay(Object client, Object screen, int width, int height) throws Exception {
        openOverlay(client, screen, width, height, false);
    }

    static void openBrowserOverlay(Object client, Object screen, int width, int height) throws Exception {
        openOverlay(client, screen, width, height, true);
    }

    static void openSettingsOverlay(Object client, Object screen, int width, int height) throws Exception {
        openOverlay(client, screen, width, height, false, true);
    }

    private static void openOverlay(Object client, Object screen, int width, int height, boolean browse) throws Exception {
        openOverlay(client, screen, width, height, browse, false);
    }

    private static void openOverlay(Object client, Object screen, int width, int height, boolean browse, boolean preferences) throws Exception {
        OverlayState old = STATES.get(screen);
        if (old != null && old.open) {
            if (preferences) showSettings(old);
            else if (browse && !old.browserOpen) openBrowser(old);
            return;
        }

        OverlayState state = new OverlayState(client, screen, width, height);
        state.versionIndex = versionIndexForFilter(ToolState.versionFilter, ToolState.versionIndex);
        ToolState.versionIndex = state.versionIndex;
        ToolState.versionFilter = VERSION_OPTIONS[state.versionIndex];
        state.minIndex = clampIndex(ToolState.minIndex, MIN_PLAYER_OPTIONS.length, 1);
        state.maxIndex = clampIndex(ToolState.maxIndex, MAX_PLAYER_OPTIONS.length, 7);
        state.sortIndex = clampIndex(ToolState.sortIndex, SORT_LABELS.length, 0);
        state.serverTypeIndex = clampIndex(ToolState.serverTypeIndex, SERVER_TYPE_LABELS.length, 0);
        state.sourceIndex = clampIndex(ToolState.finderSourceIndex, SOURCE_LABELS.length, 0);
        state.settingsReturnToMultiplayer = preferences;
        state.searchFilters = ToolState.activeSearchFilters();
        if (state.searchFilters.active()) state.sourceIndex = 2;
        state.autoAdd = !browse && !preferences && ToolState.autoAddDefault && !ToolState.quickSearch;
        state.open = true;
        STATES.put(screen, state);

        for (Object widget : new ArrayList<>(Reflection.widgets(screen))) {
            state.originalStates.put(widget, new WidgetState(
                    Reflection.readBoolean(widget, "visible", true),
                    Reflection.readBoolean(widget, "active", true)));
            Reflection.setBoolean(widget, "visible", false);
            Reflection.setBoolean(widget, "active", false);
        }
        buildOverlay(state);
        if (preferences) showSettings(state);
        else if (browse) openBrowser(state);
        else if (state.autoAdd) {
            state.autoAddedThisSession = 0;
            scheduleNextAutoAddBatch(state, 0L, false, "Auto-add starting…");
        }
    }

    private static void buildOverlay(OverlayState s) throws Exception {
        int cx = s.width / 2;
        int y = 30;
        int topWidth = Math.min(416, s.width - 12), topX = (s.width - topWidth) / 2;
        int findWidth = topWidth * 128 / 416, autoWidth = topWidth * 92 / 416;
        int resetWidth = autoWidth, closeWidth = topWidth - findWidth - autoWidth - resetWidth - 12;
        s.findButton = trackedButton(s, "Find New Servers", topX, y, findWidth,
                "Search the selected provider for another batch of servers.", b -> findNewServers(s));
        s.autoButton = trackedButton(s, autoLabel(s), topX + findWidth + 4, y, autoWidth,
                "Automatically save servers that pass Verified Search. Options: ON or OFF.", b -> toggleAuto(s));
        s.resetButton = trackedButton(s, "Reset Search", topX + findWidth + autoWidth + 8, y, resetWidth,
                "Clear the current results and begin again with the selected filters.", b -> resetSearchState(s, "Search reset."));
        s.closeButton = trackedButton(s, "Close Finder", topX + findWidth + autoWidth + resetWidth + 12, y, closeWidth,
                "Close Server Finder and return to the multiplayer screen.", b -> closeOverlay(s));

        y += 24;
        final int filterMenuY = y + 21;
        int filterWidth = Math.min(380, s.width - 12), filterX = (s.width - filterWidth) / 2;
        int versionWidth = filterWidth * 100 / 380, minWidth = filterWidth * 88 / 380;
        int maxWidth = minWidth, typeWidth = filterWidth - versionWidth - minWidth - maxWidth - 12;
        s.versionButton = trackedButton(s, versionLabel(s), filterX, y, versionWidth,
                "Minecraft version filter. Click to choose a supported version or Any.",
                b -> openMainOptionMenu(s, filterX, filterMenuY, versionWidth, versionOptionLabels(), s.versionIndex,
                        selected -> selectVersion(s, selected)));
        s.minButton = trackedButton(s, minLabel(s), filterX + versionWidth + 4, y, minWidth,
                "Minimum number of players currently online. Click to choose a limit.",
                b -> openMainOptionMenu(s, filterX + versionWidth + 4, filterMenuY, minWidth, integerLabels(MIN_PLAYER_OPTIONS, false), s.minIndex,
                        selected -> selectMin(s, selected)));
        s.maxButton = trackedButton(s, maxLabel(s), filterX + versionWidth + minWidth + 8, y, maxWidth,
                "Maximum number of players currently online. Click to choose a limit or Any.",
                b -> openMainOptionMenu(s, filterX + versionWidth + minWidth + 8, filterMenuY, maxWidth, integerLabels(MAX_PLAYER_OPTIONS, true), s.maxIndex,
                        selected -> selectMax(s, selected)));
        s.serverTypeButton = trackedButton(s, serverTypeLabel(s), filterX + versionWidth + minWidth + maxWidth + 12, y, typeWidth,
                "Server login type. Click to choose Any, Premium or Cracked.",
                b -> openMainOptionMenu(s, filterX + versionWidth + minWidth + maxWidth + 12, filterMenuY, typeWidth, List.of(SERVER_TYPE_LABELS), s.serverTypeIndex,
                        selected -> selectServerType(s, selected)));

        y += 24;
        final int sortMenuY = y + 21;
        int sortWidth = Math.min(388, s.width - 12), sortX = (s.width - sortWidth) / 2;
        int columnWidth = (sortWidth - 16) / 5, lastWidth = sortWidth - 16 - 4 * columnWidth;
        s.sortButton = trackedButton(s, sortLabel(s), sortX, y, columnWidth,
                "Result order. Click to choose how discovered servers are sorted.",
                b -> openMainOptionMenu(s, sortX, sortMenuY, columnWidth, List.of(SORT_LABELS), s.sortIndex,
                        selected -> selectSort(s, selected)));
        s.filtersButton = trackedButton(s, filtersLabel(), sortX + columnWidth + 4, y, columnWidth,
                "Country, region, MOTD text and modpack filters for BreakBlocks.", b -> openDetailedFilters(s, false));
        s.blockedButton = trackedButton(s, blockedLabel(), sortX + 2 * (columnWidth + 4), y, columnWidth,
                "View, unblock or clear servers excluded from future searches.", b -> showBlockedList(s));
        s.browserButton = trackedButton(s, "Browse BB", sortX + 3 * (columnWidth + 4), y, columnWidth,
                "Browse BreakBlocks pages and choose individual servers to save.", b -> openBrowser(s));
        s.settingsButton = trackedButton(s, "Settings", sortX + 4 * (columnWidth + 4), y, lastWidth,
                "Open Finder preferences, statistics and contribution controls.", b -> showSettings(s));

        y += 26;
        int contentWidth = Math.min(452, s.width - 12), x = (s.width - contentWidth) / 2;
        s.statusButton = addTracked(s, Reflection.makeButton(
                "Ready — " + ToolState.addedHistoryCount() + " servers added before can be skipped automatically.",
                x, y, contentWidth, 20, b -> {}));
        Reflection.setBoolean(s.statusButton, "active", false);

        int rowY = y + 25;
        int resultWidth = contentWidth - 122;
        for (int i = 0; i < finderRowsPerPage(s); i++) {
            final int index = i;
            Object row = addTracked(s, Reflection.makeButton("", x, rowY + i * 23, resultWidth, 20, b -> toggleVisibleResult(s, index)));
            Object add = addTracked(s, Reflection.makeButton("Add", x + contentWidth - 118, rowY + i * 23, 54, 20, b -> addVisibleResult(s, index)));
            Object detail = addTracked(s, Reflection.makeButton("Details", x + contentWidth - 60, rowY + i * 23, 60, 20, b -> showVisibleDetails(s, index)));
            s.resultButtons.add(row);
            s.resultAddButtons.add(add);
            s.resultDetailButtons.add(detail);
        }
        int navY = rowY + finderRowsPerPage(s) * 23 + 2;
        s.previousResultsButton = addTracked(s, Reflection.makeButton("< Previous", x, navY, 90, 20, b -> changeResultPage(s, -1)));
        s.resultPageButton = addTracked(s, Reflection.makeButton("Page 1/1", x + 94, navY, contentWidth - 188, 20, b -> {}));
        Reflection.setBoolean(s.resultPageButton, "active", false);
        s.nextResultsButton = addTracked(s, Reflection.makeButton("Next >", x + contentWidth - 90, navY, 90, 20, b -> changeResultPage(s, 1)));
        refreshRows(s);
    }

    static int finderRowsPerPage(OverlayState s) {
        return Math.max(1, Math.min(DISPLAY_RESULTS, (s.height - 159) / 23));
    }

    private static Object addTracked(OverlayState s, Object widget) throws Exception {
        Reflection.addWidget(s.screen, widget);
        s.widgets.add(widget);
        return widget;
    }

    private static Object trackedButton(OverlayState s, String text, int x, int y, int width,
                                        String tooltip, java.util.function.Consumer<Object> pressed) throws Exception {
        Object button = addTracked(s, Reflection.makeButton(text, x, y, width, 20, pressed));
        Reflection.setTooltip(button, tooltip);
        return button;
    }

    private static Object subButton(OverlayState s, String text, int x, int y, int width,
                                    String tooltip, java.util.function.Consumer<Object> pressed) throws Exception {
        Object button = addSub(s, Reflection.makeButton(text, x, y, width, 20, pressed));
        Reflection.setTooltip(button, tooltip);
        return button;
    }

    private static Object addSub(OverlayState s, Object widget) throws Exception {
        Reflection.addWidget(s.screen, widget);
        s.subWidgets.add(widget);
        return widget;
    }

    private static void openMainOptionMenu(OverlayState s, int x, int y, int width,
                                           List<String> labels, int selectedIndex, IntConsumer selected) {
        openOptionMenu(s, s.widgets, x, y, width, labels, selectedIndex, selected);
    }

    private static void openSubOptionMenu(OverlayState s, int x, int y, int width,
                                          List<String> labels, int selectedIndex, IntConsumer selected) {
        openOptionMenu(s, s.subWidgets, x, y, width, labels, selectedIndex, selected);
    }

    private static void openOptionMenu(OverlayState s, Collection<Object> ownerWidgets, int x, int y, int width,
                                       List<String> labels, int selectedIndex, IntConsumer selected) {
        openOptionMenuPage(s, ownerWidgets, x, y, width, labels, selectedIndex, selected, 0);
    }

    private static void openOptionMenuPage(OverlayState s, Collection<Object> ownerWidgets, int x, int y, int width,
                                           List<String> labels, int selectedIndex, IntConsumer selected, int page) {
        try {
            clearOptionMenu(s);
            for (Object widget : ownerWidgets) {
                if (!Reflection.readBoolean(widget, "visible", true)) continue;
                s.optionMenuOwnerStates.put(widget, Reflection.readBoolean(widget, "active", true));
                Reflection.setBoolean(widget, "active", false);
            }
            int simpleCapacity = Math.max(1, (s.height - 10) / 21 - 1);
            boolean paged = labels.size() > simpleCapacity;
            int pageSize = paged ? Math.max(1, simpleCapacity - 1) : labels.size();
            int pages = Math.max(1, (labels.size() + pageSize - 1) / pageSize);
            int currentPage = Math.max(0, Math.min(page, pages - 1));
            int start = currentPage * pageSize, end = Math.min(labels.size(), start + pageSize);
            int optionY = Math.min(y, Math.max(5, s.height - 5 - (end - start + (paged ? 2 : 1)) * 21));
            for (int i = start; i < end; i++) {
                final int index = i;
                String label = (i == selectedIndex ? "✓ " : "") + labels.get(i);
                Object option = Reflection.makeButton(label, x, optionY, width, 20, b -> {
                    clearOptionMenu(s);
                    selected.accept(index);
                });
                Reflection.addWidget(s.screen, option);
                s.optionMenuWidgets.add(option);
                optionY += 21;
            }
            if (paged) {
                int half = (width - 4) / 2;
                Object previous = Reflection.makeButton("< Prev", x, optionY, half, 20,
                        b -> openOptionMenuPage(s, ownerWidgets, x, y, width, labels, selectedIndex, selected, currentPage - 1));
                Object next = Reflection.makeButton("Next >", x + half + 4, optionY, width - half - 4, 20,
                        b -> openOptionMenuPage(s, ownerWidgets, x, y, width, labels, selectedIndex, selected, currentPage + 1));
                Reflection.setBoolean(previous, "active", currentPage > 0);
                Reflection.setBoolean(next, "active", currentPage + 1 < pages);
                Reflection.addWidget(s.screen, previous);
                Reflection.addWidget(s.screen, next);
                s.optionMenuWidgets.add(previous);
                s.optionMenuWidgets.add(next);
                optionY += 21;
            }
            Object cancel = Reflection.makeButton("Cancel", x, optionY, width, 20, b -> clearOptionMenu(s));
            Reflection.addWidget(s.screen, cancel);
            s.optionMenuWidgets.add(cancel);
        } catch (Throwable t) {
            clearOptionMenu(s);
            log("Could not open option menu", t);
        }
    }

    private static void clearOptionMenu(OverlayState s) {
        try { Reflection.widgets(s.screen).removeAll(s.optionMenuWidgets); } catch (Throwable ignored) {}
        s.optionMenuWidgets.clear();
        for (Map.Entry<Object, Boolean> entry : s.optionMenuOwnerStates.entrySet()) {
            Reflection.setBoolean(entry.getKey(), "active", entry.getValue());
        }
        s.optionMenuOwnerStates.clear();
    }

    private static Object addSubLabel(OverlayState s, String text, int x, int y, int width) throws Exception {
        Object label = addSub(s, Reflection.makeButton(text, x, y, width, 20, b -> {}));
        Reflection.setBoolean(label, "active", false);
        return label;
    }

    private static void closeOverlay(OverlayState s) {
        if (!s.open) return;
        s.open = false;
        s.browserOpen = false;
        s.browserRequestToken++;
        s.autoAdd = false;
        s.autoAddScheduleToken++;
        clearOptionMenu(s);
        try {
            List<Object> widgets = Reflection.widgets(s.screen);
            widgets.removeAll(s.widgets);
            widgets.removeAll(s.subWidgets);
        } catch (Throwable ignored) {}
        for (Map.Entry<Object, WidgetState> e : s.originalStates.entrySet()) {
            Reflection.setBoolean(e.getKey(), "visible", e.getValue().visible());
            Reflection.setBoolean(e.getKey(), "active", e.getValue().active());
        }
        s.widgets.clear();
        s.subWidgets.clear();
        try { refreshMultiplayerAfterFinderClose(s); }
        catch (Throwable t) { log("Close Finder failed", t); }
    }

    private static void refreshMultiplayerAfterFinderClose(OverlayState s) throws Exception {
        if (ServerTabsEntrypoint.refreshCurrentScreen(s.screen)) return;
        ServerTabsEntrypoint.preserveCurrentViewAfterRefresh(s.screen);
        Method refresh = Reflection.findMethod(s.screen.getClass(), "refreshServerList", 0);
        if (refresh != null) refresh.invoke(s.screen);
        MultiplayerManagementEntrypoint.rebuildRowButtons(s.screen);
    }

    private static void selectVersion(OverlayState s, int selected) {
        s.versionIndex = clampIndex(selected, VERSION_OPTIONS.length, 0);
        Reflection.setButtonText(s.versionButton, versionLabel(s));
        ToolState.versionIndex = s.versionIndex;
        ToolState.versionFilter = VERSION_OPTIONS[s.versionIndex];
        ToolState.save();
        resetSearchState(s, "Version changed — search reset.");
    }
    private static void selectMin(OverlayState s, int selected) {
        s.minIndex = clampIndex(selected, MIN_PLAYER_OPTIONS.length, 0);
        Reflection.setButtonText(s.minButton, minLabel(s));
        ToolState.minIndex = s.minIndex; ToolState.save();
        resetSearchState(s, "Player filter changed — search reset.");
    }
    private static void selectMax(OverlayState s, int selected) {
        s.maxIndex = clampIndex(selected, MAX_PLAYER_OPTIONS.length, MAX_PLAYER_OPTIONS.length - 1);
        Reflection.setButtonText(s.maxButton, maxLabel(s));
        ToolState.maxIndex = s.maxIndex; ToolState.save();
        resetSearchState(s, "Player filter changed — search reset.");
    }
    private static void selectServerType(OverlayState s, int selected) {
        s.serverTypeIndex = clampIndex(selected, SERVER_TYPE_LABELS.length, 0);
        Reflection.setButtonText(s.serverTypeButton, serverTypeLabel(s));
        ToolState.serverTypeIndex = s.serverTypeIndex; ToolState.save();
        resetSearchState(s, "Server type changed — search reset.");
    }
    private static void selectSort(OverlayState s, int selected) {
        s.sortIndex = clampIndex(selected, SORT_LABELS.length, 0);
        Reflection.setButtonText(s.sortButton, sortLabel(s));
        ToolState.sortIndex = s.sortIndex; ToolState.save();
        resetSearchState(s, "Sort changed — search reset.");
    }
    private static void toggleAuto(OverlayState s) {
        if (ToolState.quickSearch) {
            s.autoAdd = false;
            Reflection.setButtonText(s.autoButton, autoLabel(s));
            setStatus(s, "Auto-add requires Verified Search. Change Search Mode in Settings.");
            return;
        }
        s.autoAdd = !s.autoAdd;
        s.autoAddScheduleToken++;
        Reflection.setButtonText(s.autoButton, autoLabel(s));
        if (s.autoAdd) {
            s.autoAddedThisSession = 0;
            setStatus(s, s.loading ? "Auto-add ON — current search will continue automatically." : "Auto-add ON — starting search…");
            if (!s.loading) findNewServers(s);
        } else {
            setStatus(s, "Auto-add OFF.");
        }
    }

    static void resetSearchState(OverlayState s, String status) {
        if (s.loading) { setStatus(s, "Wait for the current provider request to finish."); return; }
        s.seenEndpoints.clear();
        s.currentBatch.clear();
        s.results = List.of();
        s.resultPage = 0;
        s.exhausted = false;
        s.providerRequestCounts.clear();
        s.providerDuplicateOnlyStreaks.clear();
        s.disabledProviders.clear();
        s.activeProvider = null;
        s.allSourceCursor = 0;
        s.breakBlocksPageOrder.clear();
        s.breakBlocksRequestedPages.clear();
        s.breakBlocksPageCapacity = 0;
        s.breakBlocksAvailablePages = 0;
        s.breakBlocksPaidTier = false;
        s.breakBlocksPatreonTier = "";
        s.breakBlocksCurrentPage = 0;
        s.breakBlocksApiResults = 0;
        s.breakBlocksProbeAttempts = 0;
        s.breakBlocksStatusReplies = 0;
        s.breakBlocksLiveVerified = 0;
        s.breakBlocksDnsFailures = 0;
        s.breakBlocksUnreachableFailures = 0;
        s.breakBlocksTimeoutFailures = 0;
        s.breakBlocksProbeErrors = 0;
        s.breakBlocksIncompatibleReplies = 0;
        s.breakBlocksLastProbeFailure = "";
        s.statusProbeAttempts = 0;
        s.statusFirstPasses = 0;
        s.statusSecondPasses = 0;
        s.liveVerified = 0;
        s.statusRejected = 0;
        s.statusDnsFailures = 0;
        s.statusUnreachableFailures = 0;
        s.statusTimeoutFailures = 0;
        s.statusProbeErrors = 0;
        s.lastProbeFailure = "";
        s.searchGeneration++;
        refreshRows(s);
        setStatus(s, status);
    }

    static void findNewServers(OverlayState s) {
        refreshConfiguredApiKey(s);
        if (!s.open || s.loading) {
            if (s.loading) setStatus(s, "Wait for the current provider request to finish.");
            return;
        }
        if (MIN_PLAYER_OPTIONS[s.minIndex] > MAX_PLAYER_OPTIONS[s.maxIndex]) {
            setStatus(s, "Minimum players cannot be above maximum players.");
            return;
        }
        if (ToolState.quickSearch && s.autoAdd) {
            s.autoAdd = false;
            s.autoAddScheduleToken++;
            Reflection.setButtonText(s.autoButton, autoLabel(s));
        }
        if (s.exhausted) {
            setStatus(s, "No more not-previously-added results are available from " + sourceLabel(s) + ".");
            return;
        }
        // Any explicit or scheduled search supersedes an older delayed Auto Add callback.
        s.autoAddScheduleToken++;
        s.searchGeneration++;
        s.loading = true;
        s.currentBatch.clear();
        setStatus(s, "Finding servers via " + sourceLabel(s) + "…");
        fetchUntilBatchFull(s);
    }

    private static void fetchUntilBatchFull(OverlayState s) {
        if (!s.open) { s.loading = false; return; }
        if (s.currentBatch.size() >= DISPLAY_RESULTS) {
            finishBatch(s);
            return;
        }

        Provider provider = chooseProviderForNextRequest(s);
        if (provider == null) {
            s.exhausted = true;
            finishBatch(s);
            return;
        }
        s.activeProvider = provider;
        int requestNumber = s.providerRequestCounts.getOrDefault(provider, 0);
        s.providerRequestCounts.put(provider, requestNumber + 1);

        switch (provider) {
            case BREAKBLOCKS -> fetchBreakBlocks(s);
            case CORNBREAD -> fetchCornbread(s);
            case MINESCAN -> fetchMineScan(s);
        }
    }

    private static Provider chooseProviderForNextRequest(OverlayState s) {
        int mode = s.sourceIndex;
        if (s.searchFilters.active()) return providerAvailableForRequest(s, Provider.BREAKBLOCKS) ? Provider.BREAKBLOCKS : null;
        if (mode == 2) return providerAvailableForRequest(s, Provider.BREAKBLOCKS) ? Provider.BREAKBLOCKS : null;
        if (mode == 3) return providerAvailableForRequest(s, Provider.CORNBREAD) ? Provider.CORNBREAD : null;
        if (mode == 4) return providerAvailableForRequest(s, Provider.MINESCAN) ? Provider.MINESCAN : null;

        Provider[] order = {Provider.BREAKBLOCKS, Provider.CORNBREAD, Provider.MINESCAN};
        if (mode == 1) {
            for (int i = 0; i < order.length; i++) {
                int index = (s.allSourceCursor + i) % order.length;
                Provider p = order[index];
                if (providerAvailableForRequest(s, p)) {
                    s.allSourceCursor = (index + 1) % order.length;
                    return p;
                }
            }
            return null;
        }

        // Auto mode prefers BreakBlocks, then fails over to Cornbread and MineScan.
        if (s.activeProvider != null && providerAvailableForRequest(s, s.activeProvider)) return s.activeProvider;
        for (Provider p : order) if (providerAvailableForRequest(s, p)) return p;
        return null;
    }

    private static boolean providerAvailableForRequest(OverlayState s, Provider provider) {
        if (s.disabledProviders.contains(provider)) return false;
        if (provider == Provider.BREAKBLOCKS) {
            prepareBreakBlocksPageOrder(s, maxBreakBlocksPages(s));
            if (s.breakBlocksPageOrder.isEmpty()) {
                s.disabledProviders.add(provider);
                return false;
            }
            return true;
        }
        int used = s.providerRequestCounts.getOrDefault(provider, 0);
        int max = MAX_RANDOM_PROVIDER_REQUESTS;
        if (used >= max) {
            s.disabledProviders.add(provider);
            return false;
        }
        return true;
    }

    private static void fetchBreakBlocks(OverlayState s) {
        // Give each fresh search its own no-repeat page order. Simultaneous
        // clients with identical filters still share the provider's result
        // pool, but are unlikely to verify the same page at the same time.
        int pageCount = maxBreakBlocksPages(s);
        prepareBreakBlocksPageOrder(s, pageCount);
        if (s.breakBlocksPageOrder.isEmpty()) { providerExhausted(s, Provider.BREAKBLOCKS); return; }
        int page = s.breakBlocksPageOrder.remove(0);
        s.breakBlocksRequestedPages.add(page);
        URI uri = buildBreakBlocksPageUri(s, page);
        s.breakBlocksCurrentPage = page;
        setStatus(s, breakBlocksProgressLabel(s, "requesting"));
        sendBreakBlocksPage(s, page, uri, true);
    }

    private static void prepareBreakBlocksPageOrder(OverlayState s, int pageCount) {
        int safePageCount = Math.max(1, pageCount);
        if (s.breakBlocksPageCapacity == safePageCount && !s.breakBlocksPageOrder.isEmpty()) return;
        List<Integer> pages = new ArrayList<>(shuffledBreakBlocksPages(safePageCount, ThreadLocalRandom.current()));
        pages.removeIf(s.breakBlocksRequestedPages::contains);
        s.breakBlocksPageOrder.clear();
        s.breakBlocksPageOrder.addAll(pages);
        s.breakBlocksPageCapacity = safePageCount;
    }

    static List<Integer> shuffledBreakBlocksPages(int pageCount, Random random) {
        int safePageCount = Math.max(1, pageCount);
        List<Integer> pages = new ArrayList<>(safePageCount);
        for (int page = 1; page <= safePageCount; page++) pages.add(page);
        Collections.shuffle(pages, Objects.requireNonNull(random, "random"));
        return List.copyOf(pages);
    }

    static URI buildBreakBlocksPageUri(OverlayState s, int page) {
        return buildBrowserPageUri(s.versionIndex, s.minIndex, s.maxIndex, s.sortIndex,
                s.serverTypeIndex, ToolState.breakBlocksMaxAgeDays, page, s.searchFilters);
    }

    static URI buildBrowserPageUri(int versionIndex, int minIndex, int maxIndex, int sortIndex,
                                   int serverTypeIndex, int maxAgeDays, int page) {
        return buildBrowserPageUri(versionIndex, minIndex, maxIndex, sortIndex, serverTypeIndex,
                maxAgeDays, page, ServerSearchFilters.EMPTY);
    }

    static URI buildBrowserPageUri(int versionIndex, int minIndex, int maxIndex, int sortIndex,
                                   int serverTypeIndex, int maxAgeDays, int page, ServerSearchFilters filters) {
        int age = normalizedBreakBlocksAgeDays(maxAgeDays);
        StringBuilder url = new StringBuilder(BREAKBLOCKS_API_URL)
                .append("?version=").append(enc(VERSION_OPTIONS[clampIndex(versionIndex, VERSION_OPTIONS.length, 0)]))
                .append("&minUsers=").append(MIN_PLAYER_OPTIONS[clampIndex(minIndex, MIN_PLAYER_OPTIONS.length, 0)])
                .append("&maxUsers=").append(MAX_PLAYER_OPTIONS[clampIndex(maxIndex, MAX_PLAYER_OPTIONS.length, MAX_PLAYER_OPTIONS.length - 1)])
                .append("&page=").append(Math.max(1, page))
                .append("&limit=").append(API_LIMIT)
                .append("&maxAge=").append(age);
        if (serverTypeIndex == 2) url.append("&offlineOnly=on");
        String sort = SORT_API_VALUES[clampIndex(sortIndex, SORT_API_VALUES.length, 0)];
        if (!sort.isBlank()) url.append("&sort=").append(enc(sort));
        return URI.create(filters.appendTo(url.toString()));
    }

    private static void fetchCornbread(OverlayState s) {
        StringBuilder url = new StringBuilder(CORNBREAD_API_URL)
                .append("?limit=").append(API_LIMIT)
                .append("&minPlayers=").append(MIN_PLAYER_OPTIONS[s.minIndex]);
        String version = providerVersionValue(s);
        if (!version.isBlank()) url.append("&version=").append(enc(version));
        sendSimpleProviderRequest(s, Provider.CORNBREAD, URI.create(url.toString()));
    }

    private static void fetchMineScan(OverlayState s) {
        StringBuilder url = new StringBuilder(MINESCAN_API_URL)
                .append("?count=").append(API_LIMIT)
                .append("&minPlayers=").append(MIN_PLAYER_OPTIONS[s.minIndex]);
        String version = providerVersionValue(s);
        if (!version.isBlank()) url.append("&version=").append(enc(version));
        sendSimpleProviderRequest(s, Provider.MINESCAN, URI.create(url.toString()));
    }

    private static String providerVersionValue(OverlayState s) {
        String value = VERSION_OPTIONS[s.versionIndex];
        if (value.equals("*")) return "";
        return value.endsWith("*") ? value.substring(0, value.length() - 1) : value;
    }

    private static void sendBreakBlocksPage(OverlayState s, int page, URI uri, boolean allowAuthentication) {
        String configuredKey = refreshConfiguredApiKey(s);
        String apiKey = allowAuthentication && !s.apiKeyDisabledForSession ? configuredKey : "";
        boolean authenticated = !apiKey.isBlank();
        HttpRequest request = buildBreakBlocksRequest(uri, apiKey);
        BreakBlocksRateBudget.recordSearchRequest(authenticated);
        HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, error) -> Reflection.execute(s.client,
                        () -> handleBreakBlocksPage(s, page, uri, apiKey, response, error)));
    }

    static HttpRequest buildBreakBlocksRequest(URI uri, String apiKey) {
        HttpRequest.Builder builder = baseRequest(uri);
        if (apiKey != null && !apiKey.isBlank()) builder.header("Authorization", "Bearer " + apiKey.trim());
        return builder.GET().build();
    }

    private static String refreshConfiguredApiKey(OverlayState s) {
        ToolState.reloadBreakBlocksApiKey();
        String key = ToolState.breakBlocksApiKey();
        if (!Objects.equals(s.lastConfiguredApiKey, key)) {
            s.lastConfiguredApiKey = key;
            s.apiKeyDisabledForSession = false;
            s.apiKeyDisabledReason = "";
            s.apiKeyCheckedThisSession = false;
            s.apiKeyAcceptedThisSession = false;
            s.apiKeyResponseWithoutAuthStatus = false;
            s.breakBlocksPaidTier = false;
            s.browserPaidTier = false;
            BreakBlocksAccount.configure(key);
        }
        return key;
    }

    static HttpRequest buildProviderRequest(URI uri) {
        return baseRequest(uri).GET().build();
    }

    private static HttpRequest.Builder baseRequest(URI uri) {
        return HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(12))
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT);
    }

    private static void sendSimpleProviderRequest(OverlayState s, Provider provider, URI uri) {
        HttpRequest request = buildProviderRequest(uri);
        HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, error) -> Reflection.execute(s.client,
                        () -> handleSimpleProviderResponse(s, provider, response, error)));
    }

    private static void handleBreakBlocksPage(OverlayState s, int page, URI uri, String apiKey,
                                               HttpResponse<String> response, Throwable error) {
        if (!s.open) { s.loading = false; return; }
        if (!Objects.equals(apiKey, refreshConfiguredApiKey(s)) && !apiKey.isBlank()) {
            sendBreakBlocksPage(s, page, uri, true);
            return;
        }
        boolean authenticated = !apiKey.isBlank();
        if (error != null) { providerFailure(s, Provider.BREAKBLOCKS, rootMessage(error)); return; }
        if (response == null) { providerFailure(s, Provider.BREAKBLOCKS, "No response"); return; }

        BreakBlocksRateBudget.observe(response, authenticated);

        int status = response.statusCode();
        if (authenticated && (status == 401 || status == 403)) {
            s.apiKeyDisabledForSession = true;
            s.apiKeyDisabledReason = status == 401 ? "Key rejected (401)" : "Access forbidden (403)";
            s.apiKeyCheckedThisSession = true;
            s.apiKeyResponseWithoutAuthStatus = false;
            BreakBlocksAccount.rejected();
            setStatus(s, "BreakBlocks " + s.apiKeyDisabledReason + "; retrying anonymously…");
            sendBreakBlocksPage(s, page, uri, false);
            return;
        }
        if (status == 429) {
            BreakBlocksRateBudget.noteRateLimit(response);
            String retry = response.headers().firstValue("Retry-After").orElse("").trim();
            providerFailure(s, Provider.BREAKBLOCKS, retry.isBlank() ? "rate limit reached" : "rate limit; retry in " + retry + "s");
            return;
        }
        if (status / 100 != 2) { providerFailure(s, Provider.BREAKBLOCKS, "HTTP " + status); return; }
        try {
            SearchResult parsed = parseSearchResult(response.body());
            if (authenticated && parsed.authStatusPresent()) BreakBlocksAccount.observe(parsed.authed(), parsed.patreonTier());
            s.apiKeyCheckedThisSession = authenticated && parsed.authStatusPresent();
            s.apiKeyAcceptedThisSession = authenticated && parsed.authStatusPresent() && parsed.authed();
            s.apiKeyResponseWithoutAuthStatus = authenticated && !parsed.authStatusPresent();
            s.breakBlocksPatreonTier = parsed.patreonTier();
            s.breakBlocksPaidTier = authenticated && BreakBlocksAccount.snapshot().paid();
            int matchingPages = pageCountForResults(parsed.filtered(), API_LIMIT);
            int publicLimit = s.serverTypeIndex == 2 ? 2 : MAX_PUBLIC_PAGES;
            s.breakBlocksAvailablePages = s.breakBlocksPaidTier
                    ? matchingPages : Math.min(publicLimit, matchingPages);
            prepareBreakBlocksPageOrder(s, maxBreakBlocksPages(s));
            s.breakBlocksCurrentPage = page;
            s.breakBlocksApiResults += parsed.servers().size();
            if (parsed.servers().isEmpty()) {
                if (s.breakBlocksPageOrder.isEmpty()) providerExhausted(s, Provider.BREAKBLOCKS);
                else fetchUntilBatchFull(s);
                return;
            }
            setStatus(s, breakBlocksProgressLabel(s, "received"));
            processProviderRecords(s, Provider.BREAKBLOCKS, parsed.servers(), true);
        } catch (Throwable t) {
            providerFailure(s, Provider.BREAKBLOCKS, "could not read response: " + rootMessage(t));
        }
    }

    private static void handleSimpleProviderResponse(OverlayState s, Provider provider,
                                                     HttpResponse<String> response, Throwable error) {
        if (!s.open) { s.loading = false; return; }
        if (error != null) { providerFailure(s, provider, rootMessage(error)); return; }
        if (response == null) { providerFailure(s, provider, "No response"); return; }
        int status = response.statusCode();
        if (status == 429) {
            String retry = response.headers().firstValue("Retry-After").orElse("").trim();
            providerFailure(s, provider, retry.isBlank() ? "rate limit reached" : "rate limit; retry in " + retry + "s");
            return;
        }
        if (status / 100 != 2) { providerFailure(s, provider, "HTTP " + status); return; }
        try {
            List<ServerRecord> records = provider == Provider.CORNBREAD
                    ? parseCornbreadResult(response.body())
                    : parseMineScanResult(response.body());
            if (records.isEmpty()) {
                int streak = s.providerDuplicateOnlyStreaks.getOrDefault(provider, 0) + 1;
                s.providerDuplicateOnlyStreaks.put(provider, streak);
                if (streak >= 2) providerExhausted(s, provider);
                else fetchUntilBatchFull(s);
                return;
            }
            processProviderRecords(s, provider, records, false);
        } catch (Throwable t) {
            providerFailure(s, provider, "could not read response: " + rootMessage(t));
        }
    }

    private static void processProviderRecords(OverlayState s, Provider provider, List<ServerRecord> records, boolean paged) {
        // Provider APIs commonly return stable ordering. Randomizing before
        // filtering and slicing means simultaneous clients are unlikely to
        // verify, display, auto-add, and contribute the same small subset.
        List<ServerRecord> shuffledRecords = new ArrayList<>(records);
        Collections.shuffle(shuffledRecords, ThreadLocalRandom.current());
        List<ServerRecord> candidates = new ArrayList<>();
        int newApiEndpoints = 0;
        for (ServerRecord r : shuffledRecords) {
            String endpoint = normalizeEndpoint(r.endpoint());
            if (endpoint.isBlank() || !s.seenEndpoints.add(endpoint)) continue;
            newApiEndpoints++;
            if (r.whitelisted()) continue;
            if (s.serverTypeIndex == 1 && r.offlineMode()) continue;
            if (s.serverTypeIndex == 2 && !r.offlineMode()) continue;
            if (r.playersOnline() < MIN_PLAYER_OPTIONS[s.minIndex] || r.playersOnline() > MAX_PLAYER_OPTIONS[s.maxIndex]) continue;
            if (ToolState.isBlocked(endpoint)) continue;
            if (ToolState.skipAddedHistory && ToolState.wasAdded(endpoint)) continue;
            try { if (ServerListBridge.contains(s.client, endpoint)) continue; } catch (Throwable ignored) {}
            candidates.add(r);
            if (s.sourceIndex == 1 && candidates.size() >= ALL_SOURCE_SLICE) break;
        }

        if (newApiEndpoints == 0) {
            int streak = s.providerDuplicateOnlyStreaks.getOrDefault(provider, 0) + 1;
            s.providerDuplicateOnlyStreaks.put(provider, streak);
            if (paged || streak >= 3) providerExhausted(s, provider);
            else fetchUntilBatchFull(s);
            return;
        }
        s.providerDuplicateOnlyStreaks.put(provider, 0);
        if (candidates.isEmpty()) { fetchUntilBatchFull(s); return; }
        if (ToolState.quickSearch) {
            acceptQuickCandidates(s, provider, candidates);
            return;
        }
        verifyProviderCandidatesTwice(s, provider, candidates);
    }

    private static void acceptQuickCandidates(OverlayState s, Provider provider, List<ServerRecord> candidates) {
        List<ServerRecord> accepted = new ArrayList<>();
        for (ServerRecord record : candidates) {
            if (!versionMatches(s, record) || ToolState.isBlocked(record.endpoint())) continue;
            ServerRecord unverified = record.withSource(record.source() + " (unverified)");
            s.currentBatch.add(unverified);
            accepted.add(unverified);
        }
        scheduleAuthChecks(s, accepted);
        System.out.println("[Zazu's Server Seeker] Quick Search accepted " + s.currentBatch.size()
                + " unverified " + provider.label + " result(s) without status probing.");
        finishBatch(s);
    }

    private static void providerFailure(OverlayState s, Provider provider, String reason) {
        s.disabledProviders.add(provider);
        if (canFailOver(s)) {
            setStatus(s, provider.label + " unavailable (" + shorten(reason, 35) + "); trying another source…");
            s.activeProvider = null;
            fetchUntilBatchFull(s);
        } else {
            failSearch(s, provider.label + " failed: " + reason);
        }
    }

    private static void providerExhausted(OverlayState s, Provider provider) {
        s.disabledProviders.add(provider);
        s.activeProvider = null;
        // Let fetchUntilBatchFull perform the single provider-selection step.
        // Probing with chooseProviderForNextRequest here would advance the
        // All Sources round-robin cursor twice and skip a healthy provider.
        fetchUntilBatchFull(s);
    }

    private static boolean canFailOver(OverlayState s) {
        return s.sourceIndex == 0 || s.sourceIndex == 1;
    }

    /**
     * Provider data is discovery input only. A candidate is not displayed or
     * saved until two independent Java status handshakes succeed roughly one
     * second apart. This prevents stale provider records / briefly-open ports
     * from becoming Scanned Servers.
     */
    private static void verifyProviderCandidatesTwice(OverlayState s, Provider provider,
                                                       List<ServerRecord> candidates) {
        if (!s.open) { s.loading = false; return; }

        List<ServerRecord> filtered = new ArrayList<>();
        for (ServerRecord r : candidates) {
            if (!versionMatches(s, r) || ToolState.isBlocked(r.endpoint())) continue;
            filtered.add(r);
        }
        if (filtered.isEmpty()) { fetchUntilBatchFull(s); return; }

        List<String> endpoints = filtered.stream().map(ServerRecord::endpoint).toList();
        final long verificationStarted = System.nanoTime();
        final long firstPassStarted = System.nanoTime();
        s.statusProbeAttempts += endpoints.size();
        if (provider == Provider.BREAKBLOCKS) s.breakBlocksProbeAttempts += endpoints.size();
        setStatus(s, "Verifying " + endpoints.size() + " candidate(s) — status check 1/2…");

        final long token = s.searchGeneration;
        VanillaStatusProbe.probe(s.client, s.screen, endpoints, () ->
                s.open && s.loading && s.searchGeneration == token
        ).whenComplete((firstResults, firstError) -> Reflection.execute(s.client, () -> {
            if (!s.open || !s.loading || s.searchGeneration != token) return;

            Map<String, VanillaStatusProbe.Result> firstByEndpoint = resultMap(firstResults);
            List<ServerRecord> firstPassed = new ArrayList<>();
            for (ServerRecord record : filtered) {
                VanillaStatusProbe.Result result = firstByEndpoint.get(normalizeEndpoint(record.endpoint()));
                if (result != null && result.replied()) {
                    s.statusFirstPasses++;
                    if (provider == Provider.BREAKBLOCKS) s.breakBlocksStatusReplies++;
                    firstPassed.add(record.withLiveVersion(result.version(), result.protocol()));
                } else {
                    recordProbeFailure(s, provider, result);
                }
            }

            logProbePass(provider, 1, filtered.size(), firstPassed.size(), firstPassStarted);

            if (firstPassed.isEmpty()) {
                s.statusRejected += filtered.size();
                setStatus(s, "Rejected " + filtered.size() + " stale/unreachable candidate(s); searching next batch…");
                fetchUntilBatchFull(s);
                return;
            }

            setStatus(s, "Status check 1/2 passed " + firstPassed.size() + "/" + filtered.size()
                    + "; confirming in 1 second…");

            CompletableFuture.delayedExecutor(SECOND_STATUS_CONFIRM_DELAY_MS, TimeUnit.MILLISECONDS).execute(() ->
                    Reflection.execute(s.client, () -> {
                        if (!s.open || !s.loading || s.searchGeneration != token) return;

                        List<String> confirmEndpoints = firstPassed.stream().map(ServerRecord::endpoint).toList();
                        final long secondPassStarted = System.nanoTime();
                        s.statusProbeAttempts += confirmEndpoints.size();
                        if (provider == Provider.BREAKBLOCKS) s.breakBlocksProbeAttempts += confirmEndpoints.size();
                        setStatus(s, "Verifying " + confirmEndpoints.size() + " candidate(s) — status check 2/2…");

                        VanillaStatusProbe.probe(s.client, s.screen, confirmEndpoints, () ->
                                s.open && s.loading && s.searchGeneration == token
                        ).whenComplete((secondResults, secondError) -> Reflection.execute(s.client, () -> {
                            if (!s.open || !s.loading || s.searchGeneration != token) return;

                            Map<String, VanillaStatusProbe.Result> secondByEndpoint = resultMap(secondResults);
                            List<ServerRecord> verified = new ArrayList<>();
                            for (ServerRecord record : firstPassed) {
                                VanillaStatusProbe.Result result = secondByEndpoint.get(normalizeEndpoint(record.endpoint()));
                                if (result != null && result.replied()) {
                                    ServerRecord confirmed = record.withLiveVersion(result.version(), result.protocol());
                                    if (!versionMatches(s, confirmed)) {
                                        if (provider == Provider.BREAKBLOCKS) s.breakBlocksIncompatibleReplies++;
                                        continue;
                                    }
                                    s.statusSecondPasses++;
                                    s.liveVerified++;
                                    if (provider == Provider.BREAKBLOCKS) {
                                        s.breakBlocksStatusReplies++;
                                        s.breakBlocksLiveVerified++;
                                    }
                                    verified.add(confirmed);
                                } else {
                                    recordProbeFailure(s, provider, result);
                                }
                            }

                            logProbePass(provider, 2, firstPassed.size(), verified.size(), secondPassStarted);
                            System.out.println("[Zazu's Server Seeker] " + provider.label
                                    + " verification finished: " + verified.size() + "/" + filtered.size()
                                    + " accepted in " + elapsedMillis(verificationStarted) + " ms");

                            s.statusRejected += filtered.size() - verified.size();
                            if (verified.isEmpty()) {
                                setStatus(s, "Second status check rejected remaining candidates; searching again…");
                                fetchUntilBatchFull(s);
                                return;
                            }

                            acceptVerifiedCandidates(s, provider, verified);
                        }));
                    }));
        }));
    }

    private static void logProbePass(Provider provider, int pass, int attempted, int replied, long started) {
        System.out.println("[Zazu's Server Seeker] " + provider.label + " status check " + pass
                + "/2: " + replied + "/" + attempted + " replied in " + elapsedMillis(started) + " ms");
    }

    private static long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private static Map<String, VanillaStatusProbe.Result> resultMap(List<VanillaStatusProbe.Result> results) {
        Map<String, VanillaStatusProbe.Result> out = new HashMap<>();
        if (results == null) return out;
        for (VanillaStatusProbe.Result result : results) {
            if (result == null) continue;
            out.put(normalizeEndpoint(result.endpoint()), result);
        }
        return out;
    }

    private static void recordProbeFailure(OverlayState s, Provider provider, VanillaStatusProbe.Result result) {
        if (result == null) {
            s.statusProbeErrors++;
            if (provider == Provider.BREAKBLOCKS) s.breakBlocksProbeErrors++;
            return;
        }
        s.lastProbeFailure = result.detail() == null ? "" : result.detail();
        if (provider == Provider.BREAKBLOCKS) s.breakBlocksLastProbeFailure = s.lastProbeFailure;
        switch (result.failure()) {
            case DNS -> {
                s.statusDnsFailures++;
                if (provider == Provider.BREAKBLOCKS) s.breakBlocksDnsFailures++;
            }
            case UNREACHABLE -> {
                s.statusUnreachableFailures++;
                if (provider == Provider.BREAKBLOCKS) s.breakBlocksUnreachableFailures++;
            }
            case TIMEOUT -> {
                s.statusTimeoutFailures++;
                if (provider == Provider.BREAKBLOCKS) s.breakBlocksTimeoutFailures++;
            }
            case ERROR -> {
                s.statusProbeErrors++;
                if (provider == Provider.BREAKBLOCKS) s.breakBlocksProbeErrors++;
            }
            case NONE -> {}
        }
    }

    private static void acceptVerifiedCandidates(OverlayState s, Provider provider, List<ServerRecord> verified) {
        scheduleAuthChecks(s, verified);
        if (ToolState.contributeVerifiedServers) {
            for (ServerRecord record : verified) {
                BreakBlocksContributor.submit(record.address(), record.port(),
                        provider == Provider.BREAKBLOCKS ? record.lastPing() : "");
            }
        }

        for (ServerRecord r : verified) {
            if (s.currentBatch.size() < DISPLAY_RESULTS) s.currentBatch.add(r);
        }

        if (provider == Provider.BREAKBLOCKS) {
            setStatus(s, breakBlocksProgressLabel(s, verified.size() + " double-verified live"));
        } else {
            setStatus(s, provider.label + ": " + verified.size() + " double-verified live server(s)");
        }

        if (s.autoAdd) {
            for (ServerRecord r : verified) {
                if (!s.open || !s.autoAdd) break;
                int limit = ToolState.autoAddLimit;
                if (limit > 0 && s.autoAddedThisSession >= limit) {
                    s.autoAdd = false;
                    Reflection.setButtonText(s.autoButton, autoLabel(s));
                    setStatus(s, "Auto-add limit reached (" + limit + ").");
                    break;
                }
                try {
                    if (ServerListBridge.add(s.client, r)) {
                        s.autoAddedThisSession++;
                        System.out.println("[Zazu's Server Seeker] Added double-verified server " + r.endpoint());
                    }
                } catch (Throwable t) { log("Server-list change failed: " + r.endpoint(), t); }
            }
        }
        continueAfterLiveChecks(s);
    }

    private static void scheduleAuthChecks(OverlayState s, Collection<ServerRecord> records) {
        if (!ToolState.authDetectionEnabled || records == null || records.isEmpty()) return;
        for (ServerRecord record : records) {
            if (record == null) continue;
            ServerAuthService.ensureAsync(s.client, record.endpoint(), record.protocol(), () -> {
                if (!s.open) return;
                refreshRows(s);
            });
        }
    }

    private static void continueAfterLiveChecks(OverlayState s) {
        if (s.currentBatch.size() >= DISPLAY_RESULTS || s.exhausted) finishBatch(s);
        else fetchUntilBatchFull(s);
    }

    private static void finishBatch(OverlayState s) {
        s.loading = false;
        LinkedHashMap<String, ServerRecord> accumulated = new LinkedHashMap<>();
        for (ServerRecord record : s.results) accumulated.put(normalizeEndpoint(record.endpoint()), record);
        for (ServerRecord record : s.currentBatch) accumulated.putIfAbsent(normalizeEndpoint(record.endpoint()), record);
        List<ServerRecord> sorted = new ArrayList<>(accumulated.values());
        sortRecords(s, sorted);
        s.results = List.copyOf(sorted);
        s.resultPage = Math.max(0, Math.min(s.resultPage, resultPageCount(s) - 1));
        refreshRows(s);
        String suffix = s.exhausted ? " — provider pool exhausted" : " — source: " + sourceLabel(s);
        String diagnostics = ToolState.quickSearch
                ? " — unverified provider results"
                : " — verified " + s.liveVerified + " — rejected " + s.statusRejected;
        if (s.breakBlocksCurrentPage > 0) {
            diagnostics += " — BB p" + s.breakBlocksCurrentPage + " " + s.breakBlocksApiResults + " API";
        }
        setStatus(s, s.results.size() + " results — page " + (s.resultPage + 1) + "/" + resultPageCount(s)
                + "; " + s.autoAddedThisSession + " added" + suffix + diagnostics);
        refreshStats(s);
        if (s.autoAdd) {
            long delay = s.exhausted ? AUTO_ADD_AFTER_EXHAUSTED_MS : AUTO_ADD_BETWEEN_BATCHES_MS;
            scheduleNextAutoAddBatch(s, delay, s.exhausted, null);
        }
    }

    private static void scheduleNextAutoAddBatch(OverlayState s, long delayMs, boolean resetPool, String status) {
        if (!s.open || !s.autoAdd) return;
        if (ToolState.autoAddLimit > 0 && s.autoAddedThisSession >= ToolState.autoAddLimit) {
            s.autoAdd = false;
            s.autoAddScheduleToken++;
            Reflection.setButtonText(s.autoButton, autoLabel(s));
            setStatus(s, "Auto-add limit reached (" + ToolState.autoAddLimit + ").");
            return;
        }
        final long token = ++s.autoAddScheduleToken;
        if (status != null && !status.isBlank()) setStatus(s, status);
        CompletableFuture.delayedExecutor(Math.max(0L, delayMs), TimeUnit.MILLISECONDS).execute(() ->
                Reflection.execute(s.client, () -> {
                    if (!s.open || !s.autoAdd || s.loading || s.autoAddScheduleToken != token) return;
                    if (ToolState.autoAddLimit > 0 && s.autoAddedThisSession >= ToolState.autoAddLimit) {
                        s.autoAdd = false;
                        s.autoAddScheduleToken++;
                        Reflection.setButtonText(s.autoButton, autoLabel(s));
                        setStatus(s, "Auto-add limit reached (" + ToolState.autoAddLimit + ").");
                        return;
                    }
                    if (resetPool || s.exhausted) {
                        resetSearchState(s, "Auto-add refreshing search pool…");
                    }
                    findNewServers(s);
                }));
    }

    private static void sortRecords(OverlayState s, List<ServerRecord> records) {
        Comparator<ServerRecord> comparator = switch (s.sortIndex) {
            case 1 -> Comparator.comparingInt(ServerRecord::playersOnline).reversed();
            case 2 -> Comparator.comparing(ServerRecord::version, String.CASE_INSENSITIVE_ORDER);
            case 3 -> Comparator.comparing(ServerRecord::address, String.CASE_INSENSITIVE_ORDER);
            case 4 -> Comparator.comparingInt(ServerRecord::port);
            default -> null;
        };
        if (comparator != null) records.sort(comparator);
    }

    private static void failSearch(OverlayState s, String message) {
        s.loading = false;
        setStatus(s, message);
        if (s.autoAdd) {
            scheduleNextAutoAddBatch(s, AUTO_ADD_AFTER_FAILURE_MS, true, message + " — Auto-add retrying in 15s…");
        }
    }

    private static void refreshRows(OverlayState s) {
        int rows = finderRowsPerPage(s);
        int start = s.resultPage * rows;
        for (int i = 0; i < rows; i++) {
            int resultIndex = start + i;
            boolean has = resultIndex < s.results.size();
            Object row = s.resultButtons.size() > i ? s.resultButtons.get(i) : null;
            Object add = s.resultAddButtons.size() > i ? s.resultAddButtons.get(i) : null;
            Object detail = s.resultDetailButtons.size() > i ? s.resultDetailButtons.get(i) : null;
            Reflection.setBoolean(row, "visible", has && s.mainVisible); Reflection.setBoolean(row, "active", has && s.mainVisible);
            Reflection.setBoolean(add, "visible", has && s.mainVisible); Reflection.setBoolean(add, "active", has && s.mainVisible);
            Reflection.setBoolean(detail, "visible", has && s.mainVisible); Reflection.setBoolean(detail, "active", has && s.mainVisible);
            if (has) {
                ServerRecord r = s.results.get(resultIndex);
                String verification = r.source().endsWith(" (unverified)") ? "UNVERIFIED" : "VERIFIED";
                String auth = ToolState.authDetectionEnabled ? ServerAuthService.shortLabel(r.endpoint()) : "OFF";
                String text = shorten(r.endpoint() + " | AUTH:" + auth + " | " + r.version() + " | " + r.playersOnline()
                        + "/" + r.playersMax() + " | " + verification, 58);
                Reflection.setButtonText(row, text);
                try { Reflection.setButtonText(add, ServerListBridge.contains(s.client, r.endpoint()) ? "Saved" : "Add"); }
                catch (Throwable ignored) { Reflection.setButtonText(add, "Add"); }
            }
        }
        int pages = resultPageCount(s);
        Reflection.setButtonText(s.resultPageButton, "Page " + (s.resultPage + 1) + "/" + pages);
        Reflection.setBoolean(s.previousResultsButton, "visible", s.mainVisible && !s.results.isEmpty());
        Reflection.setBoolean(s.nextResultsButton, "visible", s.mainVisible && !s.results.isEmpty());
        Reflection.setBoolean(s.resultPageButton, "visible", s.mainVisible && !s.results.isEmpty());
        Reflection.setBoolean(s.previousResultsButton, "active", s.resultPage > 0);
        Reflection.setBoolean(s.nextResultsButton, "active", s.resultPage + 1 < pages);
    }

    private static int resultPageCount(OverlayState s) {
        int rows = finderRowsPerPage(s);
        return Math.max(1, (s.results.size() + rows - 1) / rows);
    }

    private static int visibleResultIndex(OverlayState s, int row) {
        return s.resultPage * finderRowsPerPage(s) + row;
    }

    private static void changeResultPage(OverlayState s, int direction) {
        int next = Math.max(0, Math.min(s.resultPage + direction, resultPageCount(s) - 1));
        if (next == s.resultPage) return;
        s.resultPage = next;
        refreshRows(s);
        setStatus(s, s.results.size() + (ToolState.quickSearch ? " quick" : " verified")
                + " results — page " + (s.resultPage + 1) + "/" + resultPageCount(s));
    }

    private static void addVisibleResult(OverlayState s, int row) { addResult(s, visibleResultIndex(s, row)); }
    private static void toggleVisibleResult(OverlayState s, int row) { toggleResult(s, visibleResultIndex(s, row)); }
    private static void showVisibleDetails(OverlayState s, int row) { s.finderDetailsPage = 0; showDetails(s, visibleResultIndex(s, row)); }

    private static void addResult(OverlayState s, int index) {
        if (index < 0 || index >= s.results.size()) return;
        ServerRecord record = s.results.get(index);
        if (ToolState.isBlocked(record.endpoint())) { setStatus(s, "That server is blocked. Open Details to unblock it."); return; }
        try {
                if (ServerListBridge.add(s.client, record)) setStatus(s, "Added " + record.endpoint());
                else if (ServerListBridge.contains(s.client, record.endpoint())) setStatus(s, "Server is already saved.");
                else setStatus(s, "Server could not be added.");
                refreshRows(s); refreshStats(s);
        } catch (Throwable t) { setStatus(s, "Server-list change failed: " + rootMessage(t)); }
    }

    private static void toggleResult(OverlayState s, int index) {
        if (index >= 0 && index < s.results.size()) { s.finderDetailsPage = 0; showDetails(s, index); }
    }

    private static void hideMain(OverlayState s) {
        s.mainVisible = false;
        clearOptionMenu(s);
        for (Object w : s.widgets) Reflection.setBoolean(w, "visible", false);
    }
    private static void showMain(OverlayState s) {
        s.mainVisible = true;
        s.apiKeyTestOpen = false;
        for (Object w : s.widgets) Reflection.setBoolean(w, "visible", true);
        refreshRows(s);
    }
    private static void clearSubView(OverlayState s) {
        clearOptionMenu(s);
        try { Reflection.widgets(s.screen).removeAll(s.subWidgets); } catch (Throwable ignored) {}
        s.subWidgets.clear();
    }

    private static void openBrowser(OverlayState s) {
        s.autoAdd = false;
        s.autoAddScheduleToken++;
        Reflection.setButtonText(s.autoButton, autoLabel(s));
        s.browserVersionIndex = s.versionIndex;
        s.browserMinIndex = s.minIndex;
        s.browserMaxIndex = s.maxIndex;
        s.browserSortIndex = s.sortIndex;
        s.browserTypeIndex = s.serverTypeIndex;
        s.browserAgeDays = normalizedBreakBlocksAgeDays(ToolState.breakBlocksMaxAgeDays);
        s.browserSearchFilters = ToolState.activeSearchFilters();
        s.browserPage = 1;
        s.browserRowPage = 0;
        s.browserResults = List.of();
        s.browserOpen = true;
        requestBrowserPage(s, 1, true);
    }

    private static void closeBrowser(OverlayState s) {
        s.browserOpen = false;
        s.browserLoading = false;
        s.browserRequestToken++;
        clearSubView(s);
        showMain(s);
    }

    private static void browserFilterChanged(OverlayState s) {
        s.browserRequestToken++;
        s.browserLoading = false;
        s.browserResults = List.of();
        s.browserRowPage = 0;
        s.browserAvailablePages = 1;
        s.browserPaidTier = false;
        requestBrowserPage(s, 1, true);
    }

    private static void requestBrowserPage(OverlayState s, int page, boolean clearResults) {
        if (!s.open || !s.browserOpen) return;
        if (s.browserMinIndex < 0 || s.browserMaxIndex < 0
                || MIN_PLAYER_OPTIONS[s.browserMinIndex] > MAX_PLAYER_OPTIONS[s.browserMaxIndex]) {
            s.browserLoading = false;
            s.browserStatus = "Minimum players cannot be above maximum players.";
            showBrowser(s);
            return;
        }
        if (s.browserLoading) {
            // A changed filter supersedes its previous request. The old response
            // is discarded by browserRequestToken when it eventually arrives.
            s.browserRequestToken++;
        }
        String configuredKey = refreshConfiguredApiKey(s);
        String key = s.apiKeyDisabledForSession ? "" : configuredKey;
        boolean authenticated = !key.isBlank();
        BreakBlocksRateBudget.Snapshot allowance = BreakBlocksRateBudget.snapshot(authenticated);
        if (allowance.paused() || (!allowance.unlimited() && allowance.remaining() <= 0)) {
            s.browserLoading = false;
            s.browserStatus = "BreakBlocks allowance reached; retry in " + allowance.resetSeconds() + "s.";
            showBrowser(s);
            return;
        }
        s.browserPage = Math.max(1, page);
        s.browserRowPage = 0;
        if (clearResults) s.browserResults = List.of();
        s.browserLoading = true;
        s.browserStatus = "Loading BreakBlocks page " + s.browserPage + "...";
        long token = ++s.browserRequestToken;
        showBrowser(s);
        URI uri = buildBrowserPageUri(s.browserVersionIndex, s.browserMinIndex, s.browserMaxIndex,
                s.browserSortIndex, s.browserTypeIndex, s.browserAgeDays, s.browserPage, s.browserSearchFilters);
        sendBrowserRequest(s, uri, authenticated ? key : "", token);
    }

    private static void sendBrowserRequest(OverlayState s, URI uri, String apiKey, long token) {
        boolean authenticated = apiKey != null && !apiKey.isBlank();
        BreakBlocksRateBudget.recordSearchRequest(authenticated);
        HTTP.sendAsync(buildBreakBlocksRequest(uri, apiKey), HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, error) -> Reflection.execute(s.client,
                        () -> handleBrowserResponse(s, uri, apiKey, token, response, error)));
    }

    private static void handleBrowserResponse(OverlayState s, URI uri, String apiKey, long token,
                                              HttpResponse<String> response, Throwable error) {
        if (!s.open || !s.browserOpen || token != s.browserRequestToken) return;
        if (!Objects.equals(apiKey, refreshConfiguredApiKey(s)) && !apiKey.isBlank()) {
            requestBrowserPage(s, s.browserPage, false);
            return;
        }
        boolean authenticated = !apiKey.isBlank();
        if (error != null || response == null) {
            browserFailure(s, "BreakBlocks request failed: " + (error == null ? "no response" : rootMessage(error)));
            return;
        }
        BreakBlocksRateBudget.observe(response, authenticated);
        int status = response.statusCode();
        if (authenticated && (status == 401 || status == 403)) {
            s.apiKeyDisabledForSession = true;
            s.apiKeyDisabledReason = status == 401 ? "Key rejected (401)" : "Access forbidden (403)";
            s.apiKeyResponseWithoutAuthStatus = false;
            BreakBlocksAccount.rejected();
            BreakBlocksRateBudget.Snapshot allowance = BreakBlocksRateBudget.snapshot(false);
            if (allowance.paused() || allowance.remaining() <= 0) {
                browserFailure(s, s.apiKeyDisabledReason + "; anonymous allowance unavailable. Retry later.");
            } else {
                if (s.browserPage > (s.browserTypeIndex == 2 ? 2 : MAX_PUBLIC_PAGES)) {
                    s.browserPage = 1;
                    uri = buildBrowserPageUri(s.browserVersionIndex, s.browserMinIndex, s.browserMaxIndex,
                            s.browserSortIndex, s.browserTypeIndex, s.browserAgeDays, 1, s.browserSearchFilters);
                }
                s.browserStatus = s.apiKeyDisabledReason + "; retrying anonymously...";
                showBrowser(s);
                sendBrowserRequest(s, uri, "", token);
            }
            return;
        }
        if (status == 429) {
            BreakBlocksRateBudget.noteRateLimit(response);
            browserFailure(s, "BreakBlocks rate limit reached. Retry when the allowance resets.");
            return;
        }
        if (status / 100 != 2) {
            browserFailure(s, "BreakBlocks returned HTTP " + status + ".");
            return;
        }
        try {
            SearchResult parsed = parseSearchResult(response.body());
            if (authenticated && parsed.authStatusPresent()) BreakBlocksAccount.observe(parsed.authed(), parsed.patreonTier());
            s.apiKeyCheckedThisSession = authenticated && parsed.authStatusPresent();
            s.apiKeyAcceptedThisSession = authenticated && parsed.authStatusPresent() && parsed.authed();
            s.apiKeyResponseWithoutAuthStatus = authenticated && !parsed.authStatusPresent();
            s.browserPaidTier = authenticated && BreakBlocksAccount.snapshot().paid();
            s.browserFiltered = parsed.filtered();
            s.browserAvailablePages = browserPageLimit(parsed.filtered(), s.browserPaidTier, s.browserTypeIndex == 2);
            List<ServerRecord> visible = new ArrayList<>();
            int hiddenWhitelisted = 0;
            for (ServerRecord record : parsed.servers()) {
                if (record.whitelisted()) { hiddenWhitelisted++; continue; }
                if (s.browserTypeIndex == 1 && record.offlineMode()) continue;
                if (s.browserTypeIndex == 2 && !record.offlineMode()) continue;
                visible.add(record);
            }
            s.browserHiddenWhitelisted = hiddenWhitelisted;
            s.browserResults = List.copyOf(visible);
            s.browserLoading = false;
            s.browserStatus = "BreakBlocks page " + s.browserPage + "/" + s.browserAvailablePages
                    + " | " + parsed.filtered() + " matching | " + visible.size() + " shown"
                    + (s.browserHiddenWhitelisted > 0 ? " | " + s.browserHiddenWhitelisted + " whitelisted hidden" : "");
            showBrowser(s);
        } catch (Throwable t) {
            browserFailure(s, "Could not read BreakBlocks response: " + rootMessage(t));
        }
    }

    private static void browserFailure(OverlayState s, String message) {
        s.browserLoading = false;
        s.browserStatus = message;
        showBrowser(s);
    }

    static int browserPageLimit(int filtered, boolean paid, boolean crackedOnly) {
        int matchingPages = pageCountForResults(filtered, API_LIMIT);
        return paid ? matchingPages : Math.min(crackedOnly ? 2 : MAX_PUBLIC_PAGES, matchingPages);
    }

    private static void browsePrevious(OverlayState s) {
        if (s.browserLoading) return;
        if (s.browserRowPage > 0) { s.browserRowPage--; showBrowser(s); }
        else if (s.browserPage > 1) requestBrowserPage(s, s.browserPage - 1, true);
    }

    private static void browseNext(OverlayState s) {
        if (s.browserLoading) return;
        if ((s.browserRowPage + 1) * browserRowsPerPage(s) < s.browserResults.size()) {
            s.browserRowPage++;
            showBrowser(s);
        } else if (s.browserPage < s.browserAvailablePages) requestBrowserPage(s, s.browserPage + 1, true);
    }

    static int browserRowsPerPage(OverlayState s) {
        return Math.max(1, Math.min(DISPLAY_RESULTS, (s.height - 149) / 23));
    }

    private static void showBrowser(OverlayState s) {
        if (!s.open || !s.browserOpen) return;
        try {
            clearSubView(s); hideMain(s);
            int contentWidth = Math.min(452, Math.max(260, s.width - 12));
            int x = (s.width - contentWidth) / 2, y = 30;
            int versionWidth = Math.max(76, contentWidth * 108 / 452);
            int minWidth = Math.max(56, contentWidth * 84 / 452);
            int maxWidth = minWidth;
            int typeWidth = contentWidth - versionWidth - minWidth - maxWidth - 12;
            addSubLabel(s, "Browse BreakBlocks", x, y, contentWidth - 120);
            subButton(s, "Back to Finder", x + contentWidth - 118, y, 118,
                    "Return to the scanner. Auto-add remains off until you turn it back on.", b -> closeBrowser(s));
            y += 24;
            final int firstMenuY = y + 21;
            subButton(s, "Version: " + (VERSION_OPTIONS[s.browserVersionIndex].equals("*") ? "Any" : VERSION_OPTIONS[s.browserVersionIndex]),
                    x, y, versionWidth, "Choose a version to browse.", b -> openSubOptionMenu(s, x, firstMenuY, versionWidth,
                            versionOptionLabels(), s.browserVersionIndex, selected -> {
                                s.browserVersionIndex = selected; browserFilterChanged(s);
                            }));
            subButton(s, "Min: " + MIN_PLAYER_OPTIONS[s.browserMinIndex], x + versionWidth + 4, y, minWidth,
                    "Minimum player count.", b -> openSubOptionMenu(s, x + versionWidth + 4, firstMenuY, minWidth,
                            integerLabels(MIN_PLAYER_OPTIONS, false), s.browserMinIndex, selected -> {
                                s.browserMinIndex = selected; browserFilterChanged(s);
                            }));
            subButton(s, "Max: " + (MAX_PLAYER_OPTIONS[s.browserMaxIndex] >= 999999 ? "Any" : MAX_PLAYER_OPTIONS[s.browserMaxIndex]),
                    x + versionWidth + minWidth + 8, y, maxWidth, "Maximum player count.", b -> openSubOptionMenu(s, x + versionWidth + minWidth + 8, firstMenuY, maxWidth,
                            integerLabels(MAX_PLAYER_OPTIONS, true), s.browserMaxIndex, selected -> {
                                s.browserMaxIndex = selected; browserFilterChanged(s);
                            }));
            subButton(s, "Type: " + SERVER_TYPE_LABELS[s.browserTypeIndex], x + versionWidth + minWidth + maxWidth + 12, y, typeWidth,
                    "Show any, premium or cracked servers.", b -> openSubOptionMenu(s, x + versionWidth + minWidth + maxWidth + 12, firstMenuY, typeWidth,
                            List.of(SERVER_TYPE_LABELS), s.browserTypeIndex, selected -> {
                                s.browserTypeIndex = selected; browserFilterChanged(s);
                            }));
            y += 24;
            final int secondMenuY = y + 21;
            int sortWidth = (contentWidth - 12) / 4, seenWidth = sortWidth;
            int reloadWidth = sortWidth;
            subButton(s, "Sort: " + SORT_LABELS[s.browserSortIndex], x, y, sortWidth,
                    "Order the API results.", b -> openSubOptionMenu(s, x, secondMenuY, sortWidth,
                            List.of(SORT_LABELS), s.browserSortIndex, selected -> {
                                s.browserSortIndex = selected; browserFilterChanged(s);
                            }));
            subButton(s, "Seen: " + s.browserAgeDays + " days", x + sortWidth + 4, y, seenWidth,
                    "Only show records seen within this many days.", b -> openSubOptionMenu(s, x + sortWidth + 4, secondMenuY, seenWidth,
                            breakBlocksAgeLabels(), browserAgeIndex(s), selected -> {
                                s.browserAgeDays = BREAKBLOCKS_AGE_OPTIONS[selected]; browserFilterChanged(s);
                            }));
            subButton(s, "Reload", x + sortWidth + seenWidth + 8, y, reloadWidth,
                    "Reload the current BreakBlocks page.", b -> requestBrowserPage(s, s.browserPage, true));
            subButton(s, filtersLabel(), x + sortWidth + seenWidth + reloadWidth + 12, y,
                    contentWidth - sortWidth - seenWidth - reloadWidth - 12,
                    "Country, region, MOTD text and modpack filters.", b -> openDetailedFilters(s, true));
            y += 24;
            addSubLabel(s, shorten(s.browserStatus, contentWidth / 6), x, y, contentWidth);
            y += 25;
            int rows = browserRowsPerPage(s);
            Set<String> saved = new HashSet<>();
            try {
                for (Object entry : ServerListBridge.servers(ServerListBridge.createLoadedList(s.client))) {
                    saved.add(normalizeEndpoint(ServerListBridge.serverEndpoint(entry)));
                }
            } catch (Throwable t) { log("Could not check saved browser entries", t); }
            for (int i = 0; i < rows; i++) {
                int index = s.browserRowPage * rows + i;
                if (index >= s.browserResults.size()) break;
                ServerRecord record = s.browserResults.get(index);
                String label = record.endpoint() + " | " + record.version() + " | "
                        + record.playersOnline() + "/" + record.playersMax() + " | " + record.motd();
                int resultWidth = contentWidth - 122;
                subButton(s, shorten(label, Math.max(20, resultWidth / 6)), x, y, resultWidth,
                        "BreakBlocks listing. Open details for the full MOTD and location.", b -> {
                            s.browserDetailsPage = 0; showBrowserDetails(s, record);
                        });
                boolean alreadySaved = saved.contains(normalizeEndpoint(record.endpoint()));
                boolean blocked = ToolState.isBlocked(record.endpoint());
                Object add = subButton(s, blocked ? "Blocked" : alreadySaved ? "Saved" : "Add", x + contentWidth - 118, y, 54,
                        "Save this server to your Servers tab.", b -> addBrowserResult(s, record));
                Reflection.setBoolean(add, "active", !alreadySaved && !blocked);
                subButton(s, "Details", x + contentWidth - 60, y, 60,
                        "Show full server details.", b -> {
                            s.browserDetailsPage = 0; showBrowserDetails(s, record);
                        });
                y += 23;
            }
            int navY = 127 + rows * 23 + 2;
            Object previous = subButton(s, "< Previous", x, navY, 90,
                    "Previous results or previous BreakBlocks page.", b -> browsePrevious(s));
            Object next = subButton(s, "Next >", x + contentWidth - 90, navY, 90,
                    "Next results or next BreakBlocks page.", b -> browseNext(s));
            Reflection.setBoolean(previous, "active", !s.browserLoading && (s.browserPage > 1 || s.browserRowPage > 0));
            Reflection.setBoolean(next, "active", !s.browserLoading
                    && ((s.browserRowPage + 1) * rows < s.browserResults.size()
                    || s.browserPage < s.browserAvailablePages));
            addSubLabel(s, "Page " + s.browserPage + "/" + s.browserAvailablePages
                    + "  (" + (s.browserResults.isEmpty() ? 0 : s.browserRowPage + 1) + "/"
                    + Math.max(1, (s.browserResults.size() + rows - 1) / rows) + ")",
                    x + 96, navY, contentWidth - 192);
        } catch (Throwable t) {
            log("Could not draw BreakBlocks browser", t);
            closeBrowser(s);
        }
    }

    private static int browserAgeIndex(OverlayState s) {
        for (int i = 0; i < BREAKBLOCKS_AGE_OPTIONS.length; i++) {
            if (BREAKBLOCKS_AGE_OPTIONS[i] == s.browserAgeDays) return i;
        }
        return BREAKBLOCKS_AGE_OPTIONS.length - 1;
    }

    private static void addBrowserResult(OverlayState s, ServerRecord record) {
        try {
            boolean added = ServerListBridge.addBrowsed(s.client, record);
            s.browserStatus = added ? "Added " + record.endpoint() + " to Servers."
                    : ServerListBridge.contains(s.client, record.endpoint()) ? "Server is already saved."
                    : "Server is blocked or could not be added.";
        } catch (Throwable t) {
            s.browserStatus = "Could not add server: " + rootMessage(t);
        }
        showBrowser(s);
    }

    private static void showBrowserDetails(OverlayState s, ServerRecord record) {
        if (!s.browserOpen) return;
        try {
            clearSubView(s); hideMain(s);
            int contentWidth = Math.min(440, s.width - 12);
            int x = (s.width - contentWidth) / 2, y = 32;
            addSubLabel(s, shorten("BreakBlocks — " + record.endpoint(), contentWidth / 6), x, y, contentWidth); y += 24;
            boolean saved = ServerListBridge.contains(s.client, record.endpoint());
            boolean blocked = ToolState.isBlocked(record.endpoint());
            int actionWidth = Math.min(170, (contentWidth - 10) / 2);
            int backWidth = Math.min(155, (contentWidth - 10) / 2);
            Object add = subButton(s, saved ? "Saved" : blocked ? "Blocked" : "Add to Servers", x, y, actionWidth,
                    "Save this server to the Servers tab.", b -> {
                        addBrowserResult(s, record);
                        showBrowserDetails(s, record);
                    });
            Reflection.setBoolean(add, "active", !saved && !blocked);
            subButton(s, "Back to Browser", x + contentWidth - backWidth, y, backWidth,
                    "Return to BreakBlocks results.", b -> showBrowser(s));
            y += 28;
            String location = joinNonBlank(", ", record.city(), record.region(), record.countryCode(), record.country());
            List<String> info = List.of(
                    "Version: " + record.version() + "   Players: " + record.playersOnline() + "/" + record.playersMax(),
                    "MOTD: " + record.motd(),
                    "Location: " + (location.isBlank() ? "Unknown" : location),
                    "Last ping: " + blankDefault(record.lastPing(), "Unknown"),
                    "Modpack: " + blankDefault(record.modpack(), "None detected"),
                    "Plugins: " + (record.plugins().isEmpty() ? "None reported" : String.join(", ", record.plugins())),
                    "BreakBlocks listing — live availability has not been verified.");
            int rows = detailRowsPerPage(s), pages = (info.size() + rows - 1) / rows;
            s.browserDetailsPage = Math.max(0, Math.min(s.browserDetailsPage, pages - 1));
            for (int i = s.browserDetailsPage * rows; i < Math.min(info.size(), (s.browserDetailsPage + 1) * rows); i++) {
                addSubLabel(s, shorten(info.get(i), contentWidth / 6), x, y, contentWidth);
                y += 24;
            }
            int navY = s.height - 28;
            Object previous = subButton(s, "< Previous", x, navY, 100,
                    "Previous details page.", b -> { s.browserDetailsPage--; showBrowserDetails(s, record); });
            addSubLabel(s, "Page " + (s.browserDetailsPage + 1) + "/" + pages, x + 105, navY, contentWidth - 210);
            Object next = subButton(s, "Next >", x + contentWidth - 100, navY, 100,
                    "Next details page.", b -> { s.browserDetailsPage++; showBrowserDetails(s, record); });
            Reflection.setBoolean(previous, "active", s.browserDetailsPage > 0);
            Reflection.setBoolean(next, "active", s.browserDetailsPage + 1 < pages);
        } catch (Throwable t) {
            log("Could not show BreakBlocks details", t);
            showBrowser(s);
        }
    }

    private static void showDetails(OverlayState s, int index) {
        if (index < 0 || index >= s.results.size()) return;
        ServerRecord r = s.results.get(index);
        try {
            clearSubView(s); hideMain(s);
            int contentWidth = Math.min(440, s.width - 12), x = (s.width - contentWidth) / 2, y = 32;
            addSubLabel(s, "Server Details — " + r.endpoint(), x, y, contentWidth); y += 24;
            int firstWidth = contentWidth * 130 / 440, secondWidth = contentWidth * 105 / 440;
            int thirdWidth = contentWidth * 95 / 440, fourthWidth = contentWidth - firstWidth - secondWidth - thirdWidth - 15;
            boolean saved;
            try { saved = ServerListBridge.contains(s.client, r.endpoint()); } catch (Throwable t) { saved = false; }
            if (saved) subButton(s, "Remove Saved", x, y, firstWidth, "Remove this server from your list.", b -> removeFromDetails(s, r));
            else subButton(s, "Add Server", x, y, firstWidth, "Add this server to your list.", b -> addFromDetails(s, r));
            if (ToolState.isBlocked(r.endpoint())) subButton(s, "Unblock", x + firstWidth + 5, y, secondWidth,
                    "Allow this server in future searches.", b -> { ToolState.unblock(r.endpoint()); showDetails(s, index); });
            else subButton(s, "Block", x + firstWidth + 5, y, secondWidth,
                    "Exclude this server from future searches.", b -> { ToolState.block(r.endpoint()); showDetails(s, index); });
            Object authButton = subButton(s, ServerAuthService.isChecking(r.endpoint()) ? "Checking…" : "Recheck Auth",
                    x + firstWidth + secondWidth + 10, y, thirdWidth, "Recheck server authentication.", b -> {
                        if (ServerAuthService.recheckAsync(s.client, r.endpoint(), r.protocol(), () -> showDetails(s, index))) showDetails(s, index);
                    });
            Reflection.setBoolean(authButton, "active", ToolState.authDetectionEnabled && !ServerAuthService.isChecking(r.endpoint()));
            subButton(s, "Back", x + contentWidth - fourthWidth, y, fourthWidth,
                    "Return to Finder results.", b -> { clearSubView(s); showMain(s); });
            y += 28;
            String location = joinNonBlank(", ", r.city(), r.region(), r.countryCode(), r.country());
            String authLabel = ToolState.authDetectionEnabled ? ServerAuthService.displayLabel(r.endpoint()) : "Disabled";
            String checked = ToolState.authDetectionEnabled ? ServerAuthService.checkedLabel(r.endpoint()) : "N/A";
            List<String> info = List.of(
                    "Version: " + r.version() + "   Players: " + r.playersOnline() + "/" + r.playersMax(),
                    "Source: " + r.source(), "MOTD: " + r.motd(),
                    "Location: " + (location.isBlank() ? "Unknown" : location),
                    "Last ping: " + blankDefault(r.lastPing(), "Unknown"),
                    "Modpack: " + blankDefault(r.modpack(), "None detected"),
                    "Plugins: " + (r.plugins().isEmpty() ? "None reported" : String.join(", ", r.plugins())),
                    "Authentication: " + authLabel + "   Checked: " + checked,
                    "Provider offline-mode: " + (r.offlineMode() ? "Yes" : "No")
                            + "   Blocked: " + (ToolState.isBlocked(r.endpoint()) ? "Yes" : "No"));
            int rows = detailRowsPerPage(s), pages = (info.size() + rows - 1) / rows;
            s.finderDetailsPage = Math.max(0, Math.min(s.finderDetailsPage, pages - 1));
            for (int i = s.finderDetailsPage * rows; i < Math.min(info.size(), (s.finderDetailsPage + 1) * rows); i++) {
                addSubLabel(s, shorten(info.get(i), contentWidth / 6), x, y, contentWidth);
                y += 24;
            }
            int navY = s.height - 28;
            Object previous = subButton(s, "< Previous", x, navY, 100,
                    "Previous details page.", b -> { s.finderDetailsPage--; showDetails(s, index); });
            addSubLabel(s, "Page " + (s.finderDetailsPage + 1) + "/" + pages, x + 105, navY, contentWidth - 210);
            Object next = subButton(s, "Next >", x + contentWidth - 100, navY, 100,
                    "Next details page.", b -> { s.finderDetailsPage++; showDetails(s, index); });
            Reflection.setBoolean(previous, "active", s.finderDetailsPage > 0);
            Reflection.setBoolean(next, "active", s.finderDetailsPage + 1 < pages);
        } catch (Throwable t) {
            log("Could not show server details", t);
            clearSubView(s); showMain(s);
        }
    }

    static int detailRowsPerPage(OverlayState s) {
        return Math.max(1, (s.height - 124) / 24);
    }

    private static void addFromDetails(OverlayState s, ServerRecord r) {
        try { ServerListBridge.add(s.client, r); clearSubView(s); showMain(s); refreshRows(s); refreshStats(s); }
        catch (Throwable t) { log("Add from details failed", t); }
    }
    private static void removeFromDetails(OverlayState s, ServerRecord r) {
        try { ServerListBridge.remove(s.client, r.endpoint()); clearSubView(s); showMain(s); refreshRows(s); refreshStats(s); }
        catch (Throwable t) { log("Remove from details failed", t); }
    }

    private static String filtersLabel() {
        return ToolState.searchFiltersEnabled ? "Filters*" : ToolState.searchFilters.active() ? "Filters (off)" : "Filters";
    }

    static int detailedFilterRows(OverlayState s) { return Math.max(1, Math.min(4, (s.height - 140) / 26)); }

    private static void openDetailedFilters(OverlayState s, boolean browser) {
        if (s.loading || s.browserLoading) {
            if (browser) { s.browserStatus = "Wait for the current request to finish."; showBrowser(s); }
            else setStatus(s, "Wait for the current request to finish.");
            return;
        }
        s.autoAdd = false;
        s.autoAddScheduleToken++;
        Reflection.setButtonText(s.autoButton, autoLabel(s));
        ServerSearchFilters filters = ToolState.searchFilters;
        s.filterDraft = new String[]{filters.country(), filters.region(), filters.motd(), filters.modpack()};
        s.filterReturnBrowser = browser;
        s.filterPage = 0;
        s.filterMessage = "Apply selects BreakBlocks. Other sources pause these filters.";
        showDetailedFilters(s);
    }

    private static void showDetailedFilters(OverlayState s) {
        try {
            clearSubView(s); hideMain(s);
            int w = Math.min(420, s.width - 12), x = (s.width - w) / 2;
            int rows = detailedFilterRows(s), pages = (4 + rows - 1) / rows;
            s.filterPage = Math.max(0, Math.min(s.filterPage, pages - 1));
            addSubLabel(s, "Detailed Server Filters", x, 35, w - 100);
            subButton(s, "Back", x + w - 96, 35, 96, "Return without applying draft changes.", b -> returnFromFilters(s));
            addSubLabel(s, shorten(s.filterMessage, w / 6), x, 59, w);
            String[] names = {"Country / code", "Region", "MOTD text", "Modpack"};
            String[] hints = {"Country name, GB or a wildcard such as United*.", "Region such as eu-west or eu-*.",
                    "Text anywhere in the MOTD; * is supported.", "Modpack name or short name such as ATM9."};
            for (int slot = 0; slot < rows; slot++) {
                int index = s.filterPage * rows + slot;
                if (index >= 4) break;
                int y = 85 + slot * 26;
                Object label = addSubLabel(s, names[index], x, y, 108);
                Reflection.setTooltip(label, hints[index]);
                addSub(s, DetailedFilterWidgets.textBox(s.screen, x + 114, y, w - 114,
                        names[index], s.filterDraft[index], value -> s.filterDraft[index] = value));
            }
            int half = (w - 6) / 2;
            subButton(s, "Apply", x, s.height - 52, half, "Save filters and reset the affected search.", b -> applyDetailedFilters(s));
            subButton(s, "Clear Fields", x + half + 6, s.height - 52, half,
                    "Blank every field, then press Apply to clear the saved filters.", b -> {
                        Arrays.fill(s.filterDraft, ""); s.filterMessage = "Press Apply to clear the saved filters."; showDetailedFilters(s);
                    });
            Object previous = subButton(s, "< Previous", x, s.height - 28, 90, "Previous fields.", b -> { s.filterPage--; showDetailedFilters(s); });
            addSubLabel(s, "Page " + (s.filterPage + 1) + "/" + pages, x + 94, s.height - 28, w - 188);
            Object next = subButton(s, "Next >", x + w - 90, s.height - 28, 90, "Next fields.", b -> { s.filterPage++; showDetailedFilters(s); });
            Reflection.setBoolean(previous, "active", s.filterPage > 0);
            Reflection.setBoolean(next, "active", s.filterPage + 1 < pages);
        } catch (Throwable failure) { log("Could not open detailed filters", failure); returnFromFilters(s); }
    }

    private static void applyDetailedFilters(OverlayState s) {
        try {
            ServerSearchFilters filters = new ServerSearchFilters(s.filterDraft[0], s.filterDraft[1], s.filterDraft[2], s.filterDraft[3]);
            if (!ToolState.setSearchFilters(filters)) {
                s.filterMessage = "Could not save filters. Check folder permissions."; showDetailedFilters(s); return;
            }
            s.searchFilters = ToolState.activeSearchFilters();
            s.browserSearchFilters = s.searchFilters;
            s.sourceIndex = ToolState.finderSourceIndex;
            Reflection.setButtonText(s.filtersButton, filtersLabel());
            resetSearchState(s, filters.active() ? "Detailed BreakBlocks filters applied." : "Detailed filters cleared.");
            if (s.filterReturnBrowser) browserFilterChanged(s);
            else returnFromFilters(s);
        } catch (IllegalArgumentException invalid) { s.filterMessage = invalid.getMessage(); showDetailedFilters(s); }
    }

    private static void returnFromFilters(OverlayState s) {
        if (s.filterReturnBrowser) showBrowser(s);
        else { clearSubView(s); showMain(s); }
    }

    private static void showSettings(OverlayState s) {
        try {
            s.apiKeyTestOpen = false;
            clearSubView(s); hideMain(s);
            int contentWidth = Math.min(420, s.width - 12), x = (s.width - contentWidth) / 2;
            int leftWidth = (contentWidth - 10) / 2, rightX = x + leftWidth + 10;
            int rightWidth = contentWidth - leftWidth - 10, y = 35;
            addSubLabel(s, "Zazu's Server Seeker Settings", x, y, contentWidth - 110);
            subButton(s, "Back", x + contentWidth - 100, y, 100,
                    s.settingsReturnToMultiplayer ? "Return to Multiplayer." : "Return to the Server Finder results.",
                    b -> backFromSettings(s)); y += 24;
            addSubLabel(s, "Build: " + ReleaseInfo.VERSION + "   Minecraft: " + ReleaseInfo.MINECRAFT_VERSION, x, y, leftWidth);
            int keyEditWidth = (rightWidth - 4) / 2;
            subButton(s, "API Key", rightX, y, keyEditWidth,
                    "Enter, save or clear the BreakBlocks key used by every Server Seeker feature.", b -> openApiKeyEditor(s));
            subButton(s, "Test API Key", rightX + keyEditWidth + 4, y, rightWidth - keyEditWidth - 4,
                    "Test the saved key used by the scanner, browser and player search.", b -> {
                        startApiKeyTest(s); showApiKeyTest(s);
                    }); y += 24;
            refreshConfiguredApiKey(s);
            addSubLabel(s, shorten(breakBlocksApiStatusLabel(s), contentWidth / 6), x, y, contentWidth);
            y += 24;

            int rowsPerPage = settingsRowsPerPage(s), pages = (12 + rowsPerPage - 1) / rowsPerPage;
            s.settingsPage = Math.max(0, Math.min(s.settingsPage, pages - 1));
            for (int slot = 0; slot < rowsPerPage; slot++) {
                int row = s.settingsPage * rowsPerPage + slot;
                int rowY = y + slot * 28, menuY = rowY + 21;
                switch (row) {
                    case 0 -> {
                        subButton(s, "Skip Added Before: " + onOff(ToolState.skipAddedHistory), x, rowY, leftWidth,
                                "Skip addresses that Server Seeker has previously added to your server list.", b -> { ToolState.skipAddedHistory = !ToolState.skipAddedHistory; ToolState.save(); showSettings(s); });
                        subButton(s, "Block Deleted: " + onOff(ToolState.blockDeleted), rightX, rowY, rightWidth,
                                "Automatically block a scanned server when you delete it, preventing rediscovery.", b -> { ToolState.blockDeleted = !ToolState.blockDeleted; ToolState.save(); showSettings(s); });
                    }
                    case 1 -> {
                        subButton(s, "Favourites First: " + onOff(ToolState.favouritesFirst), x, rowY, leftWidth,
                                "Place favourite servers before other servers in managed lists.", b -> { ToolState.favouritesFirst = !ToolState.favouritesFirst; ToolState.save(); showSettings(s); });
                        subButton(s, "Auto-add Default: " + onOff(ToolState.autoAddDefault), rightX, rowY, rightWidth,
                                "Choose whether Verified Search starts with Auto-add enabled.", b -> { ToolState.autoAddDefault = !ToolState.autoAddDefault; ToolState.save(); showSettings(s); });
                    }
                    case 2 -> {
                        subButton(s, "Auto-add Limit: " + autoAddLimitLabel(), x, rowY, leftWidth,
                                "Maximum servers Auto-add may save in one run. Click to choose a limit.",
                                b -> openSubOptionMenu(s, x, menuY, leftWidth, autoAddLimitLabels(), autoAddLimitIndex(),
                                        selected -> { selectAutoAddLimit(selected); showSettings(s); }));
                        subButton(s, "Finder Source: " + sourceLabel(s), rightX, rowY, rightWidth,
                                "Choose Auto, All Sources, BreakBlocks, Cornbread or MineScan.",
                                b -> openSubOptionMenu(s, rightX, menuY, rightWidth, List.of(SOURCE_LABELS), s.sourceIndex,
                                        selected -> { selectSource(s, selected); showSettings(s); }));
                    }
                    case 3 -> {
                        subButton(s, "BreakBlocks Age: " + breakBlocksAgeLabel(), x, rowY, leftWidth,
                                "Only request BreakBlocks records seen within this many days.",
                                b -> openSubOptionMenu(s, x, menuY, leftWidth, breakBlocksAgeLabels(), breakBlocksAgeIndex(),
                                        selected -> { selectBreakBlocksAge(s, selected); showSettings(s); }));
                        subButton(s, "Clear Added History (" + ToolState.addedHistoryCount() + ")", rightX, rowY, rightWidth,
                                "Forget previously added servers so they may appear again.", b -> { ToolState.clearAddedHistory(); showSettings(s); });
                    }
                    case 4 -> {
                        subButton(s, "Server Seeker Stats", x, rowY, leftWidth,
                                "View added, deleted, favourite and blocked totals.", b -> showSeekerStats(s));
                        subButton(s, "Automatic Cleanup: " + onOff(ToolState.automaticCleanupEnabled), rightX, rowY, rightWidth,
                                "Configure cleanup and restore recently removed servers.", b -> showCleanupSettings(s));
                    }
                    case 5 -> {
                        subButton(s, "Search Mode: " + (ToolState.quickSearch ? "Quick" : "Verified"), x, rowY, leftWidth,
                                "Quick shows results immediately. Verified performs two checks.", b -> { toggleSearchMode(s); showSettings(s); });
                        subButton(s, "Auth Detection: " + onOff(ToolState.authDetectionEnabled), rightX, rowY, rightWidth,
                                "Classify servers asynchronously as Microsoft, Cracked or Unknown.", b -> {
                            ToolState.authDetectionEnabled = !ToolState.authDetectionEnabled;
                            ToolState.save();
                            if (ToolState.authDetectionEnabled) scheduleAuthChecks(s, s.results);
                            showSettings(s);
                        });
                    }
                    case 6 -> addSubLabel(s, "Auth Recheck: " + ServerAuthService.RECHECK_DAYS + " days", x, rowY, contentWidth);
                    case 7 -> {
                        subButton(s, "Contribute Servers: " + onOff(ToolState.contributeVerifiedServers), x, rowY, leftWidth,
                                "Send public double-verified discoveries and successful joins to BreakBlocks.", b -> {
                            ToolState.contributeVerifiedServers = !ToolState.contributeVerifiedServers;
                            ToolState.save(); BreakBlocksContributor.onSettingChanged(); showSettings(s);
                        });
                        subButton(s, "Contribution Stats", rightX, rowY, rightWidth,
                                "View the BreakBlocks queue, allowance and log tools.", b -> showContributionStats(s));
                    }
                    case 8 -> addSubLabel(s, "Contribution log: config/zazus-server-seeker/breakblocks-contributions.csv", x, rowY, contentWidth);
                    case 9 -> addSubLabel(s, "Enter your key using the API Key button above.", x, rowY, contentWidth);
                    case 10 -> addSubLabel(s, "config/zazus-server-seeker/server-tool.properties", x, rowY, contentWidth);
                    case 11 -> subButton(s, "Record Ping Players: " + onOff(ToolState.recordPingPlayers), x, rowY, contentWidth,
                            "Save sampled player names from normal server pings to local Server Notes. No extra requests.", b -> {
                                ToolState.recordPingPlayers = !ToolState.recordPingPlayers;
                                if (!ToolState.recordPingPlayers) ServerPingPlayerCollector.clearPending();
                                ToolState.save(); showSettings(s);
                            });
                    default -> { }
                }
            }
            int navY = Math.min(s.height - 28, y + rowsPerPage * 28 + 7);
            Object previous = subButton(s, "< Previous", x, navY, Math.min(100, leftWidth),
                    "Previous settings page.", b -> { s.settingsPage--; showSettings(s); });
            addSubLabel(s, "Page " + (s.settingsPage + 1) + "/" + pages, x + 105, navY, contentWidth - 210);
            Object next = subButton(s, "Next >", x + contentWidth - 100, navY, 100,
                    "Next settings page.", b -> { s.settingsPage++; showSettings(s); });
            Reflection.setBoolean(previous, "active", s.settingsPage > 0);
            Reflection.setBoolean(next, "active", s.settingsPage + 1 < pages);
        } catch (Throwable t) { log("Could not open settings", t); clearSubView(s); showMain(s); }
    }

    static void backFromSettings(OverlayState s) {
        if (s.settingsReturnToMultiplayer) {
            closeOverlay(s);
        } else {
            clearSubView(s);
            showMain(s);
            refreshStats(s);
        }
    }

    static int settingsRowsPerPage(OverlayState s) {
        return Math.max(1, Math.min(5, (s.height - 142) / 28));
    }

    private static void openApiKeyEditor(OverlayState s) {
        Object client = s.client;
        Object parent = s.screen;
        int page = s.settingsPage;
        boolean returnToMultiplayer = s.settingsReturnToMultiplayer;
        closeOverlay(s);
        try {
            ScreenCompat.setScreen(client, new BreakBlocksApiKeyScreen((net.minecraft.client.gui.screens.Screen) parent, () -> {
                try {
                    openOverlay(client, parent, RuntimeAccess.intField(parent, "width", s.width),
                            RuntimeAccess.intField(parent, "height", s.height), false, true);
                    OverlayState resumed = STATES.get(parent);
                    if (resumed != null) {
                        resumed.settingsPage = page;
                        resumed.settingsReturnToMultiplayer = returnToMultiplayer;
                        showSettings(resumed);
                    }
                } catch (Throwable error) { log("Could not return to API key settings", error); }
            }));
        } catch (Throwable error) { log("Could not open API key settings", error); }
    }

    private static void showApiKeyTest(OverlayState s) {
        try {
            s.apiKeyTestOpen = true;
            clearSubView(s); hideMain(s);
            int contentWidth = Math.min(420, s.width - 12), x = (s.width - contentWidth) / 2, y = 38;
            int half = (contentWidth - 10) / 2;
            addSubLabel(s, "BreakBlocks API Key Check", x, y, contentWidth); y += 28;
            addSubLabel(s, shorten(breakBlocksApiStatusLabel(s), contentWidth / 6), x, y, contentWidth); y += 26;
            addSubLabel(s, shorten(s.apiKeyTestStatus, contentWidth / 6), x, y, contentWidth); y += 26;
            addSubLabel(s, "HTTP 200 alone cannot prove a key was accepted.", x, y, contentWidth); y += 24;
            addSubLabel(s, "The discovery endpoint also works without a key.", x, y, contentWidth); y += 30;
            subButton(s, "Retry Check", x, y, half,
                    "Reload the key from the config and send one discovery request.", b -> {
                        startApiKeyTest(s); showApiKeyTest(s);
                    });
            subButton(s, "Back", x + half + 10, y, contentWidth - half - 10,
                    "Return to Server Seeker Settings.", b -> showSettings(s));
        } catch (Throwable t) { log("Could not show API key check", t); showSettings(s); }
    }

    private static void startApiKeyTest(OverlayState s) {
        String key = refreshConfiguredApiKey(s);
        long token = ++s.apiKeyTestToken;
        if (key.isBlank()) {
            s.apiKeyTestStatus = "No key configured. Set breakBlocksApiKey in server-tool.properties.";
            return;
        }
        BreakBlocksRateBudget.Snapshot quota = BreakBlocksRateBudget.snapshot(true);
        if (quota.paused() || (!quota.unlimited() && quota.remaining() <= 0)) {
            s.apiKeyTestStatus = "Allowance reached. Retry in " + quota.resetSeconds() + "s.";
            return;
        }
        s.apiKeyTestStatus = "Checking BreakBlocks (one request)...";
        BreakBlocksRateBudget.recordSearchRequest(true);
        HTTP.sendAsync(buildBreakBlocksRequest(URI.create(BREAKBLOCKS_API_URL + "?limit=1&page=1"), key),
                        HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, error) -> Reflection.execute(s.client, () -> {
                    if (!s.open || token != s.apiKeyTestToken) return;
                    // A response to the previous key must not mark a newly edited key as rejected.
                    if (!Objects.equals(key, refreshConfiguredApiKey(s))) {
                        s.apiKeyTestStatus = "Key changed during check. Press Retry Check.";
                        if (s.apiKeyTestOpen) showApiKeyTest(s);
                        return;
                    }
                    if (error != null || response == null) {
                        s.apiKeyTestStatus = "Request failed: " + (error == null ? "no response" : rootMessage(error));
                    } else {
                        BreakBlocksRateBudget.observe(response, true);
                        int status = response.statusCode();
                        if (status == 401 || status == 403) {
                            s.apiKeyDisabledForSession = true;
                            s.apiKeyDisabledReason = status == 401 ? "Key rejected (401)" : "Access forbidden (403)";
                            s.apiKeyCheckedThisSession = true;
                            s.apiKeyResponseWithoutAuthStatus = false;
                            BreakBlocksAccount.rejected();
                            s.apiKeyTestStatus = "HTTP " + status + ": " + s.apiKeyDisabledReason + ".";
                        } else if (status == 429) {
                            BreakBlocksRateBudget.noteRateLimit(response);
                            s.apiKeyTestStatus = "HTTP 429: rate limit reached; key was not checked.";
                        } else if (status / 100 != 2) {
                            s.apiKeyTestStatus = "HTTP " + status + ": key could not be checked.";
                        } else {
                            try {
                                SearchResult parsed = parseSearchResult(response.body());
                                s.apiKeyCheckedThisSession = parsed.authStatusPresent();
                                s.apiKeyAcceptedThisSession = parsed.authStatusPresent() && parsed.authed();
                                s.apiKeyResponseWithoutAuthStatus = !parsed.authStatusPresent();
                                if (parsed.authStatusPresent()) BreakBlocksAccount.observe(parsed.authed(), parsed.patreonTier());
                                if (parsed.authStatusPresent() && parsed.authed()) {
                                    s.apiKeyDisabledForSession = false;
                                    s.apiKeyDisabledReason = "";
                                }
                                s.apiKeyTestStatus = !parsed.authStatusPresent()
                                        ? "HTTP " + status + ": no auth status; key acceptance unconfirmed."
                                        : parsed.authed() ? "HTTP " + status + ": key authenticated."
                                        : "HTTP " + status + ": API reports authed=false.";
                            } catch (RuntimeException ex) {
                                s.apiKeyTestStatus = "HTTP " + status + ": response could not be read.";
                            }
                        }
                    }
                    if (s.apiKeyTestOpen) showApiKeyTest(s);
                }));
    }

    private static void showCleanupSettings(OverlayState s) {
        try {
            clearSubView(s); hideMain(s);
            int contentWidth = Math.min(420, s.width - 12), x = (s.width - contentWidth) / 2, y = 38;
            int half = (contentWidth - 10) / 2, rightX = x + half + 10;
            addSubLabel(s, "Automatic Cleanup", x, y, contentWidth); y += 28;
            subButton(s, "Automatic Cleanup: " + onOff(ToolState.automaticCleanupEnabled), x, y, contentWidth,
                    "Master switch. Favourites and manually added servers are always protected.", b -> {
                ToolState.automaticCleanupEnabled = !ToolState.automaticCleanupEnabled;
                ToolState.save(); showCleanupSettings(s);
            }); y += 26;
            subButton(s, "Whitelist Rejections: " + onOff(ToolState.cleanupWhitelistEnabled), x, y, half,
                    "Remove Finder-owned servers after a confirmed whitelist rejection.", b -> {
                ToolState.cleanupWhitelistEnabled = !ToolState.cleanupWhitelistEnabled;
                ToolState.save(); showCleanupSettings(s);
            });
            subButton(s, "Required Client Mods: " + onOff(ToolState.cleanupRequiredModsEnabled), rightX, y, contentWidth - half - 10,
                    "Remove Finder-owned servers that explicitly require missing client mods.", b -> {
                ToolState.cleanupRequiredModsEnabled = !ToolState.cleanupRequiredModsEnabled;
                ToolState.save(); showCleanupSettings(s);
            }); y += 26;
            subButton(s, "Invalid Address / DNS: " + onOff(ToolState.cleanupInvalidAddressEnabled), x, y, half,
                    "Remove after three eligible invalid-address or DNS failures.", b -> {
                ToolState.cleanupInvalidAddressEnabled = !ToolState.cleanupInvalidAddressEnabled;
                ToolState.save(); showCleanupSettings(s);
            });
            subButton(s, "Unreachable (3 checks): " + onOff(ToolState.cleanupUnreachableEnabled), rightX, y, contentWidth - half - 10,
                    "Remove after three connection-refused or unreachable checks. Timeouts never count.", b -> {
                ToolState.cleanupUnreachableEnabled = !ToolState.cleanupUnreachableEnabled;
                ToolState.save(); showCleanupSettings(s);
            }); y += 32;
            addSubLabel(s, "Protected: favourites, manual servers, timeouts and all client/version mismatches.", x, y, contentWidth); y += 28;
            int removed = ServerCategoryStore.recentlyRemovedSnapshot().size();
            subButton(s, "Recently Removed (" + removed + ")", x, y, half,
                    "Review removal reasons and restore individual servers.", b -> showRecentlyRemoved(s));
            subButton(s, "Back", x + contentWidth - 100, y, 100,
                    "Return to Server Seeker Settings.", b -> showSettings(s));
        } catch (Throwable t) { log("Could not open automatic-cleanup settings", t); showSettings(s); }
    }

    private static void showRecentlyRemoved(OverlayState s) {
        try {
            clearSubView(s); hideMain(s);
            int contentWidth = Math.min(420, s.width - 12), x = (s.width - contentWidth) / 2, y = 34;
            addSubLabel(s, "Recently Removed Finder Servers", x, y, contentWidth - 105);
            subButton(s, "Back", x + contentWidth - 100, y, 100,
                    "Return to Automatic Cleanup settings.", b -> showCleanupSettings(s)); y += 26;
            List<ServerCategoryStore.RecentlyRemoved> removed = ServerCategoryStore.recentlyRemovedSnapshot();
            int pageSize = listRowsPerPage(s), pages = Math.max(1, (removed.size() + pageSize - 1) / pageSize);
            s.recentlyRemovedPage = Math.max(0, Math.min(s.recentlyRemovedPage, pages - 1));
            if (removed.isEmpty()) {
                addSubLabel(s, "No servers have been removed automatically.", x, y, contentWidth);
            } else {
                int start = s.recentlyRemovedPage * pageSize;
                for (int i = start; i < Math.min(removed.size(), start + pageSize); i++) {
                    ServerCategoryStore.RecentlyRemoved entry = removed.get(i);
                    String label = "Restore " + entry.endpoint() + " — " + entry.reason();
                    String tooltip = entry.reason() + " • " + removedAge(entry.atMillis())
                            + (entry.detail().isBlank() ? "" : " • " + entry.detail());
                    subButton(s, label, x, y, contentWidth, tooltip, b -> {
                        if (ServerCategoryStore.restoreRecentlyRemoved(s.client, entry.endpoint())) {
                            MultiplayerManagementEntrypoint.refreshMultiplayerScreen(s.client, s.screen);
                        } else {
                            showRecentlyRemoved(s);
                        }
                    });
                    y += 23;
                }
            }
            int navY = s.height - 52;
            Object previous = subButton(s, "< Previous", x, navY, 100,
                    "Previous removed servers page.", b -> { s.recentlyRemovedPage--; showRecentlyRemoved(s); });
            addSubLabel(s, "Page " + (s.recentlyRemovedPage + 1) + "/" + pages, x + 105, navY, contentWidth - 210);
            Object next = subButton(s, "Next >", x + contentWidth - 100, navY, 100,
                    "Next removed servers page.", b -> { s.recentlyRemovedPage++; showRecentlyRemoved(s); });
            Reflection.setBoolean(previous, "active", s.recentlyRemovedPage > 0);
            Reflection.setBoolean(next, "active", s.recentlyRemovedPage + 1 < pages);
            subButton(s, "Clear History", x, s.height - 28, 150,
                    "Clear only the Recently Removed history; this does not alter servers.dat.", b -> {
                ServerCategoryStore.clearRecentlyRemoved(); s.recentlyRemovedPage = 0; showRecentlyRemoved(s);
            });
        } catch (Throwable t) { log("Could not show recently removed servers", t); showCleanupSettings(s); }
    }

    static int listRowsPerPage(OverlayState s) {
        return Math.max(1, Math.min(8, (s.height - 118) / 23));
    }

    private static String removedAge(long atMillis) {
        long seconds = Math.max(0L, (System.currentTimeMillis() - atMillis) / 1_000L);
        if (seconds < 60L) return seconds + "s ago";
        long minutes = seconds / 60L;
        if (minutes < 60L) return minutes + "m ago";
        long hours = minutes / 60L;
        if (hours < 24L) return hours + "h ago";
        return (hours / 24L) + "d ago";
    }

    private static void showSeekerStats(OverlayState s) {
        try {
            clearSubView(s); hideMain(s);
            int contentWidth = Math.min(420, s.width - 12), x = (s.width - contentWidth) / 2, y = 38;
            int half = (contentWidth - 10) / 2;
            addSubLabel(s, "Server Seeker Stats", x, y, contentWidth); y += 30;
            addSubLabel(s, "Added history: " + ToolState.addedHistoryCount(), x, y, contentWidth); y += 23;
            addSubLabel(s, "Addresses remembered so previously added servers can be skipped.", x, y, contentWidth); y += 26;
            addSubLabel(s, "Servers added: " + ToolState.addedCount, x, y, half);
            addSubLabel(s, "Servers deleted: " + ToolState.deletedCount, x + half + 10, y, contentWidth - half - 10); y += 26;
            addSubLabel(s, "Favourite servers: " + ServerListBridge.countFavourites(s.client), x, y, half);
            addSubLabel(s, "Blocked servers: " + ToolState.blockedCount(), x + half + 10, y, contentWidth - half - 10); y += 30;
            subButton(s, "Reset Added/Deleted Stats", x, y, half,
                    "Reset only the Servers added and Servers deleted counters.", b -> { ToolState.resetStats(); showSeekerStats(s); });
            subButton(s, "Back", x + contentWidth - 100, y, 100,
                    "Return to Server Seeker Settings.", b -> showSettings(s));
        } catch (Throwable t) {
            log("Could not open Server Seeker stats", t);
            showSettings(s);
        }
    }

    private static void showContributionStats(OverlayState s) {
        try {
            clearSubView(s); hideMain(s);
            BreakBlocksContributor.ContributionSnapshot stats = BreakBlocksContributor.snapshot();
            BreakBlocksRateBudget.Snapshot quota = stats.quota();
            BreakBlocksAccount.Snapshot account = BreakBlocksAccount.snapshot();
            int contentWidth = Math.min(440, s.width - 12), x = (s.width - contentWidth) / 2, y = 28;
            addSubLabel(s, "BreakBlocks Contribution Stats", x, y, contentWidth - 205);
            subButton(s, "Refresh", x + contentWidth - 200, y, 95,
                    "Reload contribution statistics.", b -> showContributionStats(s));
            subButton(s, "Back", x + contentWidth - 100, y, 100,
                    "Return to Server Seeker Settings.", b -> {
                        s.confirmClearContributionStats = false; s.contributionMessage = ""; showSettings(s);
                    }); y += 24;
            String mode = account.authed()
                    ? (account.patreonTier().isBlank() ? "Authenticated API key" : account.patreonTier())
                    : quota.authenticated()
                            ? (account.observed() ? "API key (authed=false)" : "API key (not yet validated)")
                            : "Anonymous";
            String allowance = quota.unlimited() ? "Unlimited" : quota.remaining() + "/" + quota.limit();
            String reset = quota.unlimited() ? "n/a" : quota.resetSeconds() > 0 ? quota.resetSeconds() + "s" : "ready";
            addSubLabel(s, shorten("Mode: " + mode + "   Allowance: " + allowance
                    + "   Reset: " + reset + (quota.paused() ? "   PAUSED" : ""), contentWidth / 6), x, y, contentWidth); y += 23;
            addSubLabel(s, "Session — Accepted " + stats.acceptedSession() + "  Refreshing " + stats.refreshingSession()
                    + "  429s " + stats.rateLimitedSession() + "  Failed " + stats.failedSession(), x, y, contentWidth); y += 23;
            addSubLabel(s, "Overall — Accepted " + stats.acceptedOverall() + "  Refreshing " + stats.refreshingOverall()
                    + "  429s " + stats.rateLimitedOverall() + "  Failed " + stats.failedOverall(), x, y, contentWidth); y += 23;
            String current = stats.currentEndpoint().isBlank() ? "None" : stats.currentEndpoint();
            addSubLabel(s, shorten("Current: " + current + "   Failed awaiting retry: " + stats.failedPending()
                    + "   Pending: " + stats.queued().size(), contentWidth / 6), x, y, contentWidth); y += 25;

            int controlWidth = (contentWidth - 8) / 3;
            subButton(s, "Retry Failed (" + stats.failedPending() + ")", x, y, controlWidth,
                    "Requeue failed contributions.", b -> {
                int retried = BreakBlocksContributor.retryFailed();
                s.contributionMessage = retried == 0 ? "No failed contributions to retry." : "Queued " + retried + " failed contribution(s).";
                showContributionStats(s);
            });
            subButton(s, "Open Log Folder", x + controlWidth + 4, y, controlWidth,
                    "Open the Minecraft config folder.", b -> {
                s.contributionMessage = BreakBlocksContributor.openLogFolder()
                        ? "Opened the Minecraft config folder." : "Could not open the config folder.";
                showContributionStats(s);
            });
            String clearLabel = s.confirmClearContributionStats ? "Confirm Clear Stats" : "Clear Statistics";
            subButton(s, clearLabel, x + (controlWidth + 4) * 2, y, contentWidth - (controlWidth + 4) * 2,
                    "Clear recorded contribution statistics; keep the queue.", b -> {
                if (s.confirmClearContributionStats) {
                    BreakBlocksContributor.clearStatistics();
                    s.confirmClearContributionStats = false;
                    s.contributionMessage = "Contribution statistics cleared; queue preserved.";
                } else {
                    s.confirmClearContributionStats = true;
                    s.contributionMessage = "Press Confirm Clear Stats to continue.";
                }
                showContributionStats(s);
            });
            y += 24;
            List<String> queued = stats.queued();
            if (s.height >= 245) {
                addSubLabel(s, s.contributionMessage.isBlank() ? "Pending queue: " + queued.size()
                        : shorten(s.contributionMessage, contentWidth / 6), x, y, contentWidth);
                y += 22;
            }
            int pageSize = Math.max(1, Math.min(8, (s.height - y - 36) / 22));
            int pages = Math.max(1, (queued.size() + pageSize - 1) / pageSize);
            s.contributionPage = Math.max(0, Math.min(s.contributionPage, pages - 1));
            if (s.height >= 245) {
                int start = s.contributionPage * pageSize;
                for (int i = start; i < Math.min(queued.size(), start + pageSize); i++) {
                    String endpoint = queued.get(i);
                    addSubLabel(s, (endpoint.equals(stats.currentEndpoint()) ? "▶ " : "") + endpoint, x, y, contentWidth);
                    y += 22;
                }
            }
            int navY = s.height - 28;
            Object previous = subButton(s, "< Previous", x, navY, 100,
                    "Previous queued servers page.", b -> { s.contributionPage--; showContributionStats(s); });
            addSubLabel(s, "Page " + (s.contributionPage + 1) + "/" + pages, x + 105, navY, contentWidth - 210);
            Object next = subButton(s, "Next >", x + contentWidth - 100, navY, 100,
                    "Next queued servers page.", b -> { s.contributionPage++; showContributionStats(s); });
            Reflection.setBoolean(previous, "active", s.contributionPage > 0);
            Reflection.setBoolean(next, "active", s.contributionPage + 1 < pages);
        } catch (Throwable t) {
            log("Could not open contribution stats", t);
            clearSubView(s);
            showSettings(s);
        }
    }

    private static void toggleSearchMode(OverlayState s) {
        ToolState.quickSearch = !ToolState.quickSearch;
        if (ToolState.quickSearch && s.autoAdd) {
            s.autoAdd = false;
            s.autoAddScheduleToken++;
            Reflection.setButtonText(s.autoButton, autoLabel(s));
        }
        ToolState.save();
        resetSearchState(s, ToolState.quickSearch
                ? "Quick Search enabled — results are unverified and Auto-add is disabled."
                : "Verified Search enabled — two status checks required.");
    }

    private static void showBlockedList(OverlayState s) {
        try {
            clearSubView(s); hideMain(s);
            List<String> blocked = ToolState.blockedSnapshot();
            int pageSize = listRowsPerPage(s), pages = Math.max(1, (blocked.size() + pageSize - 1) / pageSize);
            s.blockedPage = Math.max(0, Math.min(s.blockedPage, pages - 1));
            int contentWidth = Math.min(420, s.width - 12), x = (s.width - contentWidth) / 2, y = 34;
            addSubLabel(s, "Blocked / Deleted Servers — " + blocked.size(), x, y, contentWidth); y += 26;
            int start = s.blockedPage * pageSize;
            for (int i = 0; i < pageSize && start + i < blocked.size(); i++) {
                String endpoint = blocked.get(start + i);
                addSubLabel(s, endpoint, x, y, contentWidth - 110);
                subButton(s, "Unblock", x + contentWidth - 104, y, 104,
                        "Allow this server in future searches.", b -> { ToolState.unblock(endpoint); showBlockedList(s); });
                y += 23;
            }
            int navY = s.height - 52;
            Object previous = subButton(s, "< Previous", x, navY, 100,
                    "Previous blocked servers page.", b -> { s.blockedPage--; showBlockedList(s); });
            addSubLabel(s, "Page " + (s.blockedPage + 1) + "/" + pages, x + 105, navY, contentWidth - 210);
            Object next = subButton(s, "Next >", x + contentWidth - 100, navY, 100,
                    "Next blocked servers page.", b -> { s.blockedPage++; showBlockedList(s); });
            Reflection.setBoolean(previous, "active", s.blockedPage > 0);
            Reflection.setBoolean(next, "active", s.blockedPage + 1 < pages);
            subButton(s, "Clear Entire Blocked List", x, s.height - 28, 200,
                    "Remove all blocked entries.", b -> { ToolState.clearBlocked(); s.blockedPage = 0; showBlockedList(s); });
            subButton(s, "Back", x + contentWidth - 100, s.height - 28, 100,
                    "Return to Finder.", b -> { clearSubView(s); showMain(s); });
        } catch (Throwable t) { log("Could not open blocked list", t); clearSubView(s); showMain(s); }
    }

    private static void refreshStats(OverlayState s) {
        Reflection.setButtonText(s.blockedButton, blockedLabel());
    }

    private static String blockedLabel() { return "Blocked Servers (" + ToolState.blockedCount() + ")"; }
    private static String sortLabel(OverlayState s) { return "Sort: " + SORT_LABELS[s.sortIndex]; }
    private static String autoAddLimitLabel() { return ToolState.autoAddLimit <= 0 ? "Unlimited" : String.valueOf(ToolState.autoAddLimit); }
    private static int autoAddLimitIndex() {
        int current = 0;
        for (int i = 0; i < AUTO_ADD_LIMITS.length; i++) if (AUTO_ADD_LIMITS[i] == ToolState.autoAddLimit) current = i;
        return current;
    }
    private static List<String> autoAddLimitLabels() { return integerLabels(AUTO_ADD_LIMITS, true); }
    private static void selectAutoAddLimit(int selected) {
        ToolState.autoAddLimit = AUTO_ADD_LIMITS[clampIndex(selected, AUTO_ADD_LIMITS.length, 1)];
        ToolState.save();
    }
    private static String onOff(boolean value) { return value ? "ON" : "OFF"; }
    private static int clampIndex(int index, int length, int fallback) { return index >= 0 && index < length ? index : fallback; }
    private static List<String> versionOptionLabels() {
        ArrayList<String> labels = new ArrayList<>();
        for (String option : VERSION_OPTIONS) labels.add(option.equals("*") ? "Any" : option);
        return labels;
    }
    private static List<String> integerLabels(int[] values, boolean zeroOrHugeMeansAny) {
        ArrayList<String> labels = new ArrayList<>();
        for (int value : values) {
            boolean any = zeroOrHugeMeansAny && (value == 0 || value >= 999999);
            labels.add(any ? "Any" : String.valueOf(value));
        }
        return labels;
    }
    private static String versionLabel(OverlayState s) { return "Version: " + (VERSION_OPTIONS[s.versionIndex].equals("*") ? "Any" : VERSION_OPTIONS[s.versionIndex]); }
    static int versionIndexForFilter(String savedFilter, int fallbackIndex) {
        if (savedFilter != null && !savedFilter.isBlank()) {
            for (int i = 0; i < VERSION_OPTIONS.length; i++) {
                if (VERSION_OPTIONS[i].equalsIgnoreCase(savedFilter.trim())) return i;
            }
        }
        return clampIndex(fallbackIndex, VERSION_OPTIONS.length, 2);
    }
    static String versionOption(int index) { return VERSION_OPTIONS[clampIndex(index, VERSION_OPTIONS.length, 2)]; }
    private static String minLabel(OverlayState s) { return "Min: " + MIN_PLAYER_OPTIONS[s.minIndex]; }
    private static String maxLabel(OverlayState s) { return "Max: " + (MAX_PLAYER_OPTIONS[s.maxIndex] >= 999999 ? "Any" : MAX_PLAYER_OPTIONS[s.maxIndex]); }
    private static String serverTypeLabel(OverlayState s) { return "Type: " + SERVER_TYPE_LABELS[s.serverTypeIndex]; }
    private static int maxBreakBlocksPages(OverlayState s) {
        if (s.breakBlocksAvailablePages > 0) return s.breakBlocksAvailablePages;
        return s.serverTypeIndex == 2 ? 2 : MAX_PUBLIC_PAGES;
    }
    static int pageCountForResults(int filtered, int pageSize) {
        int safePageSize = Math.max(1, pageSize);
        return Math.max(1, (Math.max(0, filtered) + safePageSize - 1) / safePageSize);
    }
    private static String sourceLabel(OverlayState s) { return SOURCE_LABELS[clampIndex(s.sourceIndex, SOURCE_LABELS.length, 0)]; }
    private static void selectSource(OverlayState s, int selected) {
        s.sourceIndex = clampIndex(selected, SOURCE_LABELS.length, 0);
        ToolState.finderSourceIndex = s.sourceIndex;
        if (s.sourceIndex != 2) {
            ToolState.searchFiltersEnabled = false;
            s.searchFilters = ServerSearchFilters.EMPTY;
            Reflection.setButtonText(s.filtersButton, filtersLabel());
        }
        ToolState.save();
        resetSearchState(s, "Finder source changed to " + sourceLabel(s) + ".");
    }
    private static int normalizedBreakBlocksAgeDays(int days) {
        for (int option : BREAKBLOCKS_AGE_OPTIONS) if (option == days) return days;
        return 30;
    }
    private static String breakBlocksAgeLabel() {
        int days = normalizedBreakBlocksAgeDays(ToolState.breakBlocksMaxAgeDays);
        return days + (days == 1 ? " day" : " days");
    }
    private static int breakBlocksAgeIndex() {
        int current = normalizedBreakBlocksAgeDays(ToolState.breakBlocksMaxAgeDays);
        int index = 0;
        for (int i = 0; i < BREAKBLOCKS_AGE_OPTIONS.length; i++) if (BREAKBLOCKS_AGE_OPTIONS[i] == current) index = i;
        return index;
    }
    private static List<String> breakBlocksAgeLabels() {
        ArrayList<String> labels = new ArrayList<>();
        for (int days : BREAKBLOCKS_AGE_OPTIONS) labels.add(days + (days == 1 ? " day" : " days"));
        return labels;
    }
    private static void selectBreakBlocksAge(OverlayState s, int selected) {
        ToolState.breakBlocksMaxAgeDays = BREAKBLOCKS_AGE_OPTIONS[clampIndex(selected, BREAKBLOCKS_AGE_OPTIONS.length, 4)];
        ToolState.save();
        resetSearchState(s, "BreakBlocks age changed to " + breakBlocksAgeLabel() + ".");
    }
    private static String breakBlocksProgressLabel(OverlayState s, String phase) {
        StringBuilder label = new StringBuilder("BreakBlocks page ")
                .append(Math.max(1, s.breakBlocksCurrentPage))
                .append(" — ").append(s.breakBlocksApiResults).append(" API — strict verification");
        if (phase != null && !phase.isBlank()) label.append(" (").append(phase).append(")");
        return label.toString();
    }
    private static boolean versionMatches(OverlayState s, ServerRecord record) {
        String wanted = VERSION_OPTIONS[s.versionIndex];
        if (wanted.equals("*")) return true;
        // Prefer Minecraft's own current protocol value. This accepts servers
        // whose status version-name is customized by a proxy while still
        // reporting the client's actual 26.2 protocol number.
        if (wanted.equals("26.2") && record.protocol() == VanillaStatusProbe.currentProtocol()) return true;
        String actual = record.version() == null ? "" : record.version().toLowerCase(Locale.ROOT);
        String needle = wanted.toLowerCase(Locale.ROOT);
        if (needle.endsWith("*")) return actual.contains(needle.substring(0, needle.length() - 1));
        return actual.contains(needle);
    }
    private static String breakBlocksApiStatusLabel(OverlayState s) {
        if (!ToolState.hasBreakBlocksApiKey()) return "BreakBlocks API: Anonymous (no key configured)";
        if (s.apiKeyDisabledForSession) return "BreakBlocks API: " + s.apiKeyDisabledReason + " — anonymous fallback";
        BreakBlocksAccount.Snapshot account = BreakBlocksAccount.snapshot();
        if (account.authed()) {
            String tier = account.patreonTier().isBlank() ? "Authenticated key" : account.patreonTier();
            return "BreakBlocks API: " + tier + (account.paid() ? " — unlimited results" : " — authenticated");
        }
        if (s.apiKeyCheckedThisSession) return "BreakBlocks API: Key sent, but API reported authed=false";
        if (s.apiKeyResponseWithoutAuthStatus) return "BreakBlocks API: Key sent; API gave no auth status";
        return "BreakBlocks API: Key configured (check with Test API Key)";
    }
    private static String autoLabel(OverlayState s) { return "Auto-add: " + onOff(s.autoAdd); }
    private static void setStatus(OverlayState s, String text) { Reflection.setButtonText(s.statusButton, shorten(text, 84)); }
    private static String normalizeEndpoint(String endpoint) { return ToolState.normalize(endpoint); }
    private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static String shorten(String value, int max) { if (value == null) return ""; return value.length() <= max ? value : value.substring(0, Math.max(0, max - 1)) + "…"; }
    private static String rootMessage(Throwable t) { Throwable x = Reflection.unwrap(t); return x.getMessage() == null ? x.getClass().getSimpleName() : x.getMessage(); }
    private static void log(String message, Throwable t) { System.err.println("[Zazu's Server Seeker] " + message + ": " + Reflection.unwrap(t)); }
    private static String blankDefault(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private static String joinNonBlank(String sep, String... values) {
        List<String> out = new ArrayList<>();
        for (String v : values) if (v != null && !v.isBlank() && !out.contains(v)) out.add(v.trim());
        return String.join(sep, out);
    }

    static SearchResult parseSearchResult(String json) {
        Object parsed = new JsonParser(json).parse();
        if (!(parsed instanceof Map<?, ?> top)) throw new IllegalArgumentException("Top-level response was not an object.");
        Object dataObj = top.get("data");
        Map<?, ?> data = dataObj instanceof Map<?, ?> m ? m : top;
        List<ServerRecord> servers = new ArrayList<>();
        Object results = data.get("results");
        if (results instanceof List<?> list) {
            for (Object obj : list) {
                if (!(obj instanceof Map<?, ?> m)) continue;
                String address = asString(m.get("address"), "");
                if (address.isBlank()) continue;
                String motd = asString(m.get("motd_stripped"), asString(m.get("motd"), ""));
                boolean offline = asBoolean(m.get("offline_mode"), false);
                boolean whitelist = asBoolean(firstNonNull(m.get("whitelisted"), m.get("whitelist")), false);
                servers.add(new ServerRecord(
                        address,
                        asInt(m.get("port"), 25565),
                        asString(m.get("version"), "?"),
                        asInt(m.get("players_online"), 0),
                        asInt(m.get("players_max"), 0),
                        motd,
                        asString(m.get("country"), ""),
                        asString(m.get("country_code"), ""),
                        asString(m.get("city"), ""),
                        asString(m.get("region"), ""),
                        asString(m.get("last_ping"), ""),
                        asString(m.get("detected_mod_pack"), ""),
                        offline, whitelist, stringList(m.get("plugins")), -1, "BreakBlocks"));
            }
        }
        int displayed = asInt(data.get("displayed"), servers.size());
        int total = asInt(data.get("total"), displayed);
        int filtered = asInt(data.get("filtered"), total);
        boolean authStatusPresent = data.containsKey("authed") || top.containsKey("authed");
        boolean authed = asBoolean(firstNonNull(data.get("authed"), top.get("authed")), false);
        String patreonTier = asString(firstNonNull(data.get("patreon_tier"), top.get("patreon_tier")), "").trim();
        return new SearchResult(servers, displayed, total, filtered, authStatusPresent, authed, patreonTier);
    }

    static List<ServerRecord> parseCornbreadResult(String json) {
        Object parsed = new JsonParser(json).parse();
        if (!(parsed instanceof Map<?, ?> top)) throw new IllegalArgumentException("Cornbread response was not an object.");
        Object data = top.get("data");
        if (!(data instanceof List<?> list)) return List.of();
        List<ServerRecord> out = new ArrayList<>();
        for (Object obj : list) {
            if (!(obj instanceof Map<?, ?> m)) continue;
            String address = ipv4Value(firstNonNull(m.get("ip"), m.get("address"), m.get("serverip")));
            if (address.isBlank()) continue;
            Map<?, ?> versionMap = m.get("version") instanceof Map<?, ?> vm ? vm : Map.of();
            Map<?, ?> playersMap = m.get("players") instanceof Map<?, ?> pm ? pm : Map.of();
            String version = !versionMap.isEmpty() ? asString(versionMap.get("name"), "?") : asString(m.get("version"), "?");
            int protocol = !versionMap.isEmpty() ? asInt(versionMap.get("protocol"), -1) : asInt(m.get("protocol"), -1);
            int online = !playersMap.isEmpty() ? asInt(playersMap.get("online"), 0) : asInt(firstNonNull(m.get("onlinePlayers"), m.get("players_online")), 0);
            int max = !playersMap.isEmpty() ? asInt(playersMap.get("max"), 0) : asInt(firstNonNull(m.get("maxPlayers"), m.get("players_max")), 0);
            boolean cracked = asBoolean(firstNonNull(m.get("cracked"), m.get("offline_mode")), false);
            boolean whitelist = asBoolean(firstNonNull(m.get("whitelisted"), m.get("whitelist")), false);
            out.add(new ServerRecord(address, asInt(m.get("port"), 25565), version, online, max,
                    asString(firstNonNull(m.get("description"), m.get("motd")), ""),
                    "", "", "", "", asString(m.get("lastSeen"), ""), "", cracked, whitelist, List.of(), protocol, "Cornbread"));
        }
        return out;
    }

    static List<ServerRecord> parseMineScanResult(String json) {
        Object parsed = new JsonParser(json).parse();
        if (!(parsed instanceof Map<?, ?> top)) throw new IllegalArgumentException("MineScan response was not an object.");
        Object data = top.get("servers");
        if (!(data instanceof List<?> list)) return List.of();
        List<ServerRecord> out = new ArrayList<>();
        for (Object obj : list) {
            if (!(obj instanceof Map<?, ?> m)) continue;
            String address = asString(firstNonNull(m.get("serverip"), m.get("address"), m.get("ip")), "");
            if (address.isBlank()) continue;
            String auth = asString(firstNonNull(m.get("authmode"), m.get("authMode")), "").toLowerCase(Locale.ROOT);
            boolean offline = auth.equals("offline") || auth.equals("cracked");
            boolean whitelist = auth.equals("whitelist") || auth.equals("whitelisted");
            out.add(new ServerRecord(address, asInt(m.get("port"), 25565), asString(m.get("version"), "?"),
                    asInt(firstNonNull(m.get("onlinePlayers"), m.get("players")), 0),
                    asInt(firstNonNull(m.get("maxPlayers"), m.get("max")), 0),
                    asString(m.get("motd"), ""), "", "", "", "", asString(m.get("lastSeen"), ""),
                    "", offline, whitelist, List.of(), asInt(m.get("protocol"), -1), "MineScan"));
        }
        return out;
    }

    private static Object firstNonNull(Object... values) {
        for (Object value : values) if (value != null) return value;
        return null;
    }

    private static String ipv4Value(Object value) {
        if (value == null) return "";
        if (value instanceof Number n) return intToIpv4(n.longValue());
        String text = String.valueOf(value).trim();
        if (text.matches("-?\\d+")) {
            try { return intToIpv4(Long.parseLong(text)); } catch (NumberFormatException ignored) {}
        }
        return text;
    }

    static String intToIpv4(long ip) {
        long u = ip & 0xFFFF_FFFFL;
        return ((u >> 24) & 0xFF) + "." + ((u >> 16) & 0xFF) + "." + ((u >> 8) & 0xFF) + "." + (u & 0xFF);
    }

    private static String asString(Object value, String fallback) { return value == null ? fallback : String.valueOf(value); }
    private static int asInt(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        try { return value == null ? fallback : Integer.parseInt(String.valueOf(value)); } catch (NumberFormatException ignored) { return fallback; }
    }
    private static boolean asBoolean(Object value, boolean fallback) {
        if (value instanceof Boolean b) return b;
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }
    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        ArrayList<String> out = new ArrayList<>(); for (Object v : list) if (v != null) out.add(String.valueOf(v)); return out;
    }

    static final class OverlayState {
        final Object client, screen;
        final int width, height;
        final Map<Object, WidgetState> originalStates = new IdentityHashMap<>();
        final Map<Object, Boolean> optionMenuOwnerStates = new IdentityHashMap<>();
        final List<Object> widgets = new ArrayList<>(), resultButtons = new ArrayList<>(), resultAddButtons = new ArrayList<>(), resultDetailButtons = new ArrayList<>(), subWidgets = new ArrayList<>(), optionMenuWidgets = new ArrayList<>();
        final LinkedHashSet<String> seenEndpoints = new LinkedHashSet<>();
        final List<ServerRecord> currentBatch = new ArrayList<>();
        final List<Integer> breakBlocksPageOrder = new ArrayList<>();
        final Set<Integer> breakBlocksRequestedPages = new LinkedHashSet<>();
        final EnumMap<Provider, Integer> providerRequestCounts = new EnumMap<>(Provider.class);
        final EnumMap<Provider, Integer> providerDuplicateOnlyStreaks = new EnumMap<>(Provider.class);
        final EnumSet<Provider> disabledProviders = EnumSet.noneOf(Provider.class);
        boolean open, loading, autoAdd, exhausted, apiKeyDisabledForSession, apiKeyAcceptedThisSession, apiKeyCheckedThisSession, apiKeyResponseWithoutAuthStatus, breakBlocksPaidTier;
        boolean mainVisible = true, apiKeyTestOpen, settingsReturnToMultiplayer;
        String lastConfiguredApiKey, apiKeyDisabledReason = "";
        boolean browserOpen, browserLoading, browserPaidTier;
        int browserVersionIndex, browserMinIndex, browserMaxIndex, browserSortIndex, browserTypeIndex, browserAgeDays;
        int browserPage = 1, browserRowPage, browserAvailablePages = 1, browserFiltered, browserHiddenWhitelisted;
        long browserRequestToken;
        long apiKeyTestToken;
        String apiKeyTestStatus = "Press Retry Check after changing the config key.";
        int settingsPage, finderDetailsPage, browserDetailsPage, recentlyRemovedPage;
        ServerSearchFilters searchFilters = ServerSearchFilters.EMPTY, browserSearchFilters = ServerSearchFilters.EMPTY;
        String[] filterDraft = {"", "", "", ""};
        String filterMessage = "";
        int filterPage;
        boolean filterReturnBrowser;
        String browserStatus = "Choose a page to browse.";
        List<ServerRecord> browserResults = List.of();
        int versionIndex, minIndex, maxIndex, sortIndex, serverTypeIndex, sourceIndex, autoAddedThisSession, blockedPage, resultPage, allSourceCursor, contributionPage;
        long autoAddScheduleToken, searchGeneration;
        int breakBlocksPageCapacity, breakBlocksAvailablePages, breakBlocksCurrentPage, breakBlocksApiResults, breakBlocksProbeAttempts, breakBlocksStatusReplies, breakBlocksLiveVerified;
        int breakBlocksDnsFailures, breakBlocksUnreachableFailures, breakBlocksTimeoutFailures, breakBlocksProbeErrors, breakBlocksIncompatibleReplies;
        int statusProbeAttempts, statusFirstPasses, statusSecondPasses, liveVerified, statusRejected;
        int statusDnsFailures, statusUnreachableFailures, statusTimeoutFailures, statusProbeErrors;
        String breakBlocksPatreonTier = "", breakBlocksLastProbeFailure = "", lastProbeFailure = "", contributionMessage = "";
        boolean confirmClearContributionStats;
        Provider activeProvider;
        List<ServerRecord> results = List.of();
        Object filtersButton, versionButton, minButton, maxButton, serverTypeButton, findButton, autoButton, resetButton, closeButton, sortButton, settingsButton, blockedButton, browserButton, statusButton;
        Object previousResultsButton, resultPageButton, nextResultsButton;
        OverlayState(Object client, Object screen, int width, int height) { this.client = client; this.screen = screen; this.width = width; this.height = height; }
    }

    record WidgetState(boolean visible, boolean active) {}
    record SearchResult(List<ServerRecord> servers, int displayed, int total, int filtered,
                        boolean authStatusPresent, boolean authed, String patreonTier) {}
    record ServerRecord(String address, int port, String version, int playersOnline, int playersMax,
                        String motd, String country, String countryCode, String city, String region,
                        String lastPing, String modpack, boolean offlineMode, boolean whitelisted,
                        List<String> plugins, int protocol, String source) {
        ServerRecord withLiveVersion(String liveVersion, int liveProtocol) {
            return new ServerRecord(address, port, liveVersion == null || liveVersion.isBlank() ? version : liveVersion,
                    playersOnline, playersMax, motd, country, countryCode, city, region, lastPing, modpack,
                    offlineMode, whitelisted, plugins, liveProtocol, source);
        }
        ServerRecord withSource(String newSource) {
            return new ServerRecord(address, port, version, playersOnline, playersMax, motd, country, countryCode,
                    city, region, lastPing, modpack, offlineMode, whitelisted, plugins, protocol, newSource);
        }
        String endpoint() { return port == 25565 ? address : address + ":" + port; }
        String displayName() { return address + " [" + version + "]"; }
    }

    /** Reflection bridge around Minecraft's servers.dat / ServerList classes. */
    static final class ServerListBridge {
        private static final Object UNSET = new Object();
        private ServerListBridge() {}

        static boolean contains(Object client, String endpoint) throws Exception { return findServer(createLoadedList(client), endpoint) != null; }

        static boolean add(Object client, ServerRecord record) throws Exception {
            if (ToolState.isBlocked(record.endpoint())) return false;
            Object list = createLoadedList(client);
            if (findServer(list, record.endpoint()) != null) return false;
            Object data = createServerData(record.displayName(), record.endpoint());
            addServerData(list, data);
            save(list);
            ServerPingPlayerCollector.serverAdded(client, record.endpoint());
            ToolState.recordAdded(record.endpoint(), record.version(), record.protocol());
            ServerCategoryStore.markScanned(record.endpoint());
            ServerAuthService.ensureAsync(client, record.endpoint(), record.protocol(), null);
            return true;
        }

        static boolean addBrowsed(Object client, ServerRecord record) throws Exception {
            if (ToolState.isBlocked(record.endpoint())) return false;
            Object list = createLoadedList(client);
            if (findServer(list, record.endpoint()) != null) return false;
            // Browsing is a deliberate user choice, so these belong in Servers.
            // Avoid the Finder's "[version]" name used to recover Scanned rows
            // when someone copies only servers.dat into another instance.
            Object data = createServerData(record.address(), record.endpoint());
            addServerData(list, data);
            save(list);
            ServerPingPlayerCollector.serverAdded(client, record.endpoint());
            ServerCategoryStore.syncNew(List.of(record.endpoint()));
            ToolState.recordAdded(record.endpoint(), record.version(), record.protocol());
            return true;
        }

        static boolean remove(Object client, String endpoint) throws Exception {
            Object list = createLoadedList(client);
            Object server = findServer(list, endpoint);
            if (server == null) return false;
            if (serverName(server).startsWith("★ ") || ServerCategoryStore.isFavourite(endpoint)) return false;
            ServerCategoryStore.recordUndo(serverName(server), endpoint);
            if (!invokeRemove(list, server)) {
                List<Object> backing = mutableBackingList(list);
                if (backing == null || !backing.remove(server)) throw new IllegalStateException("Could not remove the server.");
            }
            save(list);
            ToolState.recordDeleted(endpoint);
            ServerCategoryStore.remove(endpoint);
            return true;
        }

        static void addServerData(Object list, Object data) throws Exception {
            if (!invokeAdd(list, data)) {
                List<Object> backing = mutableBackingList(list);
                if (backing == null) throw new IllegalStateException("Could not access Minecraft's server list.");
                backing.add(data);
            }
        }

        static int countFavourites(Object client) {
            try {
                int count = 0;
                for (Object server : servers(createLoadedList(client))) {
                    String endpoint = serverEndpoint(server);
                    if (serverName(server).startsWith("★ ") || ServerCategoryStore.isFavourite(endpoint)) count++;
                }
                return count;
            } catch (Throwable ignored) { return 0; }
        }

        static Object createLoadedList(Object client) throws Exception {
            Class<?> type = Class.forName("net.minecraft.client.multiplayer.ServerList");
            Object list = null;
            for (Constructor<?> c : type.getDeclaredConstructors()) {
                try { c.setAccessible(true); } catch (Throwable ignored) {}
                Class<?>[] p = c.getParameterTypes();
                try {
                    if (p.length == 1 && client != null && p[0].isInstance(client)) { list = c.newInstance(client); break; }
                    if (p.length == 0) { list = c.newInstance(); break; }
                } catch (Throwable ignored) {}
            }
            if (list == null) throw new IllegalStateException("Unsupported ServerList constructor.");
            Method load = Reflection.findMethod(type, "load", 0);
            if (load != null) try { load.invoke(list); } catch (Throwable ignored) {}
            return list;
        }

        static Object createServerData(String name, String endpoint) throws Exception {
            Class<?> type = Class.forName("net.minecraft.client.multiplayer.ServerData");
            for (Constructor<?> c : type.getDeclaredConstructors()) {
                try { c.setAccessible(true); } catch (Throwable ignored) {}
                Class<?>[] p = c.getParameterTypes();
                if (p.length < 2 || p[0] != String.class || p[1] != String.class) continue;
                Object[] args = new Object[p.length]; args[0] = name; args[1] = endpoint;
                boolean ok = true;
                for (int i = 2; i < p.length; i++) { args[i] = defaultValue(p[i]); if (args[i] == UNSET) { ok = false; break; } }
                if (!ok) continue;
                try { return c.newInstance(args); } catch (Throwable ignored) {}
            }
            throw new IllegalStateException("Unsupported ServerData constructor.");
        }

        static Object findServer(Object list, String endpoint) throws Exception {
            String target = ToolState.normalize(endpoint);
            for (Object s : servers(list)) if (ToolState.normalize(serverEndpoint(s)).equals(target)) return s;
            return null;
        }

        @SuppressWarnings("unchecked")
        static List<Object> servers(Object list) throws Exception {
            for (String name : List.of("getServers", "servers")) {
                Object v = Reflection.invokeQuiet(list, name);
                if (v instanceof List<?> l) return (List<Object>) l;
            }
            List<Object> backing = mutableBackingList(list);
            return backing == null ? List.of() : backing;
        }

        @SuppressWarnings("unchecked")
        private static List<Object> mutableBackingList(Object list) {
            for (Class<?> c = list.getClass(); c != null; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (!List.class.isAssignableFrom(f.getType())) continue;
                    try { f.setAccessible(true); Object v = f.get(list); if (v instanceof List<?> l) return (List<Object>) l; } catch (Throwable ignored) {}
                }
            }
            return null;
        }

        static String serverEndpoint(Object server) throws Exception {
            Object value = Reflection.getField(server, "ip", "address");
            if (value != null) return String.valueOf(value);
            for (String n : List.of("ip", "address", "getAddress")) {
                value = Reflection.invokeQuiet(server, n); if (value != null) return String.valueOf(value);
            }
            return "";
        }

        static String serverName(Object server) {
            Object value = Reflection.getField(server, "name");
            return value == null ? "" : String.valueOf(value);
        }

        static void setServerName(Object server, String name) throws Exception {
            Field f = Reflection.findField(server.getClass(), "name");
            if (f == null) throw new NoSuchFieldException("ServerData.name field not found");
            f.set(server, name);
        }

        private static boolean invokeAdd(Object list, Object server) throws Exception {
            for (Method m : allMethods(list.getClass())) {
                if (!m.getName().equals("add") || m.getParameterCount() < 1) continue;
                Class<?>[] p = m.getParameterTypes();
                if (!p[0].isAssignableFrom(server.getClass())) continue;
                Object[] args = new Object[p.length]; args[0] = server; boolean ok = true;
                for (int i = 1; i < p.length; i++) { args[i] = defaultValue(p[i]); if (args[i] == UNSET) { ok = false; break; } }
                if (!ok) continue;
                try { m.setAccessible(true); m.invoke(list, args); return true; } catch (Throwable ignored) {}
            }
            return false;
        }

        private static boolean invokeRemove(Object list, Object server) throws Exception {
            for (Method m : allMethods(list.getClass())) {
                if (!m.getName().equals("remove") || m.getParameterCount() != 1) continue;
                Class<?> p = m.getParameterTypes()[0];
                try { m.setAccessible(true); } catch (Throwable ignored) {}
                if (p.isAssignableFrom(server.getClass())) { m.invoke(list, server); return true; }
                if (p == int.class || p == Integer.class) {
                    List<Object> all = servers(list); int idx = all.indexOf(server); if (idx >= 0) { m.invoke(list, idx); return true; }
                }
            }
            return false;
        }

        static void save(Object list) throws Exception {
            Method m = Reflection.findMethod(list.getClass(), "save", 0);
            if (m == null) throw new IllegalStateException("ServerList.save() was not found.");
            m.invoke(list);
        }

        private static List<Method> allMethods(Class<?> type) {
            ArrayList<Method> out = new ArrayList<>(); for (Class<?> c = type; c != null; c = c.getSuperclass()) out.addAll(Arrays.asList(c.getDeclaredMethods())); return out;
        }

        private static Object defaultValue(Class<?> type) {
            if (!type.isPrimitive()) {
                if (type.isEnum()) {
                    Object[] values = type.getEnumConstants();
                    if (values != null) {
                        for (Object v : values) if (String.valueOf(v).equalsIgnoreCase("OTHER")) return v;
                        if (values.length > 0) return values[0];
                    }
                    return UNSET;
                }
                return null;
            }
            if (type == boolean.class) return false;
            if (type == byte.class) return (byte)0;
            if (type == short.class) return (short)0;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == float.class) return 0f;
            if (type == double.class) return 0d;
            if (type == char.class) return '\0';
            return UNSET;
        }
    }

    /** Tiny dependency-free JSON parser retained from the release. */
    static final class JsonParser {
        private final String text;
        private int pos;
        JsonParser(String text) { this.text = Objects.requireNonNull(text); }
        Object parse() { Object v = parseValue(); skipWhitespace(); if (pos != text.length()) throw error("Trailing data"); return v; }
        private Object parseValue() {
            skipWhitespace(); if (pos >= text.length()) throw error("Unexpected end of input");
            char c = text.charAt(pos);
            return switch (c) {
                case '{' -> parseObject(); case '[' -> parseArray(); case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE); case 'f' -> parseLiteral("false", Boolean.FALSE); case 'n' -> parseLiteral("null", null);
                default -> { if (c == '-' || Character.isDigit(c)) yield parseNumber(); throw error("Unexpected character '" + c + "'"); }
            };
        }
        private Map<String,Object> parseObject() {
            expect('{'); LinkedHashMap<String,Object> out = new LinkedHashMap<>(); skipWhitespace(); if (peek('}')) { pos++; return out; }
            while (true) { skipWhitespace(); String key = parseString(); skipWhitespace(); expect(':'); out.put(key, parseValue()); skipWhitespace(); if (peek('}')) { pos++; return out; } expect(','); }
        }
        private List<Object> parseArray() {
            expect('['); ArrayList<Object> out = new ArrayList<>(); skipWhitespace(); if (peek(']')) { pos++; return out; }
            while (true) { out.add(parseValue()); skipWhitespace(); if (peek(']')) { pos++; return out; } expect(','); }
        }
        private String parseString() {
            expect('"'); StringBuilder out = new StringBuilder();
            while (pos < text.length()) {
                char c = text.charAt(pos++); if (c == '"') return out.toString();
                if (c != '\\') { out.append(c); continue; }
                if (pos >= text.length()) throw error("Bad escape");
                char e = text.charAt(pos++);
                switch (e) {
                    case '"','\\','/' -> out.append(e); case 'b' -> out.append('\b'); case 'f' -> out.append('\f'); case 'n' -> out.append('\n'); case 'r' -> out.append('\r'); case 't' -> out.append('\t');
                    case 'u' -> { if (pos + 4 > text.length()) throw error("Bad unicode escape"); out.append((char)Integer.parseInt(text.substring(pos, pos + 4), 16)); pos += 4; }
                    default -> throw error("Bad escape");
                }
            }
            throw error("Unterminated string");
        }
        private Object parseNumber() {
            int start = pos; if (peek('-')) pos++; while (pos < text.length() && Character.isDigit(text.charAt(pos))) pos++;
            boolean floating = false;
            if (peek('.')) { floating = true; pos++; while (pos < text.length() && Character.isDigit(text.charAt(pos))) pos++; }
            if (peek('e') || peek('E')) { floating = true; pos++; if (peek('+') || peek('-')) pos++; while (pos < text.length() && Character.isDigit(text.charAt(pos))) pos++; }
            String n = text.substring(start, pos); try { return floating ? Double.parseDouble(n) : Long.parseLong(n); } catch (NumberFormatException e) { throw error("Bad number"); }
        }
        private Object parseLiteral(String literal, Object value) { if (!text.startsWith(literal, pos)) throw error("Expected " + literal); pos += literal.length(); return value; }
        private void skipWhitespace() { while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++; }
        private boolean peek(char c) { return pos < text.length() && text.charAt(pos) == c; }
        private void expect(char c) { skipWhitespace(); if (!peek(c)) throw error("Expected '" + c + "'"); pos++; }
        private IllegalArgumentException error(String message) { return new IllegalArgumentException(message + " at " + pos); }
    }
}
