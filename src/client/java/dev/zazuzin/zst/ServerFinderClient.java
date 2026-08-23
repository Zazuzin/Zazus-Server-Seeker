package dev.zazuzin.zst;

import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * Core finder overlay and BreakBlocks client.
 *
 * Minecraft GUI/server-list interaction is deliberately reflection-based to
 * tolerate mapping/layout changes across the supported 26.2 client stack.
 */
public final class ServerFinderClient {
    private static final String API_URL = "https://api.breakblocks.com/api/v0.1/servers/find";
    private static final int DISPLAY_RESULTS = 8;
    private static final int API_LIMIT = 20;
    private static final int MAX_PUBLIC_PAGES = 10;
    private static final int MAX_AUTHENTICATED_PAGES = 50;

    private static final String[] VERSION_OPTIONS = {
            "*", "26.2", "26.1", "1.21*", "1.20*", "1.19*", "1.18*", "1.16*", "1.12*", "1.8*"
    };
    private static final int[] MIN_PLAYER_OPTIONS = {0, 1, 2, 5, 10, 20, 50, 100};
    private static final int[] MAX_PLAYER_OPTIONS = {10, 20, 50, 100, 200, 500, 1000, 999999};
    private static final String[] SERVER_TYPE_LABELS = {"Any", "Premium", "Cracked"};
    private static final String[] SORT_LABELS = {"Recent", "Players", "Version", "Address", "Port"};
    private static final String[] SORT_API_VALUES = {"", "users", "version", "address", "port"};
    private static final int[] AUTO_ADD_LIMITS = {10, 25, 50, 0};

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
        state.autoAdd = ToolState.autoAddDefault;
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
            Object row = addTracked(s, Reflection.makeButton("", cx - 226, rowY + i * 23, 330, 20, b -> toggleResult(s, index)));
            Object add = addTracked(s, Reflection.makeButton("Add", cx + 108, rowY + i * 23, 54, 20, b -> addResult(s, index)));
            Object detail = addTracked(s, Reflection.makeButton("Details", cx + 166, rowY + i * 23, 60, 20, b -> showDetails(s, index)));
            s.resultButtons.add(row);
            s.resultAddButtons.add(add);
            s.resultDetailButtons.add(detail);
        }
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
        s.autoAdd = !s.autoAdd;
        Reflection.setButtonText(s.autoButton, autoLabel(s));
        setStatus(s, s.autoAdd ? "Auto-add ON. Start a search to run continuously." : "Auto-add OFF.");
    }

    static void resetSearchState(OverlayState s, String status) {
        if (s.loading) { setStatus(s, "Wait for the current BreakBlocks request to finish."); return; }
        s.nextApiPage = 0;
        s.seenEndpoints.clear();
        s.currentBatch.clear();
        s.results = List.of();
        s.exhausted = false;
        refreshRows(s);
        setStatus(s, status);
    }

    static void findNewServers(OverlayState s) {
        ToolState.reloadBreakBlocksApiKey();
        if (!ToolState.hasBreakBlocksApiKey()) s.apiKeyDisabledForSession = false;
        if (!s.open || s.loading) {
            if (s.loading) setStatus(s, "Wait for the current BreakBlocks request to finish.");
            return;
        }
        if (MIN_PLAYER_OPTIONS[s.minIndex] > MAX_PLAYER_OPTIONS[s.maxIndex]) {
            setStatus(s, "Minimum players cannot be above maximum players.");
            return;
        }
        if (s.exhausted) {
            if (ToolState.hasBreakBlocksApiKey() && !s.apiKeyDisabledForSession) {
                setStatus(s, "No more not-previously-added results in the authenticated result window.");
            } else if (s.serverTypeIndex == 2) {
                setStatus(s, "No more cracked results in the available 2-page public window.");
            } else {
                setStatus(s, "No more not-previously-added results in this public result window.");
            }
            return;
        }
        s.loading = true;
        s.currentBatch.clear();
        setStatus(s, "Finding servers not added before…");
        fetchUntilBatchFull(s);
    }

    private static void fetchUntilBatchFull(OverlayState s) {
        if (!s.open) { s.loading = false; return; }
        if (s.currentBatch.size() >= DISPLAY_RESULTS || s.nextApiPage >= maxApiPages(s)) {
            if (s.nextApiPage >= maxApiPages(s)) s.exhausted = true;
            finishBatch(s);
            return;
        }

        int page = s.nextApiPage++;
        StringBuilder url = new StringBuilder(API_URL)
                .append("?version=").append(enc(VERSION_OPTIONS[s.versionIndex]))
                .append("&minUsers=").append(MIN_PLAYER_OPTIONS[s.minIndex])
                .append("&maxUsers=").append(MAX_PLAYER_OPTIONS[s.maxIndex])
                .append("&page=").append(page)
                .append("&limit=").append(API_LIMIT)
                .append("&maxAge=1");
        if (s.serverTypeIndex == 2) url.append("&offlineOnly=on");
        if (!SORT_API_VALUES[s.sortIndex].isBlank()) url.append("&sort=").append(enc(SORT_API_VALUES[s.sortIndex]));

        sendApiPage(s, page, URI.create(url.toString()), true);
    }

    private static void sendApiPage(OverlayState s, int page, URI uri, boolean allowAuthentication) {
        String apiKey = allowAuthentication && !s.apiKeyDisabledForSession ? ToolState.breakBlocksApiKey() : "";
        boolean authenticated = !apiKey.isBlank();
        HttpRequest request = buildBreakBlocksRequest(uri, apiKey);
        HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, error) -> Reflection.execute(s.client,
                        () -> handleApiPage(s, page, uri, authenticated, response, error)));
    }

    static HttpRequest buildBreakBlocksRequest(URI uri, String apiKey) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(12))
                .header("Accept", "application/json")
                .header("User-Agent", "ZazusServerTool/0.3.34")
                .GET();
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey.trim());
        }
        return builder.build();
    }

    private static void handleApiPage(OverlayState s, int page, URI uri, boolean authenticated,
                                      HttpResponse<String> response, Throwable error) {
        if (!s.open) { s.loading = false; return; }
        if (error != null) { failSearch(s, rootMessage(error)); return; }
        if (response == null) { failSearch(s, "No response from BreakBlocks."); return; }

        int status = response.statusCode();
        if (authenticated && (status == 401 || status == 403)) {
            // A bad/expired/restricted key must never make the Finder unusable.
            // Retry this exact page anonymously and keep the key out of all logs/messages.
            s.apiKeyDisabledForSession = true;
            setStatus(s, "BreakBlocks API key was rejected; continuing anonymously.");
            sendApiPage(s, page, uri, false);
            return;
        }
        if (status == 429) {
            String retry = response.headers().firstValue("Retry-After").orElse("").trim();
            failSearch(s, retry.isBlank()
                    ? "BreakBlocks rate limit reached. Try again shortly."
                    : "BreakBlocks rate limit reached. Retry in " + retry + "s.");
            return;
        }
        if (status / 100 != 2) { failSearch(s, "BreakBlocks returned HTTP " + status); return; }
        if (authenticated) s.apiKeyAcceptedThisSession = true;
        try {
            SearchResult parsed = parseSearchResult(response.body());
            if (parsed.servers().isEmpty()) {
                s.exhausted = true;
                finishBatch(s);
                return;
            }
            List<ServerRecord> candidates = new ArrayList<>();
            int newApiEndpoints = 0;
            for (ServerRecord r : parsed.servers()) {
                String endpoint = normalizeEndpoint(r.endpoint());
                if (!s.seenEndpoints.add(endpoint)) continue;
                newApiEndpoints++;
                if (s.serverTypeIndex == 1 && r.offlineMode()) continue; // premium only
                if (ToolState.isBlocked(endpoint)) continue;
                if (ToolState.skipAddedHistory && ToolState.wasAdded(endpoint)) continue;
                try { if (ServerListBridge.contains(s.client, endpoint)) continue; } catch (Throwable ignored) {}
                candidates.add(r);
            }
            // Some account tiers cap pagination by repeating/clamping the last page.
            // Stop rather than burning authenticated quota on duplicate-only pages.
            if (newApiEndpoints == 0) {
                s.exhausted = true;
                finishBatch(s);
                return;
            }
            if (candidates.isEmpty()) { fetchUntilBatchFull(s); return; }
            verifyLiveCandidates(s, candidates, false);
        } catch (Throwable t) {
            failSearch(s, "Could not read BreakBlocks response: " + rootMessage(t));
        }
    }

    private static void verifyLiveCandidates(OverlayState s, List<ServerRecord> candidates, boolean addImmediately) {
        List<CompletableFuture<ServerRecord>> futures = new ArrayList<>();
        for (ServerRecord record : candidates) {
            futures.add(CompletableFuture.supplyAsync(() -> probeMinecraftServer(record)));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).whenComplete((v, error) -> {
            List<ServerRecord> live = new ArrayList<>();
            for (CompletableFuture<ServerRecord> f : futures) {
                try { ServerRecord r = f.getNow(null); if (r != null) live.add(r); } catch (Throwable ignored) {}
            }
            Reflection.execute(s.client, () -> {
                if (!s.open) { s.loading = false; return; }
                for (ServerRecord r : live) {
                    if (s.currentBatch.size() >= DISPLAY_RESULTS) break;
                    if (!ToolState.isBlocked(r.endpoint())) s.currentBatch.add(r);
                }
                if (addImmediately || s.autoAdd) {
                    for (ServerRecord r : live) {
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
                                System.out.println("[Zazu's Server Tool] Added live server " + r.endpoint());
                            }
                        } catch (Throwable t) { log("Server-list change failed: " + r.endpoint(), t); }
                    }
                }
                continueAfterLiveChecks(s);
            });
        });
    }

    private static void continueAfterLiveChecks(OverlayState s) {
        if (s.currentBatch.size() >= DISPLAY_RESULTS || s.exhausted) finishBatch(s);
        else fetchUntilBatchFull(s);
    }

    static ServerRecord probeMinecraftServer(ServerRecord record) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(record.address(), record.port()), 2800);
            socket.setSoTimeout(2800);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            ByteArrayOutputStream handshake = new ByteArrayOutputStream();
            writeVarInt(handshake, 0);       // handshake packet id
            writeVarInt(handshake, 0);       // protocol: status probing does not require exact client protocol
            writeMinecraftString(handshake, record.address());
            handshake.write((record.port() >>> 8) & 0xff);
            handshake.write(record.port() & 0xff);
            writeVarInt(handshake, 1);       // next state = status
            byte[] h = handshake.toByteArray();
            writeVarInt(out, h.length); out.write(h);

            writeVarInt(out, 1); writeVarInt(out, 0); // status request
            out.flush();

            readVarInt(in);                  // packet length
            int packetId = readVarInt(in);
            if (packetId != 0) return null;
            int jsonLength = readVarInt(in);
            if (jsonLength <= 0 || jsonLength > 1_000_000) return null;
            String json = new String(in.readNBytes(jsonLength), StandardCharsets.UTF_8);
            Object root = new JsonParser(json).parse();
            if (!(root instanceof Map<?, ?> map)) return record;
            Object versionObj = map.get("version");
            if (!(versionObj instanceof Map<?, ?> vm)) return record;
            String name = asString(vm.get("name"), record.version());
            int protocol = asInt(vm.get("protocol"), -1);
            return record.withLiveVersion(name, protocol);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void writeMinecraftString(OutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length); out.write(bytes);
    }

    private static void writeVarInt(OutputStream out, int value) throws IOException {
        int v = value;
        while ((v & ~0x7f) != 0) { out.write((v & 0x7f) | 0x80); v >>>= 7; }
        out.write(v);
    }

    private static int readVarInt(InputStream in) throws IOException {
        int value = 0, position = 0;
        while (true) {
            int current = in.read();
            if (current == -1) throw new EOFException("Connection closed while reading VarInt");
            value |= (current & 0x7f) << position;
            if ((current & 0x80) == 0) return value;
            position += 7;
            if (position >= 35) throw new IOException("VarInt is too large");
        }
    }

    private static void finishBatch(OverlayState s) {
        s.loading = false;
        s.results = List.copyOf(s.currentBatch);
        refreshRows(s);
        String suffix = s.exhausted ? " — end of public results" : " — next API page: " + s.nextApiPage;
        setStatus(s, "New: " + s.results.size() + " shown this search; " + s.autoAddedThisSession + " added" + suffix);
        refreshStats(s);
    }

    private static void failSearch(OverlayState s, String message) {
        s.loading = false;
        setStatus(s, message);
    }

    private static void refreshRows(OverlayState s) {
        for (int i = 0; i < DISPLAY_RESULTS; i++) {
            boolean has = i < s.results.size();
            Object row = s.resultButtons.size() > i ? s.resultButtons.get(i) : null;
            Object add = s.resultAddButtons.size() > i ? s.resultAddButtons.get(i) : null;
            Object detail = s.resultDetailButtons.size() > i ? s.resultDetailButtons.get(i) : null;
            Reflection.setBoolean(row, "visible", has); Reflection.setBoolean(row, "active", has);
            Reflection.setBoolean(add, "visible", has); Reflection.setBoolean(add, "active", has);
            Reflection.setBoolean(detail, "visible", has); Reflection.setBoolean(detail, "active", has);
            if (has) {
                ServerRecord r = s.results.get(i);
                String text = shorten(r.endpoint() + " | " + r.version() + " | " + r.playersOnline() + "/" + r.playersMax() + " | LIVE", 58);
                Reflection.setButtonText(row, text);
                try { Reflection.setButtonText(add, ServerListBridge.contains(s.client, r.endpoint()) ? "Saved" : "Add"); }
                catch (Throwable ignored) { Reflection.setButtonText(add, "Add"); }
            }
        }
    }

    private static void addResult(OverlayState s, int index) {
        if (index < 0 || index >= s.results.size()) return;
        ServerRecord record = s.results.get(index);
        if (ToolState.isBlocked(record.endpoint())) { setStatus(s, "That server is blocked. Open Details to unblock it."); return; }
        setStatus(s, "Checking " + record.endpoint() + "…");
        CompletableFuture.supplyAsync(() -> probeMinecraftServer(record)).whenComplete((live, error) -> Reflection.execute(s.client, () -> {
            if (error != null || live == null) { setStatus(s, "Server is no longer responding."); return; }
            try {
                if (ServerListBridge.add(s.client, live)) setStatus(s, "Added " + live.endpoint());
                else if (ServerListBridge.contains(s.client, live.endpoint())) setStatus(s, "Server is already saved.");
                else setStatus(s, "Server could not be added.");
                refreshRows(s); refreshStats(s);
            } catch (Throwable t) { setStatus(s, "Server-list change failed: " + rootMessage(t)); }
        }));
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
        CompletableFuture.supplyAsync(() -> probeMinecraftServer(r)).whenComplete((live, error) -> Reflection.execute(s.client, () -> {
            if (live == null) { setStatus(s, "Server is no longer responding."); return; }
            try { ServerListBridge.add(s.client, live); clearSubView(s); showMain(s); refreshRows(s); refreshStats(s); }
            catch (Throwable t) { log("Add from details failed", t); }
        }));
    }
    private static void removeFromDetails(OverlayState s, ServerRecord r) {
        try { ServerListBridge.remove(s.client, r.endpoint()); clearSubView(s); showMain(s); refreshRows(s); refreshStats(s); }
        catch (Throwable t) { log("Remove from details failed", t); }
    }

    private static void showSettings(OverlayState s) {
        try {
            clearSubView(s); hideMain(s);
            int cx = s.width / 2, x = cx - 210, y = 35;
            addSubLabel(s, "Zazu's Server Tool Settings", x, y, 420); y += 28;
            addSub(s, Reflection.makeButton("Skip Added Before: " + onOff(ToolState.skipAddedHistory), x, y, 200, 20, b -> { ToolState.skipAddedHistory = !ToolState.skipAddedHistory; ToolState.save(); showSettings(s); }));
            addSub(s, Reflection.makeButton("Block Deleted: " + onOff(ToolState.blockDeleted), x + 210, y, 200, 20, b -> { ToolState.blockDeleted = !ToolState.blockDeleted; ToolState.save(); showSettings(s); })); y += 24;
            addSub(s, Reflection.makeButton("Favourites First: " + onOff(ToolState.favouritesFirst), x, y, 200, 20, b -> { ToolState.favouritesFirst = !ToolState.favouritesFirst; ToolState.save(); showSettings(s); }));
            addSub(s, Reflection.makeButton("Auto-add Default: " + onOff(ToolState.autoAddDefault), x + 210, y, 200, 20, b -> { ToolState.autoAddDefault = !ToolState.autoAddDefault; ToolState.save(); showSettings(s); })); y += 24;
            addSub(s, Reflection.makeButton("Auto-add Limit: " + autoAddLimitLabel(), x, y, 200, 20, b -> { cycleAutoAddLimit(); ToolState.save(); showSettings(s); }));
            addSubLabel(s, "Defaults follow the Version / Player / Sort controls.", x + 210, y, 200); y += 28;
            addSub(s, Reflection.makeButton("Clear Added History (" + ToolState.addedHistoryCount() + ")", x, y, 200, 20, b -> { ToolState.clearAddedHistory(); showSettings(s); }));
            addSub(s, Reflection.makeButton("Reset Added/Deleted Stats", x + 210, y, 200, 20, b -> { ToolState.resetStats(); showSettings(s); })); y += 28;
            ToolState.reloadBreakBlocksApiKey();
            addSubLabel(s, breakBlocksApiStatusLabel(s), x, y, 410); y += 24;
            addSubLabel(s, "Config key: breakBlocksApiKey=...", x, y, 260);
            addSub(s, Reflection.makeButton("Back", x + 310, y, 100, 20, b -> { clearSubView(s); showMain(s); refreshStats(s); })); y += 24;
            addSubLabel(s, "config/zazus-server-tool.properties", x, y, 410);
        } catch (Throwable t) { log("Could not open settings", t); clearSubView(s); showMain(s); }
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
    private static int maxApiPages(OverlayState s) {
        if (ToolState.hasBreakBlocksApiKey() && !s.apiKeyDisabledForSession) return MAX_AUTHENTICATED_PAGES;
        return s.serverTypeIndex == 2 ? 2 : MAX_PUBLIC_PAGES;
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
    private static void log(String message, Throwable t) { System.err.println("[Zazu's Server Tool] " + message + ": " + Reflection.unwrap(t)); }
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
                        asBoolean(m.get("offline_mode"), false),
                        stringList(m.get("plugins")),
                        -1));
            }
        }
        int displayed = asInt(data.get("displayed"), servers.size());
        int total = asInt(data.get("total"), displayed);
        int filtered = asInt(data.get("filtered"), total);
        return new SearchResult(servers, displayed, total, filtered);
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
        boolean open, loading, autoAdd, exhausted, apiKeyDisabledForSession, apiKeyAcceptedThisSession;
        int versionIndex, minIndex, maxIndex, sortIndex, serverTypeIndex, nextApiPage, autoAddedThisSession, blockedPage;
        List<ServerRecord> results = List.of();
        Object versionButton, minButton, maxButton, serverTypeButton, findButton, autoButton, resetButton, closeButton, sortButton, settingsButton, blockedButton, statsButton, statusButton;
        OverlayState(Object client, Object screen, int width, int height) { this.client = client; this.screen = screen; this.width = width; this.height = height; }
    }

    record WidgetState(boolean visible, boolean active) {}
    record SearchResult(List<ServerRecord> servers, int displayed, int total, int filtered) {}
    record ServerRecord(String address, int port, String version, int playersOnline, int playersMax,
                        String motd, String country, String countryCode, String city, String region,
                        String lastPing, String modpack, boolean offlineMode, List<String> plugins, int protocol) {
        ServerRecord withLiveVersion(String liveVersion, int liveProtocol) {
            return new ServerRecord(address, port, liveVersion == null || liveVersion.isBlank() ? version : liveVersion,
                    playersOnline, playersMax, motd, country, countryCode, city, region, lastPing, modpack,
                    offlineMode, plugins, liveProtocol);
        }
        String endpoint() { return port == 25565 ? address : address + ":" + port; }
        String displayName() { return "Zazu " + address + " [" + version + "]"; }
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
            if (!invokeAdd(list, data)) {
                List<Object> backing = mutableBackingList(list);
                if (backing == null) throw new IllegalStateException("Could not access Minecraft's server list.");
                backing.add(data);
            }
            save(list);
            ToolState.recordAdded(record.endpoint(), record.version(), record.protocol());
            ServerCategoryStore.markScanned(record.endpoint());
            return true;
        }

        static boolean remove(Object client, String endpoint) throws Exception {
            Object list = createLoadedList(client);
            Object server = findServer(list, endpoint);
            if (server == null) return false;
            if (serverName(server).startsWith("★ ")) return false;
            if (!invokeRemove(list, server)) {
                List<Object> backing = mutableBackingList(list);
                if (backing == null || !backing.remove(server)) throw new IllegalStateException("Could not remove the server.");
            }
            save(list);
            ToolState.recordDeleted(endpoint);
            ServerCategoryStore.remove(endpoint);
            return true;
        }

        static int countFavourites(Object client) {
            try {
                int count = 0;
                for (Object server : servers(createLoadedList(client))) if (serverName(server).startsWith("★ ")) count++;
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
