package dev.zazuzin.zst;

import net.fabricmc.api.ClientModInitializer;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * v0.3.33 release-clean Multiplayer workflow.
 *
 * Multiplayer first opens a lightweight category hub. The vanilla saved-server
 * list is only shown after choosing Favourites / Servers / Scanned Servers.
 * No server-list reload is performed by the hub or while changing categories.
 */
public final class ServerTabsEntrypoint implements ClientModInitializer {
    private static final String CORE_AUTO_JOIN = "dev.zazuzin.zst.AutoJoinEntrypoint";
    private static final long STABLE_JOIN_MS = 8_000L;
    private static final long ATTEMPT_CONTEXT_MS = 15_000L;
    private static final Map<Object, State> STATES = Collections.synchronizedMap(new WeakHashMap<>());

    private static volatile List<ServerListAccess.Saved> lastKnownSaved = List.of();
    private static volatile String lastSelectedEndpoint = "";
    private static volatile long lastSelectedAt;
    private static volatile String activeAttemptEndpoint = "";
    private static volatile long activeAttemptAt;
    private static volatile long playGeneration;

    @Override
    public void onInitializeClient() {
        ServerCategoryStore.load();
        AutoJoinDiagnostics.install();
        FinderLatencyOverlay.install();
        try {
            registerScreenWatcher();
            registerPlayWatchers();
            System.out.println("[Zazu's Server Tool] 0.3.33 release-clean Multiplayer category/options hub enabled.");
        } catch (Throwable t) {
            System.err.println("[Zazu's Server Tool] Could not enable Multiplayer category hub: " + root(t));
        }
    }

