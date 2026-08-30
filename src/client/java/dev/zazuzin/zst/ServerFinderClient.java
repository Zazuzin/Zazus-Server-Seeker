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
    private static final String USER_AGENT = "ZazusServerSeeker/0.4.0-beta.1";
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
            "*", "26.2", "26.1", "1.21*", "1.20*", "1.19*", "1.18*", "1.16*", "1.12*", "1.8*"
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

    static OverlayState stateFor(Object screen) { return STATES.get(screen); }

    static void openOverlay(Object client, Object screen, int width, int height) throws Exception {
        OverlayState old = STATES.get(screen);
        if (old != null && old.open) return;

        OverlayState state = new OverlayState(client, screen, width, height);
        state.versionIndex = clampIndex(ToolState.versionIndex, VERSION_OPTIONS.length, 1);
        state.minIndex = clampIndex(ToolState.minIndex, MIN_PLAYER_OPTIONS.length, 1);
        state.maxIndex = clampIndex(ToolState.maxIndex, MAX_PLAYER_OPTIONS.length, 7);
        state.sortIndex = clampIndex(ToolState.sortIndex, SORT_LABELS.length, 0);
        state.serverTypeIndex = clampIndex(ToolState.serverTypeIndex, SERVER_TYPE_LABELS.length, 0);
        state.sourceIndex = clampIndex(ToolState.finderSourceIndex, SOURCE_LABELS.length, 0);
        state.autoAdd = ToolState.autoAddDefault && !ToolState.quickSearch;
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
        if (state.autoAdd) {
            state.autoAddedThisSession = 0;
            scheduleNextAutoAddBatch(state, 0L, false, "Auto-add starting…");
        }
    }

    private static void buildOverlay(OverlayState s) throws Exception {
        int cx = s.width / 2;
        int y = 30;
        s.versionButton = addTracked(s, Reflection.makeButton(versionLabel(s), cx - 190, y, 100, 20, b -> cycleVersion(s)));
        s.minButton = addTracked(s, Reflection.makeButton(minLabel(s), cx - 86, y, 88, 20, b -> cycleMin(s)));
        s.maxButton = addTracked(s, Reflection.makeButton(maxLabel(s), cx + 6, y, 88, 20, b -> cycleMax(s)));
        s.serverTypeButton = addTracked(s, Reflection.makeButton(serverTypeLabel(s), cx + 98, y, 92, 20, b -> cycleServerType(s)));

        y += 24;
        s.findButton = addTracked(s, Reflection.makeButton("Find New Servers", cx - 190, y, 128, 20, b -> findNewServers(s)));
        s.autoButton = addTracked(s, Reflection.makeButton(autoLabel(s), cx - 58, y, 92, 20, b -> toggleAuto(s)));
        s.resetButton = addTracked(s, Reflection.makeButton("Reset Search", cx + 38, y, 92, 20, b -> resetSearchState(s, "Search reset.")));
        s.closeButton = addTracked(s, Reflection.makeButton("Close Finder", cx + 134, y, 92, 20, b -> closeOverlay(s)));

        y += 24;
        s.sortButton = addTracked(s, Reflection.makeButton(sortLabel(s), cx - 190, y, 100, 20, b -> cycleSort(s)));
        s.settingsButton = addTracked(s, Reflection.makeButton("Settings", cx - 86, y, 88, 20, b -> showSettings(s)));
        s.blockedButton = addTracked(s, Reflection.makeButton("Blocked", cx + 6, y, 88, 20, b -> showBlockedList(s)));
        s.statsButton = addTracked(s, Reflection.makeButton(statsLabel(s), cx + 98, y, 128, 20, b -> {}));
        Reflection.setBoolean(s.statsButton, "active", false);

        y += 26;
        s.statusButton = addTracked(s, Reflection.makeButton(
                "Ready — " + ToolState.addedHistoryCount() + " servers added before can be skipped automatically.",
                cx - 226, y, 452, 20, b -> {}));
        Reflection.setBoolean(s.statusButton, "active", false);

        int rowY = y + 25;
        for (int i = 0; i < DISPLAY_RESULTS; i++) {
            final int index = i;
            Object row = addTracked(s, Reflection.makeButton("", cx - 226, rowY + i * 23, 330, 20, b -> toggleVisibleResult(s, index)));
            Object add = addTracked(s, Reflection.makeButton("Add", cx + 108, rowY + i * 23, 54, 20, b -> addVisibleResult(s, index)));
            Object detail = addTracked(s, Reflection.makeButton("Details", cx + 166, rowY + i * 23, 60, 20, b -> showVisibleDetails(s, index)));
            s.resultButtons.add(row);
            s.resultAddButtons.add(add);
            s.resultDetailButtons.add(detail);
        }
        int navY = rowY + DISPLAY_RESULTS * 23 + 2;
        s.previousResultsButton = addTracked(s, Reflection.makeButton("< Previous", cx - 150, navY, 90, 20, b -> changeResultPage(s, -1)));
        s.resultPageButton = addTracked(s, Reflection.makeButton("Page 1/1", cx - 56, navY, 112, 20, b -> {}));
        Reflection.setBoolean(s.resultPageButton, "active", false);
        s.nextResultsButton = addTracked(s, Reflection.makeButton("Next >", cx + 60, navY, 90, 20, b -> changeResultPage(s, 1)));
        refreshRows(s);
    }

    private static Object addTracked(OverlayState s, Object widget) throws Exception {
        Reflection.addWidget(s.screen, widget);
        s.widgets.add(widget);
        return widget;
    }

    private static Object addSub(OverlayState s, Object widget) throws Exception {
        Reflection.addWidget(s.screen, widget);
        s.subWidgets.add(widget);
        return widget;
    }

    private static Object addSubLabel(OverlayState s, String text, int x, int y, int width) throws Exception {
        Object label = addSub(s, Reflection.makeButton(text, x, y, width, 20, b -> {}));
        Reflection.setBoolean(label, "active", false);
        return label;
    }

    private static void closeOverlay(OverlayState s) {
        if (!s.open) return;
        s.open = false;
        s.autoAdd = false;
        s.autoAddScheduleToken++;
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

    private static void cycleVersion(OverlayState s) {
        s.versionIndex = (s.versionIndex + 1) % VERSION_OPTIONS.length;
        Reflection.setButtonText(s.versionButton, versionLabel(s));
        ToolState.versionIndex = s.versionIndex; ToolState.save();
        resetSearchState(s, "Version changed — search reset.");
    }
    private static void cycleMin(OverlayState s) {
        s.minIndex = (s.minIndex + 1) % MIN_PLAYER_OPTIONS.length;
        Reflection.setButtonText(s.minButton, minLabel(s));
        ToolState.minIndex = s.minIndex; ToolState.save();
        resetSearchState(s, "Player filter changed — search reset.");
    }
    private static void cycleMax(OverlayState s) {
        s.maxIndex = (s.maxIndex + 1) % MAX_PLAYER_OPTIONS.length;
        Reflection.setButtonText(s.maxButton, maxLabel(s));
        ToolState.maxIndex = s.maxIndex; ToolState.save();
        resetSearchState(s, "Player filter changed — search reset.");
    }
    private static void cycleServerType(OverlayState s) {
        s.serverTypeIndex = (s.serverTypeIndex + 1) % SERVER_TYPE_LABELS.length;
        Reflection.setButtonText(s.serverTypeButton, serverTypeLabel(s));
        ToolState.serverTypeIndex = s.serverTypeIndex; ToolState.save();
        resetSearchState(s, "Server type changed — search reset.");
    }
    private static void cycleSort(OverlayState s) {
        s.sortIndex = (s.sortIndex + 1) % SORT_LABELS.length;
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
        ToolState.reloadBreakBlocksApiKey();
        if (!ToolState.hasBreakBlocksApiKey()) s.apiKeyDisabledForSession = false;
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
            case BREAKBLOCKS -> fetchBreakBlocks(s, requestNumber);
            case CORNBREAD -> fetchCornbread(s);
            case MINESCAN -> fetchMineScan(s);
        }
    }

    private static Provider chooseProviderForNextRequest(OverlayState s) {
        int mode = s.sourceIndex;
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
        int used = s.providerRequestCounts.getOrDefault(provider, 0);
        int max = provider == Provider.BREAKBLOCKS ? maxBreakBlocksPages(s) : MAX_RANDOM_PROVIDER_REQUESTS;
        if (used >= max) {
            s.disabledProviders.add(provider);
            return false;
        }
        return true;
    }

    private static void fetchBreakBlocks(OverlayState s, int requestNumber) {
        // BreakBlocks is 1-based: page=1 is the first page. requestNumber is our
        // zero-based count of requests already made for this provider.
        int page = requestNumber + 1;
        URI uri = buildBreakBlocksPageUri(s, page);
        s.breakBlocksCurrentPage = page;
        setStatus(s, breakBlocksProgressLabel(s, "requesting"));
        sendBreakBlocksPage(s, page, uri, true);
    }

    static URI buildBreakBlocksPageUri(OverlayState s, int page) {
        int age = normalizedBreakBlocksAgeDays(ToolState.breakBlocksMaxAgeDays);
        StringBuilder url = new StringBuilder(BREAKBLOCKS_API_URL)
                .append("?version=").append(enc(VERSION_OPTIONS[s.versionIndex]))
                .append("&minUsers=").append(MIN_PLAYER_OPTIONS[s.minIndex])
                .append("&maxUsers=").append(MAX_PLAYER_OPTIONS[s.maxIndex])
                .append("&page=").append(Math.max(1, page))
                .append("&limit=").append(API_LIMIT)
                .append("&maxAge=").append(age);
        if (s.serverTypeIndex == 2) url.append("&offlineOnly=on");
        if (!SORT_API_VALUES[s.sortIndex].isBlank()) url.append("&sort=").append(enc(SORT_API_VALUES[s.sortIndex]));
        return URI.create(url.toString());
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
        String apiKey = allowAuthentication && !s.apiKeyDisabledForSession ? ToolState.breakBlocksApiKey() : "";
        boolean authenticated = !apiKey.isBlank();
        HttpRequest request = buildBreakBlocksRequest(uri, apiKey);
        HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, error) -> Reflection.execute(s.client,
                        () -> handleBreakBlocksPage(s, page, uri, authenticated, response, error)));
    }

    static HttpRequest buildBreakBlocksRequest(URI uri, String apiKey) {
        HttpRequest.Builder builder = baseRequest(uri);
        if (apiKey != null && !apiKey.isBlank()) builder.header("Authorization", "Bearer " + apiKey.trim());
        return builder.GET().build();
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

    private static void handleBreakBlocksPage(OverlayState s, int page, URI uri, boolean authenticated,
                                               HttpResponse<String> response, Throwable error) {
        if (!s.open) { s.loading = false; return; }
        if (error != null) { providerFailure(s, Provider.BREAKBLOCKS, rootMessage(error)); return; }
        if (response == null) { providerFailure(s, Provider.BREAKBLOCKS, "No response"); return; }

        int status = response.statusCode();
        if (authenticated && (status == 401 || status == 403)) {
            s.apiKeyDisabledForSession = true;
            setStatus(s, "BreakBlocks API key rejected; retrying anonymously…");
            sendBreakBlocksPage(s, page, uri, false);
            return;
        }
        if (status == 429) {
            String retry = response.headers().firstValue("Retry-After").orElse("").trim();
            providerFailure(s, Provider.BREAKBLOCKS, retry.isBlank() ? "rate limit reached" : "rate limit; retry in " + retry + "s");
            return;
        }
        if (status / 100 != 2) { providerFailure(s, Provider.BREAKBLOCKS, "HTTP " + status); return; }
        if (authenticated) s.apiKeyAcceptedThisSession = true;
        try {
            SearchResult parsed = parseSearchResult(response.body());
            s.breakBlocksCurrentPage = page;
            s.breakBlocksApiResults += parsed.servers().size();
            if (parsed.servers().isEmpty()) { providerExhausted(s, Provider.BREAKBLOCKS); return; }
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
        List<ServerRecord> candidates = new ArrayList<>();
        int newApiEndpoints = 0;
        for (ServerRecord r : records) {
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
        for (ServerRecord record : candidates) {
            if (!versionMatches(s, record) || ToolState.isBlocked(record.endpoint())) continue;
            s.currentBatch.add(record.withSource(record.source() + " (unverified)"));
        }
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
        int start = s.resultPage * DISPLAY_RESULTS;
        for (int i = 0; i < DISPLAY_RESULTS; i++) {
            int resultIndex = start + i;
            boolean has = resultIndex < s.results.size();
            Object row = s.resultButtons.size() > i ? s.resultButtons.get(i) : null;
            Object add = s.resultAddButtons.size() > i ? s.resultAddButtons.get(i) : null;
            Object detail = s.resultDetailButtons.size() > i ? s.resultDetailButtons.get(i) : null;
            Reflection.setBoolean(row, "visible", has); Reflection.setBoolean(row, "active", has);
            Reflection.setBoolean(add, "visible", has); Reflection.setBoolean(add, "active", has);
            Reflection.setBoolean(detail, "visible", has); Reflection.setBoolean(detail, "active", has);
            if (has) {
                ServerRecord r = s.results.get(resultIndex);
                String verification = r.source().endsWith(" (unverified)") ? "UNVERIFIED" : "VERIFIED";
                String text = shorten(r.endpoint() + " | " + r.version() + " | " + r.playersOnline()
                        + "/" + r.playersMax() + " | " + verification, 58);
                Reflection.setButtonText(row, text);
                try { Reflection.setButtonText(add, ServerListBridge.contains(s.client, r.endpoint()) ? "Saved" : "Add"); }
                catch (Throwable ignored) { Reflection.setButtonText(add, "Add"); }
            }
        }
        int pages = resultPageCount(s);
        Reflection.setButtonText(s.resultPageButton, "Page " + (s.resultPage + 1) + "/" + pages);
        Reflection.setBoolean(s.previousResultsButton, "visible", !s.results.isEmpty());
        Reflection.setBoolean(s.nextResultsButton, "visible", !s.results.isEmpty());
        Reflection.setBoolean(s.resultPageButton, "visible", !s.results.isEmpty());
        Reflection.setBoolean(s.previousResultsButton, "active", s.resultPage > 0);
        Reflection.setBoolean(s.nextResultsButton, "active", s.resultPage + 1 < pages);
    }

    private static int resultPageCount(OverlayState s) {
        return Math.max(1, (s.results.size() + DISPLAY_RESULTS - 1) / DISPLAY_RESULTS);
    }

    private static int visibleResultIndex(OverlayState s, int row) {
        return s.resultPage * DISPLAY_RESULTS + row;
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
    private static void showVisibleDetails(OverlayState s, int row) { showDetails(s, visibleResultIndex(s, row)); }

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

    private static void toggleResult(OverlayState s, int index) { if (index >= 0 && index < s.results.size()) showDetails(s, index); }

    private static void hideMain(OverlayState s) { for (Object w : s.widgets) Reflection.setBoolean(w, "visible", false); }
    private static void showMain(OverlayState s) { for (Object w : s.widgets) Reflection.setBoolean(w, "visible", true); refreshRows(s); }
    private static void clearSubView(OverlayState s) {
        try { Reflection.widgets(s.screen).removeAll(s.subWidgets); } catch (Throwable ignored) {}
        s.subWidgets.clear();
    }

    private static void showDetails(OverlayState s, int index) {
        if (index < 0 || index >= s.results.size()) return;
        ServerRecord r = s.results.get(index);
        try {
            clearSubView(s); hideMain(s);
            int cx = s.width / 2, x = cx - 220, y = 32;
            addSubLabel(s, "Server Details — " + r.endpoint(), x, y, 440); y += 24;
            addSubLabel(s, "Version: " + r.version() + "   Players: " + r.playersOnline() + "/" + r.playersMax(), x, y, 440); y += 24;
            addSubLabel(s, "Source: " + r.source(), x, y, 440); y += 24;
            addSubLabel(s, "MOTD: " + shorten(r.motd(), 66), x, y, 440); y += 24;
            String location = joinNonBlank(", ", r.city(), r.region(), r.countryCode(), r.country());
            addSubLabel(s, "Location: " + (location.isBlank() ? "Unknown" : location), x, y, 440); y += 24;
            addSubLabel(s, "Last ping: " + blankDefault(r.lastPing(), "Unknown"), x, y, 440); y += 24;
            addSubLabel(s, "Modpack: " + blankDefault(r.modpack(), "None detected"), x, y, 440); y += 24;
            addSubLabel(s, "Plugins: " + (r.plugins().isEmpty() ? "None reported" : shorten(String.join(", ", r.plugins()), 62)), x, y, 440); y += 24;
            addSubLabel(s, "Offline mode: " + (r.offlineMode() ? "Yes" : "No") + "   Blocked: " + (ToolState.isBlocked(r.endpoint()) ? "Yes" : "No"), x, y, 440); y += 28;

            boolean saved;
            try { saved = ServerListBridge.contains(s.client, r.endpoint()); } catch (Throwable t) { saved = false; }
            if (saved) addSub(s, Reflection.makeButton("Remove Saved Server", x, y, 140, 20, b -> removeFromDetails(s, r)));
            else addSub(s, Reflection.makeButton("Add Server", x, y, 100, 20, b -> addFromDetails(s, r)));
            if (ToolState.isBlocked(r.endpoint())) addSub(s, Reflection.makeButton("Unblock Server", x + 146, y, 120, 20, b -> { ToolState.unblock(r.endpoint()); showDetails(s, index); }));
            else addSub(s, Reflection.makeButton("Block Server", x + 146, y, 120, 20, b -> { ToolState.block(r.endpoint()); showDetails(s, index); }));
            addSub(s, Reflection.makeButton("Back", x + 340, y, 100, 20, b -> { clearSubView(s); showMain(s); }));
        } catch (Throwable t) {
            log("Could not show server details", t);
            clearSubView(s); showMain(s);
        }
    }

    private static void addFromDetails(OverlayState s, ServerRecord r) {
        try { ServerListBridge.add(s.client, r); clearSubView(s); showMain(s); refreshRows(s); refreshStats(s); }
        catch (Throwable t) { log("Add from details failed", t); }
    }
    private static void removeFromDetails(OverlayState s, ServerRecord r) {
        try { ServerListBridge.remove(s.client, r.endpoint()); clearSubView(s); showMain(s); refreshRows(s); refreshStats(s); }
        catch (Throwable t) { log("Remove from details failed", t); }
    }

    private static void showSettings(OverlayState s) {
        try {
            clearSubView(s); hideMain(s);
            int cx = s.width / 2, x = cx - 210, y = 35;
            addSubLabel(s, "Zazu's Server Seeker Settings", x, y, 420); y += 28;
            addSub(s, Reflection.makeButton("Skip Added Before: " + onOff(ToolState.skipAddedHistory), x, y, 200, 20, b -> { ToolState.skipAddedHistory = !ToolState.skipAddedHistory; ToolState.save(); showSettings(s); }));
            addSub(s, Reflection.makeButton("Block Deleted: " + onOff(ToolState.blockDeleted), x + 210, y, 200, 20, b -> { ToolState.blockDeleted = !ToolState.blockDeleted; ToolState.save(); showSettings(s); })); y += 24;
            addSub(s, Reflection.makeButton("Favourites First: " + onOff(ToolState.favouritesFirst), x, y, 200, 20, b -> { ToolState.favouritesFirst = !ToolState.favouritesFirst; ToolState.save(); showSettings(s); }));
            addSub(s, Reflection.makeButton("Auto-add Default: " + onOff(ToolState.autoAddDefault), x + 210, y, 200, 20, b -> { ToolState.autoAddDefault = !ToolState.autoAddDefault; ToolState.save(); showSettings(s); })); y += 24;
            addSub(s, Reflection.makeButton("Auto-add Limit: " + autoAddLimitLabel(), x, y, 200, 20, b -> { cycleAutoAddLimit(); ToolState.save(); showSettings(s); }));
            addSub(s, Reflection.makeButton("Finder Source: " + sourceLabel(s), x + 210, y, 200, 20, b -> { cycleSource(s); showSettings(s); })); y += 24;
            addSub(s, Reflection.makeButton("BreakBlocks Age: " + breakBlocksAgeLabel(), x, y, 200, 20, b -> { cycleBreakBlocksAge(s); showSettings(s); }));
            addSub(s, Reflection.makeButton("Clear Added History (" + ToolState.addedHistoryCount() + ")", x + 210, y, 200, 20, b -> { ToolState.clearAddedHistory(); showSettings(s); })); y += 28;
            addSub(s, Reflection.makeButton("Reset Added/Deleted Stats", x, y, 200, 20, b -> { ToolState.resetStats(); showSettings(s); }));
            addSub(s, Reflection.makeButton("Search Mode: " + (ToolState.quickSearch ? "Quick" : "Verified"), x + 210, y, 200, 20, b -> { toggleSearchMode(s); showSettings(s); })); y += 28;
            ToolState.reloadBreakBlocksApiKey();
            addSubLabel(s, breakBlocksApiStatusLabel(s), x, y, 410); y += 24;
            addSubLabel(s, "Config key: breakBlocksApiKey=...", x, y, 260);
            addSub(s, Reflection.makeButton("Back", x + 310, y, 100, 20, b -> { clearSubView(s); showMain(s); refreshStats(s); })); y += 24;
            addSubLabel(s, "config/zazus-server-tool.properties", x, y, 410);
        } catch (Throwable t) { log("Could not open settings", t); clearSubView(s); showMain(s); }
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
            int pageSize = 8, pages = Math.max(1, (blocked.size() + pageSize - 1) / pageSize);
            s.blockedPage = Math.max(0, Math.min(s.blockedPage, pages - 1));
            int cx = s.width / 2, x = cx - 210, y = 34;
            addSubLabel(s, "Blocked / Deleted Servers — " + blocked.size(), x, y, 420); y += 26;
            int start = s.blockedPage * pageSize;
            for (int i = 0; i < pageSize && start + i < blocked.size(); i++) {
                String endpoint = blocked.get(start + i);
                addSubLabel(s, endpoint, x, y, 300);
                addSub(s, Reflection.makeButton("Unblock", x + 306, y, 104, 20, b -> { ToolState.unblock(endpoint); showBlockedList(s); }));
                y += 23;
            }
            int navY = Math.max(y + 4, s.height - 55);
            addSub(s, Reflection.makeButton("< Previous", x, navY, 100, 20, b -> { if (s.blockedPage > 0) s.blockedPage--; showBlockedList(s); }));
            addSubLabel(s, "Page " + (s.blockedPage + 1) + "/" + pages, x + 105, navY, 90);
            addSub(s, Reflection.makeButton("Next >", x + 200, navY, 90, 20, b -> { if (s.blockedPage + 1 < pages) s.blockedPage++; showBlockedList(s); }));
            addSub(s, Reflection.makeButton("Clear Entire Blocked List", x, navY + 24, 200, 20, b -> { ToolState.clearBlocked(); s.blockedPage = 0; showBlockedList(s); }));
            addSub(s, Reflection.makeButton("Back", x + 310, navY + 24, 100, 20, b -> { clearSubView(s); showMain(s); }));
        } catch (Throwable t) { log("Could not open blocked list", t); clearSubView(s); showMain(s); }
    }

    private static void refreshStats(OverlayState s) {
        Reflection.setButtonText(s.statsButton, statsLabel(s));
        Reflection.setButtonText(s.blockedButton, "Blocked " + ToolState.blockedCount());
    }

    private static String statsLabel(OverlayState s) {
        int favs = ServerListBridge.countFavourites(s.client);
        return "H" + ToolState.addedHistoryCount() + " A" + ToolState.addedCount + " D" + ToolState.deletedCount + " F" + favs;
    }
    private static String sortLabel(OverlayState s) { return "Sort: " + SORT_LABELS[s.sortIndex]; }
    private static String autoAddLimitLabel() { return ToolState.autoAddLimit <= 0 ? "Unlimited" : String.valueOf(ToolState.autoAddLimit); }
    private static void cycleAutoAddLimit() {
        int current = 0;
        for (int i = 0; i < AUTO_ADD_LIMITS.length; i++) if (AUTO_ADD_LIMITS[i] == ToolState.autoAddLimit) current = i;
        ToolState.autoAddLimit = AUTO_ADD_LIMITS[(current + 1) % AUTO_ADD_LIMITS.length];
    }
    private static String onOff(boolean value) { return value ? "ON" : "OFF"; }
    private static int clampIndex(int index, int length, int fallback) { return index >= 0 && index < length ? index : fallback; }
    private static String versionLabel(OverlayState s) { return "Version: " + (VERSION_OPTIONS[s.versionIndex].equals("*") ? "Any" : VERSION_OPTIONS[s.versionIndex]); }
    private static String minLabel(OverlayState s) { return "Min: " + MIN_PLAYER_OPTIONS[s.minIndex]; }
    private static String maxLabel(OverlayState s) { return "Max: " + (MAX_PLAYER_OPTIONS[s.maxIndex] >= 999999 ? "Any" : MAX_PLAYER_OPTIONS[s.maxIndex]); }
    private static String serverTypeLabel(OverlayState s) { return "Type: " + SERVER_TYPE_LABELS[s.serverTypeIndex]; }
    private static int maxBreakBlocksPages(OverlayState s) {
        return s.serverTypeIndex == 2 ? 2 : MAX_PUBLIC_PAGES;
    }
    private static String sourceLabel(OverlayState s) { return SOURCE_LABELS[clampIndex(s.sourceIndex, SOURCE_LABELS.length, 0)]; }
    private static void cycleSource(OverlayState s) {
        s.sourceIndex = (s.sourceIndex + 1) % SOURCE_LABELS.length;
        ToolState.finderSourceIndex = s.sourceIndex;
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
    private static void cycleBreakBlocksAge(OverlayState s) {
        int current = normalizedBreakBlocksAgeDays(ToolState.breakBlocksMaxAgeDays);
        int index = 0;
        for (int i = 0; i < BREAKBLOCKS_AGE_OPTIONS.length; i++) if (BREAKBLOCKS_AGE_OPTIONS[i] == current) index = i;
        ToolState.breakBlocksMaxAgeDays = BREAKBLOCKS_AGE_OPTIONS[(index + 1) % BREAKBLOCKS_AGE_OPTIONS.length];
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
        if (s.apiKeyDisabledForSession) return "BreakBlocks API: Key rejected — anonymous fallback active";
        if (s.apiKeyAcceptedThisSession) return "BreakBlocks API: Authenticated key active";
        return "BreakBlocks API: Key configured (validated on next search)";
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
        return new SearchResult(servers, displayed, total, filtered);
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
        final List<Object> widgets = new ArrayList<>(), resultButtons = new ArrayList<>(), resultAddButtons = new ArrayList<>(), resultDetailButtons = new ArrayList<>(), subWidgets = new ArrayList<>();
        final LinkedHashSet<String> seenEndpoints = new LinkedHashSet<>();
        final List<ServerRecord> currentBatch = new ArrayList<>();
        final EnumMap<Provider, Integer> providerRequestCounts = new EnumMap<>(Provider.class);
        final EnumMap<Provider, Integer> providerDuplicateOnlyStreaks = new EnumMap<>(Provider.class);
        final EnumSet<Provider> disabledProviders = EnumSet.noneOf(Provider.class);
        boolean open, loading, autoAdd, exhausted, apiKeyDisabledForSession, apiKeyAcceptedThisSession;
        int versionIndex, minIndex, maxIndex, sortIndex, serverTypeIndex, sourceIndex, autoAddedThisSession, blockedPage, resultPage, allSourceCursor;
        long autoAddScheduleToken, searchGeneration;
        int breakBlocksCurrentPage, breakBlocksApiResults, breakBlocksProbeAttempts, breakBlocksStatusReplies, breakBlocksLiveVerified;
        int breakBlocksDnsFailures, breakBlocksUnreachableFailures, breakBlocksTimeoutFailures, breakBlocksProbeErrors, breakBlocksIncompatibleReplies;
        int statusProbeAttempts, statusFirstPasses, statusSecondPasses, liveVerified, statusRejected;
        int statusDnsFailures, statusUnreachableFailures, statusTimeoutFailures, statusProbeErrors;
        String breakBlocksLastProbeFailure = "", lastProbeFailure = "";
        Provider activeProvider;
        List<ServerRecord> results = List.of();
        Object versionButton, minButton, maxButton, serverTypeButton, findButton, autoButton, resetButton, closeButton, sortButton, settingsButton, blockedButton, statsButton, statusButton;
        Object previousResultsButton, resultPageButton, nextResultsButton;
        OverlayState(Object client, Object screen, int width, int height) { this.client = client; this.screen = screen; this.width = width; this.height = height; }
    }

    record WidgetState(boolean visible, boolean active) {}
    record SearchResult(List<ServerRecord> servers, int displayed, int total, int filtered) {}
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
            ToolState.recordAdded(record.endpoint(), record.version(), record.protocol());
            ServerCategoryStore.markScanned(record.endpoint());
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

        static void setServerEndpoint(Object server, String endpoint) throws Exception {
            Field f = Reflection.findField(server.getClass(), "ip");
            if (f == null) f = Reflection.findField(server.getClass(), "address");
            if (f == null) throw new NoSuchFieldException("ServerData address field not found");
            f.set(server, endpoint);
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