    private static void registerScreenWatcher() throws Exception {
        Class<?> holder = Class.forName("net.fabricmc.fabric.api.client.screen.v1.ScreenEvents");
        Class<?> callback = Class.forName("net.fabricmc.fabric.api.client.screen.v1.ScreenEvents$AfterInit");
        Object event = holder.getField("AFTER_INIT").get(null);
        Object listener = Proxy.newProxyInstance(callback.getClassLoader(), new Class<?>[]{callback}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return RuntimeAccess.objectMethod(proxy, method, args);
            if (args == null || args.length < 4 || args[1] == null) return null;
            Object client = args[0], screen = args[1];
            int width = ((Number) args[2]).intValue(), height = ((Number) args[3]).intValue();
            try {
                if (RuntimeAccess.isScreen(screen, "JoinMultiplayerScreen")) onMultiplayerInit(client, screen, width, height);
                else if (RuntimeAccess.isScreen(screen, "ConnectScreen")) captureConnectAttempt(screen);
            } catch (Throwable t) {
                System.err.println("[Zazu's Server Tool] Multiplayer category screen hook failed: " + root(t));
            }
            return null;
        });
        RuntimeAccess.registerEvent(event, listener);
    }

    private static void registerPlayWatchers() throws Exception {
        registerSimpleEvent("net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents", "JOIN",
                "net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents$Join", ServerTabsEntrypoint::onPlayJoin);
        registerSimpleEvent("net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents", "DISCONNECT",
                "net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents$Disconnect", () -> playGeneration++);
    }

    private static void registerSimpleEvent(String holderName, String fieldName, String callbackName, Runnable action) throws Exception {
        Class<?> holder = Class.forName(holderName);
        Class<?> callback = Class.forName(callbackName);
        Object event = holder.getField(fieldName).get(null);
        Object listener = Proxy.newProxyInstance(callback.getClassLoader(), new Class<?>[]{callback}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return RuntimeAccess.objectMethod(proxy, method, args);
            action.run();
            return null;
        });
        RuntimeAccess.registerEvent(event, listener);
    }

    private static void onMultiplayerInit(Object client, Object screen, int width, int height) throws Exception {
        // JoinMultiplayerScreen can be re-initialised without changing object identity
        // (refresh, resize, returning from a child screen). Remove the previous
        // enhancement controls first so they are never mistaken for vanilla/core
        // widgets by the new State.
        State previous = STATES.get(screen);
        if (previous != null) detachCustomWidgets(previous);

        State state = new State(client, screen, width, height);
        STATES.put(screen, state);

        state.baseWidgets.addAll(widgets(screen));
        state.baseWidgets.removeIf(MultiplayerManagementEntrypoint::isManagedWidget);
        state.listWidget = ServerListAccess.listWidget(screen);
        captureOriginalBounds(state);
        captureOriginalBounds(state, MultiplayerManagementEntrypoint.finderButton(screen));
        captureOriginalBounds(state, MultiplayerManagementEntrypoint.deleteNonFavouritesButton(screen));
        captureFullRows(state, true);
        createNavigationControls(state);

        // While Auto Join is actively returning after a failure, do not interrupt
        // the sequence with the hub. Resume directly in Scanned Servers instead.
        if (coreAutoJoinEnabled()) {
            seedCoreAutoJoinExclusions(state.saved);
            showCategory(state, ServerCategoryStore.Tab.SCANNED);
        } else {
            showHub(state);
        }

        registerAfterTick(state);
        System.out.println("[Zazu's Server Tool] Multiplayer category hub installed on "
                + screen.getClass().getName() + " at " + width + "x" + height + ".");
    }

    /**
     * Creates only controls owned by the category workflow. They are physically
     * attached to the screen only in views that use them; no custom control is
     * hidden off-screen or left in the active widget list.
     */
    private static void createNavigationControls(State state) throws Exception {
        int buttonWidth = Math.min(300, Math.max(220, state.width / 3));
        int x = (state.width - buttonWidth) / 2;
        int gap = 24;
        int startY = Math.max(52, state.height / 2 - 62);

        state.favouritesButton = makeButton("Favourites", x, startY, buttonWidth, 20,
                b -> showCategory(state, ServerCategoryStore.Tab.FAVOURITES));
        state.serversButton = makeButton("Servers", x, startY + gap, buttonWidth, 20,
                b -> showCategory(state, ServerCategoryStore.Tab.SERVERS));
        state.scannedButton = makeButton("Scanned Servers", x, startY + gap * 2, buttonWidth, 20,
                b -> showCategory(state, ServerCategoryStore.Tab.SCANNED));
        state.backButton = makeButton("Back", x, startY + gap * 4, buttonWidth, 20, b -> {
            if (state.view == View.HUB) {
                leaveMultiplayer(state);
            } else {
                if (coreAutoJoinEnabled()) stopCoreAutoJoin(true);
                showHub(state);
            }
        });

        Object tool = MultiplayerManagementEntrypoint.finderButton(state.screen);
        Bounds toolBounds = originalBounds(state, tool);
        int leftWidth = toolBounds != null ? toolBounds.width : Math.min(220, Math.max(170, state.width / 5));
        int toolX = toolBounds != null ? toolBounds.x : 6;
        int toolY = toolBounds != null ? toolBounds.y : Math.max(6, state.height - 28);
        int buttonH = toolBounds != null ? toolBounds.height : 20;
        int categoriesY = Math.max(6, toolY - buttonH - 4);
        state.categoriesButton = makeButton("Categories", toolX, categoriesY, leftWidth, buttonH, b -> {
            if (coreAutoJoinEnabled()) stopCoreAutoJoin(true);
            showHub(state);
        });
    }

    private static void showHub(State state) {
        if (state == null) return;
        state.view = View.HUB;
        restoreFullRows(state);
        refreshCachedSaved(state, false);
        applyLayout(state);
        updateButtons(state);
    }

    private static void showCategory(State state, ServerCategoryStore.Tab tab) {
        if (state == null || tab == null) return;
        if (tab != ServerCategoryStore.Tab.SCANNED && coreAutoJoinEnabled()) stopCoreAutoJoin(true);
        state.view = switch (tab) {
            case FAVOURITES -> View.FAVOURITES;
            case SERVERS -> View.SERVERS;
            case SCANNED -> View.SCANNED;
        };
        applyCategoryRows(state, tab);
        MultiplayerManagementEntrypoint.rebuildRowButtons(state.screen);
        applyLayout(state);
        updateButtons(state);
    }

    private static void registerAfterTick(State state) throws Exception {
        Class<?> holder = Class.forName("net.fabricmc.fabric.api.client.screen.v1.ScreenEvents");
        Method factory = null;
        for (Method m : holder.getMethods()) {
            if (Modifier.isStatic(m.getModifiers()) && m.getName().equals("afterTick") && m.getParameterCount() == 1
                    && m.getParameterTypes()[0].isInstance(state.screen)) { factory = m; break; }
        }
        if (factory == null) throw new NoSuchMethodException("ScreenEvents.afterTick(Screen)");
        Object event = factory.invoke(null, state.screen);
        Class<?> callback = Class.forName("net.fabricmc.fabric.api.client.screen.v1.ScreenEvents$AfterTick");
        Object listener = Proxy.newProxyInstance(callback.getClassLoader(), new Class<?>[]{callback}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return RuntimeAccess.objectMethod(proxy, method, args);
            try { tick(state); } catch (Throwable t) {
                if (!state.loggedFailure) {
                    state.loggedFailure = true;
                    System.err.println("[Zazu's Server Tool] Multiplayer category tick failed: " + root(t));
                }
            }
            return null;
        });
        RuntimeAccess.registerEvent(event, listener);
    }

    private static void tick(State state) {
        Object client = RuntimeAccess.minecraftInstance();
        Object current = RuntimeAccess.field(client, "screen");
        if (current == null) current = RuntimeAccess.invoke(client, "getScreen");
        if (current != null && current != state.screen) return;

        if (finderOpen(state.screen)) {
            detachCustomWidgets(state);
            return;
        }

        observeVanillaListChanges(state);
        refreshCachedSaved(state, true);

        if (state.view != View.HUB) {
            String selected = ServerListAccess.selectedEndpoint(state.screen);
            if (!selected.isBlank()) {
                lastSelectedEndpoint = selected;
                lastSelectedAt = System.currentTimeMillis();
            }
        }

        if (coreAutoJoinEnabled()) {
            if (state.view != View.SCANNED) stopCoreAutoJoin(true);
            else seedCoreAutoJoinExclusions(state.saved);
        }

        applyLayout(state);
        updateButtons(state);
    }

    private static void observeVanillaListChanges(State state) {
        List<Object> current = ServerListAccess.onlineEntries(state.screen);
        if (current == null) return;

        List<Object> expected = expectedVisibleRows(state);
        if (sameIdentitySequence(current, expected)) return;

        boolean hasUnknown = containsUnknownIdentity(current, state.fullEntries);
        if (state.view == View.HUB || hasUnknown) {
            state.fullEntries = new ArrayList<>(current);
            refreshCachedSaved(state, false);
            if (state.view != View.HUB) {
                applyCategoryRows(state, tabForView(state.view));
                MultiplayerManagementEntrypoint.rebuildRowButtons(state.screen);
            }
            return;
        }

        // A vanilla/core refresh can remove a currently visible row without
        // reconstructing every remaining row. Drop only the missing visible rows;
        // hidden categories remain untouched.
        Set<Object> currentIdentity = identitySet(current);
        boolean changed = state.fullEntries.removeIf(entry -> expected.contains(entry) && !currentIdentity.contains(entry));
        if (changed) {
            refreshCachedSaved(state, false);
            if (state.view != View.HUB) {
                applyCategoryRows(state, tabForView(state.view));
                MultiplayerManagementEntrypoint.rebuildRowButtons(state.screen);
            }
        }
    }

    private static void captureFullRows(State state, boolean migrate) {
        captureBackingLists(state);
        List<Object> rows = ServerListAccess.onlineEntries(state.screen);
        if (rows == null || rows.isEmpty()) rows = largestSavedSnapshot(state);
        state.fullEntries = rows == null ? new ArrayList<>() : new ArrayList<>(rows);
        refreshCachedSaved(state, false);
        List<String> endpoints = state.saved.stream().map(ServerListAccess.Saved::endpoint).toList();
        if (migrate) ServerCategoryStore.migrateExisting(endpoints);
        ServerCategoryStore.syncNew(endpoints);
        state.lastSignature = ServerListAccess.signature(state.saved);
    }

    private static void captureBackingLists(State state) {
        state.backingLists.clear();
        for (List<Object> list : ServerListAccess.serverEntryLists(state.screen)) {
            state.backingLists.put(list, new ArrayList<>(list));
        }
    }

    private static List<Object> largestSavedSnapshot(State state) {
        List<Object> best = List.of();
        int bestCount = -1;
        for (List<Object> snapshot : state.backingLists.values()) {
            int count = ServerListAccess.savedFromEntries(snapshot).size();
            if (count > bestCount) { best = snapshot; bestCount = count; }
        }
        return best;
    }

    private static void refreshCachedSaved(State state, boolean detectChanges) {
        state.saved = ServerListAccess.savedFromEntries(state.fullEntries);
        String signature = ServerListAccess.signature(state.saved);
        if (!detectChanges || !signature.equals(state.lastSignature)) {
            state.lastSignature = signature;
            List<String> endpoints = state.saved.stream().map(ServerListAccess.Saved::endpoint).toList();
            ServerCategoryStore.syncNew(endpoints);
            lastKnownSaved = List.copyOf(state.saved);
            if (detectChanges && state.view != View.HUB) {
                applyCategoryRows(state, tabForView(state.view));
                MultiplayerManagementEntrypoint.rebuildRowButtons(state.screen);
            }
        } else {
            lastKnownSaved = List.copyOf(state.saved);
        }
    }

    private static void restoreFullRows(State state) {
        syncBackingListReferences(state);
        boolean restoredAny = false;
        for (Map.Entry<List<Object>, List<Object>> e : new ArrayList<>(state.backingLists.entrySet())) {
            List<Object> live = e.getKey(), full = e.getValue();
            if (sameIdentitySequence(live, full)) continue;
            try {
                live.clear();
                live.addAll(full);
                restoredAny = true;
            } catch (Throwable ignored) {}
        }
        if (!restoredAny) {
            List<Object> live = ServerListAccess.onlineEntries(state.screen);
            if (live != null && !sameIdentitySequence(live, state.fullEntries)) {
                try { live.clear(); live.addAll(state.fullEntries); }
                catch (Throwable t) { logOnce(state, "Could not restore full Multiplayer row list", t); }
            }
        }
    }

    private static void applyCategoryRows(State state, ServerCategoryStore.Tab tab) {
        syncBackingListReferences(state);
        boolean filteredAny = false;
        for (Map.Entry<List<Object>, List<Object>> e : new ArrayList<>(state.backingLists.entrySet())) {
            List<Object> live = e.getKey();
            List<Object> filtered = ServerListAccess.filterServerRows(e.getValue(), tab);
            if (sameIdentitySequence(live, filtered)) { filteredAny = true; continue; }
            try {
                live.clear();
                live.addAll(filtered);
                filteredAny = true;
            } catch (Throwable ignored) {}
        }
        if (!filteredAny) {
            List<Object> live = ServerListAccess.onlineEntries(state.screen);
            if (live == null) return;
            List<Object> filtered = filterRows(state.fullEntries, tab);
            if (sameIdentitySequence(live, filtered)) return;
            try { live.clear(); live.addAll(filtered); }
            catch (Throwable t) { logOnce(state, "Could not filter Multiplayer rows", t); }
        }
    }

    private static void syncBackingListReferences(State state) {
        for (List<Object> list : ServerListAccess.serverEntryLists(state.screen)) {
            if (state.backingLists.containsKey(list)) continue;
            // A new list object normally means vanilla/core refreshed the screen.
            // Treat its current contents as the new complete snapshot.
            state.backingLists.put(list, new ArrayList<>(list));
            if (ServerListAccess.savedFromEntries(list).size() > ServerListAccess.savedFromEntries(state.fullEntries).size()) {
                state.fullEntries = new ArrayList<>(list);
                refreshCachedSaved(state, false);
            }
        }
    }

    private static List<Object> expectedVisibleRows(State state) {
        if (state.view == View.HUB) return state.fullEntries;
        return filterRows(state.fullEntries, tabForView(state.view));
    }

    private static List<Object> filterRows(List<Object> source, ServerCategoryStore.Tab tab) {
        return ServerListAccess.filterServerRows(source, tab);
    }

    /**
     * One authoritative layout path. A control that is not part of the current
     * view is removed from the active Fabric widget list rather than hidden or
     * moved off-screen. Minecraft's own controls keep their original bounds.
     */
    private static void applyLayout(State state) {
        if (state == null) return;

        if (finderOpen(state.screen)) {
            detachCustomWidgets(state);
            return;
        }

        List<Object> live = widgets(state.screen);
        if (live == null) return;

        boolean learnedWidgets = learnBaseWidgets(state, live);
        captureMissingOriginalBounds(state);
        boolean layoutNeeded = learnedWidgets || state.appliedView != state.view;

        List<Object> desired = new ArrayList<>();
        if (state.view == View.HUB) {
            desired.add(state.favouritesButton);
            desired.add(state.serversButton);
            desired.add(state.scannedButton);

            // Reuse the core Server Tool button instead of creating a second proxy.
            Object tool = MultiplayerManagementEntrypoint.finderButton(state.screen);
            if (tool != null) desired.add(tool);
            desired.add(state.backButton);
            if (layoutNeeded) layoutHub(state, tool);

            // Scanned-only control does not exist while the hub is open.
            state.autoJoinButton = null;
        } else {
            for (Object widget : state.baseWidgets) {
                if (widget == null || isCustom(state, widget) || MultiplayerManagementEntrypoint.isManagedWidget(widget)) continue;
                if (shouldUseBaseWidget(state, widget)) desired.add(widget);
            }

            Object tool = MultiplayerManagementEntrypoint.finderButton(state.screen);
            if (tool != null) desired.add(tool);
            if (state.view == View.SERVERS) {
                Object deleteNonFavourites = MultiplayerManagementEntrypoint.deleteNonFavouritesButton(state.screen);
                if (deleteNonFavourites != null) desired.add(deleteNonFavourites);
            }
            desired.addAll(MultiplayerManagementEntrypoint.rowWidgets(state.screen));

            if (state.listWidget != null && !containsIdentity(desired, state.listWidget)) {
                desired.add(0, state.listWidget);
            }

            desired.add(state.categoriesButton);
            desired.add(state.backButton);

            if (state.view == View.SCANNED) {
                ensureAutoJoinButton(state);
                desired.add(state.autoJoinButton);
                setActive(state.autoJoinButton, autoJoinEligibleTotal() > 0 || coreAutoJoinEnabled());
            } else {
                state.autoJoinButton = null;
            }
            if (layoutNeeded) layoutCategoryControls(state);
        }

        desired.removeIf(Objects::isNull);
        dedupeIdentity(desired);
        replaceWidgetList(state, live, desired);
        state.appliedView = state.view;
    }

    private static boolean learnBaseWidgets(State state, List<Object> live) {
        boolean changed = state.baseWidgets.removeIf(MultiplayerManagementEntrypoint::isManagedWidget);
        for (Object widget : new ArrayList<>(live)) {
            if (widget == null || isCustom(state, widget) || MultiplayerManagementEntrypoint.isManagedWidget(widget)) continue;
            if (!containsIdentity(state.baseWidgets, widget)) {
                state.baseWidgets.add(widget);
                changed = true;
            }
        }
        return changed;
    }

    private static boolean shouldUseBaseWidget(State state, Object widget) {
        if (widget == null) return false;
        if (widget == state.listWidget) return true;

        String label = widgetLabel(widget).trim();
        // The category workflow owns Back. The legacy Auto Join UI has been
        // removed from the Auto Join engine, so no hidden replacement exists.
        if (label.equals("Back") || label.equals("Cancel") || label.equals("Done")) return false;
        if (label.equals("Delete Non-Favourites") || label.equals("Confirm Delete Non-Favs")) {
            return state.view == View.SERVERS;
        }
        return true;
    }

    private static void ensureAutoJoinButton(State state) {
        if (state.autoJoinButton != null) return;
        try {
            state.autoJoinButton = makeButton(autoJoinLabel(), 0, 0, 130, 20,
                    b -> toggleScannedAutoJoin(state));
        } catch (Throwable t) {
            logOnce(state, "Could not create Scanned Servers Auto Join control", t);
        }
    }

    private static void detachCustomWidgets(State state) {
        if (state == null) return;
        List<Object> live = widgets(state.screen);
        if (live == null || live.isEmpty()) return;
        try { live.removeIf(widget -> isCustom(state, widget)); }
        catch (Throwable ignored) {}
    }

    private static void replaceWidgetList(State state, List<Object> live, List<Object> desired) {
        if (sameIdentitySequence(live, desired)) return;
        try {
            live.clear();
            live.addAll(desired);
        } catch (Throwable t) {
            logOnce(state, "Could not rebuild Multiplayer widget layout", t);
        }
    }

    private static boolean containsIdentity(Collection<Object> values, Object target) {
        if (values == null || target == null) return false;
        for (Object value : values) if (value == target) return true;
        return false;
    }

    private static void dedupeIdentity(List<Object> values) {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        values.removeIf(value -> !seen.add(value));
    }

    private static void captureOriginalBounds(State state) {
        for (Object widget : state.baseWidgets) captureOriginalBounds(state, widget);
        captureOriginalBounds(state, state.listWidget);
    }

    private static void captureMissingOriginalBounds(State state) {
        for (Object widget : state.baseWidgets) {
            if (!state.originalBounds.containsKey(widget)) captureOriginalBounds(state, widget);
        }
    }

    private static void captureOriginalBounds(State state, Object widget) {
        if (widget == null || state.originalBounds.containsKey(widget)) return;
        int x = widgetInt(widget, "getX", "x", Integer.MIN_VALUE);
        int y = widgetInt(widget, "getY", "y", Integer.MIN_VALUE);
        int width = widgetInt(widget, "getWidth", "width", Integer.MIN_VALUE);
        int height = widgetInt(widget, "getHeight", "height", Integer.MIN_VALUE);
        if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE || width == Integer.MIN_VALUE || height == Integer.MIN_VALUE) return;
        if (width <= 0 || height <= 0) return;
        state.originalBounds.put(widget, new Bounds(x, y, width, height));
    }

    private static Bounds originalBounds(State state, Object widget) {
        return widget == null ? null : state.originalBounds.get(widget);
    }

    private static void layoutHub(State state, Object tool) {
        int buttonWidth = Math.min(300, Math.max(220, state.width / 3));
        int x = (state.width - buttonWidth) / 2;
        int gap = 24;
        int startY = Math.max(52, state.height / 2 - 62);
        setBounds(state.favouritesButton, x, startY, buttonWidth, 20);
        setBounds(state.serversButton, x, startY + gap, buttonWidth, 20);
        setBounds(state.scannedButton, x, startY + gap * 2, buttonWidth, 20);
        if (tool != null) setBounds(tool, x, startY + gap * 3, buttonWidth, 20);
        setBounds(state.backButton, x, startY + gap * 4, buttonWidth, 20);
    }

    private static void layoutCategoryControls(State state) {
        int margin = 6;
        Object tool = MultiplayerManagementEntrypoint.finderButton(state.screen);
        Bounds toolBounds = originalBounds(state, tool);
        if (tool != null && toolBounds != null) {
            setBounds(tool, toolBounds.x, toolBounds.y, toolBounds.width, toolBounds.height);
        }
        int toolX = toolBounds != null ? toolBounds.x : margin;
        int toolY = toolBounds != null ? toolBounds.y : Math.max(6, state.height - 28);
        int toolW = toolBounds != null ? toolBounds.width : Math.min(220, Math.max(170, state.width / 5));
        int toolH = toolBounds != null ? toolBounds.height : 20;
        int categoriesY = Math.max(6, toolY - toolH - 4);
        setBounds(state.categoriesButton, toolX, categoriesY, toolW, toolH);

        Object vanillaBack = findFirstBaseWidget(state, List.of("Back", "Cancel", "Done"));
        Bounds back = originalBounds(state, vanillaBack);
        if (back != null) {
            setBounds(state.backButton, back.x, back.y, back.width, back.height);
        } else {
            setBounds(state.backButton, Math.max(margin, state.width / 2 + 106),
                    Math.max(6, state.height - 28), 100, 20);
        }

        if (state.autoJoinButton != null) {
            int autoW = 130;
            int autoX = Math.max(margin, state.width - margin - autoW);
            setBounds(state.autoJoinButton, autoX, categoriesY, autoW, toolH);
        }
    }

    private static void setBounds(Object widget, int x, int y, int width, int height) {
        if (widget == null) return;
        RuntimeAccess.invoke(widget, "setX", x);
        RuntimeAccess.invoke(widget, "setY", y);
        RuntimeAccess.invoke(widget, "setWidth", width);
        RuntimeAccess.invoke(widget, "setHeight", height);

        setIntField(widget, "x", x);
        setIntField(widget, "y", y);
        setIntField(widget, "width", width);
        setIntField(widget, "height", height);
    }

    private static void setIntField(Object target, String name, int value) {
        if (target == null) return;
        try {
            Field f = RuntimeAccess.findField(target.getClass(), name);
            if (f != null) f.setInt(target, value);
        } catch (Throwable ignored) {}
    }

    private static void updateButtons(State state) {
        int favourites = 0, verified = 0, scanned = 0;
        for (ServerListAccess.Saved server : state.saved) {
            if (server.favourite()) favourites++;
            else if (ServerCategoryStore.isScanned(server.endpoint())) scanned++;
            else verified++;
        }
        RuntimeAccess.setButtonText(state.favouritesButton, "Favourites (" + favourites + ")");
        RuntimeAccess.setButtonText(state.serversButton, "Servers (" + verified + ")");
        RuntimeAccess.setButtonText(state.scannedButton, "Scanned Servers (" + scanned + ")");

        boolean scannedView = state.view == View.SCANNED;
        setActive(state.autoJoinButton, scannedView && (scanned > 0 || coreAutoJoinEnabled()));
        RuntimeAccess.setButtonText(state.autoJoinButton, autoJoinLabel());
    }

    private static void toggleScannedAutoJoin(State state) {
        if (state.view != View.SCANNED) return;
        if (coreAutoJoinEnabled()) {
            stopCoreAutoJoin(true);
        } else {
            long eligible = state.saved.stream().filter(s -> !s.favourite() && ServerCategoryStore.isScanned(s.endpoint())).count();
            if (eligible <= 0) return;
            RuntimeAccess.invokeStatic(CORE_AUTO_JOIN, "resetRuntimeState");
            setStaticBoolean(CORE_AUTO_JOIN, "enabled", true);
            RuntimeAccess.invokeStatic(CORE_AUTO_JOIN, "saveEnabled", true);
            seedCoreAutoJoinExclusions(state.saved);
            System.out.println("[Zazu's Server Tool] Auto Join started for Scanned Servers only (" + eligible + " eligible).");
        }
        RuntimeAccess.setButtonText(state.autoJoinButton, autoJoinLabel());
    }

    @SuppressWarnings("unchecked")
    private static void seedCoreAutoJoinExclusions(List<ServerListAccess.Saved> saved) {
        Object attemptedObject = RuntimeAccess.staticField(CORE_AUTO_JOIN, "ATTEMPTED");
        if (!(attemptedObject instanceof Set<?> raw)) return;
        Set<Object> attempted = (Set<Object>) raw;
        for (ServerListAccess.Saved server : saved) {
            if (server.favourite() || !ServerCategoryStore.isScanned(server.endpoint())) {
                attempted.add(ServerListAccess.normalize(server.endpoint()));
            }
        }
    }

    private static boolean coreAutoJoinEnabled() {
        return RuntimeAccess.staticBoolean(CORE_AUTO_JOIN, "enabled", false);
    }

    private static void stopCoreAutoJoin(boolean log) {
        setStaticBoolean(CORE_AUTO_JOIN, "enabled", false);
        RuntimeAccess.invokeStatic(CORE_AUTO_JOIN, "saveEnabled", false);
        RuntimeAccess.invokeStatic(CORE_AUTO_JOIN, "resetRuntimeState");
        if (log) System.out.println("[Zazu's Server Tool] Auto Join stopped; it is available only in Scanned Servers.");
    }

    private static String autoJoinLabel() {
        return "Auto Join: " + (coreAutoJoinEnabled() ? "ON" : "OFF");
    }

    private static Object findBaseWidget(State state, String label) {
        for (Object widget : state.baseWidgets) if (widgetLabel(widget).trim().equals(label)) return widget;
        return null;
    }

    private static Object findFirstBaseWidget(State state, Collection<String> labels) {
        for (String label : labels) {
            Object value = findBaseWidget(state, label);
            if (value != null) return value;
        }
        return null;
    }

    private static int widgetInt(Object widget, String getter, String field, int fallback) {
        if (widget == null) return fallback;
        Object value = RuntimeAccess.invoke(widget, getter);
        if (value instanceof Number n) return n.intValue();
        value = RuntimeAccess.field(widget, field);
        return value instanceof Number n ? n.intValue() : fallback;
    }

    private static void leaveMultiplayer(State state) {
        Object parent = RuntimeAccess.field(state.screen, "lastScreen");
        if (parent == null) parent = RuntimeAccess.field(state.screen, "parent");
        if (parent == null) parent = RuntimeAccess.field(state.screen, "previousScreen");
        if (parent != null) {
            try {
                ScreenCompat.setScreen(state.client, parent);
                return;
            } catch (Throwable ignored) {}
        }
        for (String label : List.of("Cancel", "Back", "Done")) {
            for (Object widget : state.baseWidgets) {
                if (widgetLabel(widget).trim().equals(label) && invokeNoArg(widget, "onPress")) return;
            }
        }
    }

    private static boolean invokeNoArg(Object target, String name) {
        if (target == null) return false;
        Method m = RuntimeAccess.findMethod(target.getClass(), name, 0);
        if (m == null) return false;
        try {
            m.invoke(target);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean isServerListView(Object screen) {
        State state = STATES.get(screen);
        return state == null || state.view != View.HUB;
    }

    private static boolean finderOpen(Object screen) {
        Object states = RuntimeAccess.staticField("dev.zazuzin.zst.ServerFinderClient", "STATES");
        if (!(states instanceof Map<?, ?> map)) return false;
        Object finderState = map.get(screen);
        return finderState != null && Boolean.TRUE.equals(RuntimeAccess.field(finderState, "open"));
    }

    private static boolean isCustom(State state, Object widget) {
        return widget == state.favouritesButton || widget == state.serversButton || widget == state.scannedButton
                || widget == state.backButton || widget == state.categoriesButton || widget == state.autoJoinButton;
    }

    private static ServerCategoryStore.Tab tabForView(View view) {
        return switch (view) {
            case FAVOURITES -> ServerCategoryStore.Tab.FAVOURITES;
            case SERVERS -> ServerCategoryStore.Tab.SERVERS;
            case SCANNED -> ServerCategoryStore.Tab.SCANNED;
            case HUB -> throw new IllegalStateException("Hub has no server category");
        };
    }

    private static Set<Object> identitySet(Collection<Object> values) {
        Set<Object> set = Collections.newSetFromMap(new IdentityHashMap<>());
        set.addAll(values);
        return set;
    }

    private static boolean containsUnknownIdentity(Collection<Object> current, Collection<Object> known) {
        Set<Object> set = identitySet(known);
        for (Object value : current) if (!set.contains(value)) return true;
        return false;
    }

    private static boolean sameIdentitySequence(List<Object> a, List<Object> b) {
        if (a == b) return true;
        if (a == null || b == null || a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (a.get(i) != b.get(i)) return false;
        return true;
    }

    private static void captureConnectAttempt(Object connectScreen) {
        String core = RuntimeAccess.staticString(CORE_AUTO_JOIN, "lastAutoJoinEndpoint");
        boolean coreRunning = RuntimeAccess.staticBoolean(CORE_AUTO_JOIN, "joinInProgress", false);
        long now = System.currentTimeMillis();
        if (coreRunning && !core.isBlank()) {
            activeAttemptEndpoint = core;
            activeAttemptAt = now;
            return;
        }

        Object parent = RuntimeAccess.field(connectScreen, "parent");
        if (parent == null) parent = RuntimeAccess.field(connectScreen, "lastScreen");
        if (parent == null) parent = RuntimeAccess.field(connectScreen, "previousScreen");
        Object cursor = parent;
        for (int i = 0; i < 4 && cursor != null; i++) {
            if (RuntimeAccess.isScreen(cursor, "JoinMultiplayerScreen")) {
                String selected = ServerListAccess.selectedEndpoint(cursor);
                if (!selected.isBlank()) {
                    activeAttemptEndpoint = selected;
                    activeAttemptAt = now;
                    return;
                }
                break;
            }
            Object next = RuntimeAccess.field(cursor, "parent");
            if (next == null) next = RuntimeAccess.field(cursor, "lastScreen");
            if (next == cursor) break;
            cursor = next;
        }

        if (!lastSelectedEndpoint.isBlank() && now - lastSelectedAt <= ATTEMPT_CONTEXT_MS) {
            activeAttemptEndpoint = lastSelectedEndpoint;
            activeAttemptAt = now;
        }
    }

    private static void onPlayJoin() {
        String endpoint = activeAttemptEndpoint;
        if (endpoint.isBlank()) {
            String core = RuntimeAccess.staticString(CORE_AUTO_JOIN, "lastAutoJoinEndpoint");
            if (!core.isBlank()) endpoint = core;
        }
        if (endpoint.isBlank() || !ServerCategoryStore.isScanned(endpoint)) return;
        final String candidate = endpoint;
        final long generation = ++playGeneration;
        System.out.println("[Zazu's Server Tool] " + candidate + " entered PLAY; verifying for 8 seconds before promotion.");
        CompletableFuture.delayedExecutor(STABLE_JOIN_MS, TimeUnit.MILLISECONDS).execute(() -> {
            if (playGeneration != generation) return;
            if (ServerCategoryStore.promoteVerified(candidate)) {
                System.out.println("[Zazu's Server Tool] Stable connection verified; moved to Servers: " + candidate);
            }
        });
    }

    static int autoJoinEligibleTotal() {
        int count = 0;
        for (ServerListAccess.Saved s : lastKnownSaved) {
            if (!s.favourite() && ServerCategoryStore.isScanned(s.endpoint())) count++;
        }
        return count;
    }

    static int autoJoinAttemptedScannedCount() {
        Object attemptedObject = RuntimeAccess.staticField(CORE_AUTO_JOIN, "ATTEMPTED");
        if (!(attemptedObject instanceof Set<?> attempted)) return 0;
        int count = 0;
        for (ServerListAccess.Saved s : lastKnownSaved) {
            if (s.favourite() || !ServerCategoryStore.isScanned(s.endpoint())) continue;
            if (attempted.contains(ServerListAccess.normalize(s.endpoint()))) count++;
        }
        return count;
    }

    private static Object makeButton(String text, int x, int y, int width, int height, Consumer<Object> action) throws Exception {
        return Reflection.makeButton(text, x, y, width, height, action);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> widgets(Object screen) {
        try {
            Class<?> screens = Class.forName("net.fabricmc.fabric.api.client.screen.v1.Screens");
            for (Method m : screens.getMethods()) {
                if (Modifier.isStatic(m.getModifiers()) && m.getName().equals("getWidgets") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isInstance(screen)) {
                    Object value = m.invoke(null, screen);
                    if (value instanceof List<?> list) return (List<Object>) list;
                }
            }
        } catch (Throwable ignored) {}
        return List.of();
    }

    private static String widgetLabel(Object widget) {
        Object message = RuntimeAccess.invoke(widget, "getMessage");
        if (message == null) message = RuntimeAccess.invoke(widget, "getText");
        return RuntimeAccess.componentText(message);
    }

    private static void setActive(Object widget, boolean value) { setFieldBoolean(widget, "active", value); }

    private static void setFieldBoolean(Object target, String field, boolean value) {
        if (target == null) return;
        try {
            Field f = RuntimeAccess.findField(target.getClass(), field);
            if (f != null) f.setBoolean(target, value);
        } catch (Throwable ignored) {}
    }

    private static void setStaticBoolean(String className, String field, boolean value) {
        try {
            Class<?> type = Class.forName(className);
            Field f = RuntimeAccess.findField(type, field);
            if (f != null) f.setBoolean(null, value);
        } catch (Throwable ignored) {}
    }

    private static void logOnce(State state, String message, Throwable t) {
        if (state.loggedFailure) return;
        state.loggedFailure = true;
        System.err.println("[Zazu's Server Tool] " + message + ": " + root(t));
    }

    private static Throwable root(Throwable t) {
        Throwable current = t;
        while ((current instanceof InvocationTargetException || current instanceof ExceptionInInitializerError)
                && current.getCause() != null) current = current.getCause();
        return current;
    }

    private enum View { HUB, FAVOURITES, SERVERS, SCANNED }

    private record Bounds(int x, int y, int width, int height) {}

    private static final class State {
        final Object client, screen;
        final int width, height;
        final List<Object> baseWidgets = new ArrayList<>();
        final Map<Object, Bounds> originalBounds = new IdentityHashMap<>();
        Object listWidget;
        Object favouritesButton, serversButton, scannedButton, backButton, categoriesButton, autoJoinButton;
        final Map<List<Object>, List<Object>> backingLists = new IdentityHashMap<>();
        List<Object> fullEntries = new ArrayList<>();
        List<ServerListAccess.Saved> saved = List.of();
        String lastSignature = "";
        View view = View.HUB;
        View appliedView;
        boolean loggedFailure;

        State(Object client, Object screen, int width, int height) {
            this.client = client;
            this.screen = screen;
            this.width = width;
            this.height = height;
        }
    }
}
