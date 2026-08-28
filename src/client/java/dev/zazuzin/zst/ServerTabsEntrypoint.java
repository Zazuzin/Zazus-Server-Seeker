package dev.zazuzin.zst;

import net.fabricmc.api.ClientModInitializer;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * v0.3.41 Multiplayer workflow.
 *
 * Multiplayer first opens a lightweight category hub. The vanilla saved-server
 * list is only shown after choosing Favourites / Servers / Scanned Servers.
 * No server-list reload is performed by the hub or while changing categories.
 */
public final class ServerTabsEntrypoint implements ClientModInitializer {
    private static final String CORE_AUTO_JOIN = "dev.zazuzin.zst.AutoJoinEntrypoint";
    private static final long STABLE_JOIN_MS = 8_000L;
    private static final long ATTEMPT_CONTEXT_MS = 15_000L;
    private static final long SCANNED_HEALTH_INTERVAL_MS = 10_000L;
    private static final long SCANNED_HEALTH_INITIAL_DELAY_MS = 20_000L;
    private static final int SCANNED_HEALTH_BATCH = 8;
    private static final int SCANNED_FAILURES_BEFORE_DELETE = 3;

    private static final Map<Object, State> STATES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<String, Integer> SCANNED_HEALTH_FAILURES = new ConcurrentHashMap<>();
    private static final Map<Object, Object> PAUSE_FAVOURITE_BUTTONS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, Object> PAUSE_EDIT_BUTTONS = Collections.synchronizedMap(new WeakHashMap<>());
    /** Every button created by this entrypoint. Keeping weak identities lets us
     * hide/remove stale controls after JoinMultiplayerScreen re-initialises the
     * same screen object, preventing ghost Back/Refresh-era buttons. */
    private static final Set<Object> OWNED_WIDGETS = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private static volatile List<ServerListAccess.Saved> lastKnownSaved = List.of();
    private static volatile View pendingViewAfterRefresh;
    private static volatile long pendingViewAfterRefreshAt;
    private static volatile String lastSelectedEndpoint = "";
    private static volatile long lastSelectedAt;
    private static volatile String activeAttemptEndpoint = "";
    private static volatile long activeAttemptAt;
    private static volatile long playGeneration;
    private static volatile String connectedEndpoint = "";
    private static volatile View autoJoinView = loadAutoJoinView();

    @Override
    public void onInitializeClient() {
        ServerCategoryStore.load();
        AutoJoinDiagnostics.install();
        FinderLatencyOverlay.install();
        try {
            registerScreenWatcher();
            registerPlayWatchers();
            System.out.println("[Zazu's Server Tool] 0.3.41 Multiplayer category/options hub enabled.");
            System.out.println("[Zazu's Server Tool] 0.3.52 Scanned Servers health cleanup enabled (3 failed status checks).");
            System.out.println("[Zazu's Server Tool] 0.3.54 Recent Servers history enabled (last 5 stable joins).");
            System.out.println("[Zazu's Server Tool] 0.3.55 whitelist deletion now removes the live Servers-tab source before returning.");
            System.out.println("[Zazu's Server Tool] 0.3.56 pause-menu Favourite Server control enabled.");
            System.out.println("[Zazu's Server Tool] 0.3.57 Auto Join enabled for Servers and Scanned Servers; favourites remain excluded.");
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
                else if (isPauseMenu(screen)) onPauseMenuInit(client, screen, width, height);
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
                "net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents$Disconnect", ServerTabsEntrypoint::onPlayDisconnect);
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

    private static boolean isPauseMenu(Object screen) {
        return RuntimeAccess.isScreen(screen, "PauseScreen") || RuntimeAccess.isScreen(screen, "GameMenuScreen");
    }

    private static void onPauseMenuInit(Object client, Object screen, int width, int height) {
        try {
            Object previous = PAUSE_FAVOURITE_BUTTONS.remove(screen);
            if (previous != null) Reflection.removeWidget(screen, previous);
            Object previousEdit = PAUSE_EDIT_BUTTONS.remove(screen);
            if (previousEdit != null) Reflection.removeWidget(screen, previousEdit);

            String endpoint = currentConnectedEndpoint(client);
            if (endpoint.isBlank()) return;

            String displayName = currentConnectedServerName(client);
            boolean favourite = MultiplayerManagementEntrypoint.isFavouriteEndpoint(client, endpoint);
            Object[] holder = new Object[1];
            Object button = makeButton(pauseFavouriteLabel(favourite), 6, 6, 160, 20, ignored -> {
                try {
                    boolean nowFavourite = MultiplayerManagementEntrypoint.toggleFavouriteEndpoint(client, endpoint, displayName);
                    Reflection.setButtonText(holder[0], pauseFavouriteLabel(nowFavourite));
                    System.out.println("[Zazu's Server Tool] Pause-menu favourite "
                            + (nowFavourite ? "enabled for " : "removed from ") + endpoint);
                } catch (Throwable t) {
                    System.err.println("[Zazu's Server Tool] Pause-menu favourite toggle failed: " + root(t));
                }
            });
            holder[0] = button;
            PAUSE_FAVOURITE_BUTTONS.put(screen, button);
            Reflection.addWidget(screen, button);

            Object edit = makeButton("Edit Server Info", 6, 30, 160, 20,
                    ignored -> openPauseServerEditor(client, screen, endpoint, displayName));
            PAUSE_EDIT_BUTTONS.put(screen, edit);
            Reflection.addWidget(screen, edit);
        } catch (Throwable t) {
            System.err.println("[Zazu's Server Tool] Could not add pause-menu Favourite Server button: " + root(t));
        }
    }

    private static String pauseFavouriteLabel(boolean favourite) {
        return favourite ? "★ Unfavourite Server" : "☆ Favourite Server";
    }

    private static void openPauseServerEditor(Object client, Object pauseScreen, String endpoint, String suggestedName) {
        try {
            Object list = ServerFinderClient.ServerListBridge.createLoadedList(client);
            Object server = ServerFinderClient.ServerListBridge.findServer(list, endpoint);
            if (server == null) {
                String name = suggestedName == null || suggestedName.isBlank() ? "Zazu " + endpoint : suggestedName;
                server = ServerFinderClient.ServerListBridge.createServerData(name, endpoint);
                ServerFinderClient.ServerListBridge.addServerData(list, server);
                ServerFinderClient.ServerListBridge.save(list);
                ServerCategoryStore.syncNew(List.of(endpoint));
            }

            final Object editedServer = server;
            final String originalEndpoint = ToolState.normalize(endpoint);
            final boolean favourite = MultiplayerManagementEntrypoint.isFavouriteEndpoint(client, originalEndpoint);
            Class<?> editType = pauseServerEditorType();
            Object editScreen = null;
            for (Constructor<?> constructor : editType.getDeclaredConstructors()) {
                Class<?>[] types = constructor.getParameterTypes();
                if (types.length != 3 || !types[0].isInstance(pauseScreen) || !types[2].isInstance(editedServer)
                        || !types[1].isInterface()) continue;
                Object callback = Proxy.newProxyInstance(types[1].getClassLoader(), new Class<?>[]{types[1]}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) return RuntimeAccess.objectMethod(proxy, method, args);
                    boolean accepted = args != null && args.length > 0 && Boolean.TRUE.equals(args[0]);
                    if (accepted) {
                        String updatedEndpoint = ToolState.normalize(
                                ServerFinderClient.ServerListBridge.serverEndpoint(editedServer));
                        if (updatedEndpoint.isBlank()) {
                            updatedEndpoint = originalEndpoint;
                            ServerFinderClient.ServerListBridge.setServerEndpoint(editedServer, originalEndpoint);
                        }
                        String name = ServerFinderClient.ServerListBridge.serverName(editedServer);
                        if (favourite && !name.startsWith(MultiplayerManagementEntrypoint.FAV_PREFIX))
                            ServerFinderClient.ServerListBridge.setServerName(editedServer, MultiplayerManagementEntrypoint.FAV_PREFIX + name);
                        ServerFinderClient.ServerListBridge.save(list);
                        ServerCategoryStore.moveEndpoint(originalEndpoint, updatedEndpoint);
                        ServerCategoryStore.setFavourite(updatedEndpoint, favourite);
                        System.out.println("[Zazu's Server Tool] Updated connected server info: "
                                + originalEndpoint + " -> " + updatedEndpoint);
                    }
                    ScreenCompat.setScreen(client, pauseScreen);
                    return null;
                });
                try {
                    constructor.setAccessible(true);
                    editScreen = constructor.newInstance(pauseScreen, callback, editedServer);
                    break;
                } catch (Throwable ignored) {}
            }
            if (editScreen == null) throw new IllegalStateException("Minecraft EditServerScreen constructor was not compatible.");
            ScreenCompat.setScreen(client, editScreen);
        } catch (Throwable t) {
            System.err.println("[Zazu's Server Tool] Could not open pause-menu server editor: " + root(t));
        }
    }

    private static Class<?> pauseServerEditorType() throws ClassNotFoundException {
        ClassNotFoundException failure = null;
        for (String name : new String[]{
                "net.minecraft.client.gui.screens.EditServerScreen",
                "net.minecraft.client.gui.screens.multiplayer.EditServerScreen"
        }) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException missing) {
                failure = missing;
            }
        }
        throw failure == null ? new ClassNotFoundException("EditServerScreen") : failure;
    }

    static String currentConnectedEndpoint(Object client) {
        if (!connectedEndpoint.isBlank()) return connectedEndpoint;
        Object data = currentServerData(client);
        if (data == null) return "";
        try {
            String endpoint = ServerFinderClient.ServerListBridge.serverEndpoint(data);
            if (!endpoint.isBlank()) connectedEndpoint = endpoint;
            return endpoint;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String currentConnectedServerName(Object client) {
        Object data = currentServerData(client);
        if (data == null) return "";
        try { return ServerFinderClient.ServerListBridge.serverName(data); }
        catch (Throwable ignored) { return ""; }
    }

    private static Object currentServerData(Object client) {
        if (client == null) client = RuntimeAccess.minecraftInstance();
        if (client == null) return null;
        for (String method : List.of("getCurrentServer", "getCurrentServerEntry", "getCurrentServerData", "getServerData")) {
            Object value = RuntimeAccess.invoke(client, method);
            if (value != null) {
                try {
                    String endpoint = ServerFinderClient.ServerListBridge.serverEndpoint(value);
                    if (!endpoint.isBlank()) return value;
                } catch (Throwable ignored) {}
            }
        }
        for (String field : List.of("currentServer", "currentServerEntry", "currentServerData", "serverData")) {
            Object value = RuntimeAccess.field(client, field);
            if (value != null) {
                try {
                    String endpoint = ServerFinderClient.ServerListBridge.serverEndpoint(value);
                    if (!endpoint.isBlank()) return value;
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private static void onMultiplayerInit(Object client, Object screen, int width, int height) throws Exception {
        // JoinMultiplayerScreen can be re-initialised without changing object identity
        // (refresh, resize, returning from a child screen). Remove the previous
        // enhancement controls first so they are never mistaken for vanilla/core
        // widgets by the new State.
        State previous = STATES.get(screen);
        View previousView = previous == null ? View.HUB : previous.view;
        View requestedView = consumePendingViewAfterRefresh();
        if (previous != null) detachCustomWidgets(previous);
        purgeStaleOwnedWidgets(screen);

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
        registerControlMouseInterceptor(state);

        // While Auto Join is returning after a failure, resume the category in
        // which the pass began instead of interrupting it with the hub.
        if (coreAutoJoinEnabled()) {
            seedCoreAutoJoinExclusions(state.saved);
            showCategory(state, tabForView(autoJoinView));
        } else if (requestedView != null && requestedView != View.HUB) {
            showCategory(state, tabForView(requestedView));
        } else if (previousView != View.HUB) {
            showCategory(state, tabForView(previousView));
        } else {
            showHub(state);
        }

        registerAfterTick(state);
        System.out.println("[Zazu's Server Tool] Multiplayer category hub installed on "
                + screen.getClass().getName() + " at " + width + "x" + height + ".");
    }

    static void preserveCurrentViewAfterRefresh(Object screen) {
        long age = System.currentTimeMillis() - pendingViewAfterRefreshAt;
        if (pendingViewAfterRefresh != null && age >= 0L && age <= 5_000L) return;
        State state = STATES.get(screen);
        if (state == null || state.view == View.HUB) return;
        requestViewAfterRefresh(state.view);
    }

    static void requestViewAfterRefresh(Object screen, ServerCategoryStore.Tab tab) {
        if (tab == null) { preserveCurrentViewAfterRefresh(screen); return; }
        requestViewAfterRefresh(switch (tab) {
            case FAVOURITES -> View.FAVOURITES;
            case SERVERS -> View.SERVERS;
            case SCANNED -> View.SCANNED;
            case RECENT -> View.RECENT;
        });
    }

    private static void requestViewAfterRefresh(View view) {
        if (view == null || view == View.HUB) return;
        pendingViewAfterRefresh = view;
        pendingViewAfterRefreshAt = System.currentTimeMillis();
    }

    private static View consumePendingViewAfterRefresh() {
        View view = pendingViewAfterRefresh;
        long age = System.currentTimeMillis() - pendingViewAfterRefreshAt;
        pendingViewAfterRefresh = null;
        pendingViewAfterRefreshAt = 0L;
        return view != null && age >= 0L && age <= 5_000L ? view : null;
    }

    /**
     * Creates only Zazu-owned controls. Minecraft's native Back and Refresh
     * buttons are left completely untouched and keep their original callbacks,
     * positions and lifecycle.
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
        state.recentButton = makeButton("Recent Servers", x, startY + gap * 3, buttonWidth, 20,
                b -> showCategory(state, ServerCategoryStore.Tab.RECENT));

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

        rememberOwned(state.favouritesButton, state.serversButton, state.scannedButton, state.recentButton,
                state.categoriesButton);
        Reflection.addWidget(state.screen, state.favouritesButton);
        Reflection.addWidget(state.screen, state.serversButton);
        Reflection.addWidget(state.screen, state.scannedButton);
        Reflection.addWidget(state.screen, state.recentButton);
        Reflection.addWidget(state.screen, state.categoriesButton);
    }

    private static void registerControlMouseInterceptor(State state) throws Exception {
        Class<?> holder = Class.forName("net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents");
        Method factory = null;
        for (Method method : holder.getMethods()) {
            if (Modifier.isStatic(method.getModifiers()) && method.getName().equals("allowMouseClick")
                    && method.getParameterCount() == 1 && method.getParameterTypes()[0].isInstance(state.screen)) {
                factory = method;
                break;
            }
        }
        if (factory == null) throw new NoSuchMethodException("ScreenMouseEvents.allowMouseClick(Screen)");
        Object event = factory.invoke(null, state.screen);
        Class<?> callback = Class.forName("net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents$AllowMouseClick");
        Object listener = Proxy.newProxyInstance(callback.getClassLoader(), new Class<?>[]{callback}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return RuntimeAccess.objectMethod(proxy, method, args);
            if (STATES.get(state.screen) != state) return Boolean.TRUE;

            // The category hub is made from normal Minecraft Button widgets. Do
            // not intercept it at all: letting Screen process those widgets is the
            // most reliable path and matches the last known-good hub behaviour.
            if (state.view == View.HUB) return Boolean.TRUE;

            Object mouse = args == null || args.length == 0 ? null : args[args.length - 1];
            if (mouse == null || mouseButton(mouse) != 0) return Boolean.TRUE;
            double x = mouseCoordinate(mouse, "x"), y = mouseCoordinate(mouse, "y");

            // Only the dedicated left rail needs interception because it overlaps
            // the full-width server-list hitbox. Dispatch the real Minecraft
            // mouseClicked(MouseButtonEvent, boolean) method first. Crucially, we
            // cancel vanilla processing only when the button actually consumed the
            // click; a failed reflective dispatch must never make a button dead.
            for (Object widget : Arrays.asList(state.autoJoinButton, state.categoriesButton,
                    MultiplayerManagementEntrypoint.finderButton(state.screen))) {
                if (visibleActiveContains(widget, x, y) && dispatchWidgetClick(widget, mouse)) {
                    return Boolean.FALSE;
                }
            }

            // Keep Minecraft's real Refresh button. Before vanilla handles the
            // click, remember the active category so its screen re-init returns
            // to this same list instead of the category hub.
            for (Object widget : Reflection.screenListElements(state.screen)) {
                if (!visibleActiveContains(widget, x, y)) continue;
                if (widgetLabel(widget).trim().equals("Refresh")) {
                    requestViewAfterRefresh(state.view);
                    return Boolean.TRUE;
                }
            }

            // Capture the selected endpoint before vanilla Join Server changes
            // screens so whitelist cleanup always knows which saved row to delete.
            for (Object widget : Reflection.screenListElements(state.screen)) {
                if (!visibleActiveContains(widget, x, y)) continue;
                if (widgetLabel(widget).trim().equals("Join Server")) {
                    String endpoint = ServerListAccess.selectedEndpoint(state.screen);
                    if (!endpoint.isBlank()) WhitelistAutoDeleteEntrypoint.noteAttempt(endpoint);
                    break;
                }
            }
            return Boolean.TRUE;
        });
        RuntimeAccess.registerEvent(event, listener);
    }

    private static double mouseCoordinate(Object event, String axis) {
        Object value = RuntimeAccess.invoke(event, axis);
        if (!(value instanceof Number)) value = RuntimeAccess.field(event, axis);
        return value instanceof Number n ? n.doubleValue() : Double.NaN;
    }

    private static int mouseButton(Object event) {
        Object value = RuntimeAccess.invoke(event, "button");
        if (!(value instanceof Number)) value = RuntimeAccess.field(event, "button");
        return value instanceof Number n ? n.intValue() : -1;
    }

    static boolean dispatchWidgetClick(Object widget, Object mouseEvent) {
        if (widget == null || mouseEvent == null) return false;

        // Minecraft 26.2 routes widget presses through
        // mouseClicked(MouseButtonEvent, boolean). Calling that path preserves the
        // widget's normal active/visible checks and invokes its configured callback.
        Object consumed = RuntimeAccess.invoke(widget, "mouseClicked", mouseEvent, false);
        if (consumed instanceof Boolean b) return b;

        // Compatibility fallback for button implementations that still expose a
        // direct zero-argument onPress method. Only report success when invocation
        // really occurred, so the AllowMouseClick hook never swallows a dead click.
        return invokeNoArg(widget, "onPress");
    }

    private static boolean visibleActiveContains(Object widget, double x, double y) {
        if (widget == null || Double.isNaN(x) || Double.isNaN(y)) return false;
        Object visible = RuntimeAccess.field(widget, "visible");
        Object active = RuntimeAccess.field(widget, "active");
        if (visible instanceof Boolean b && !b) return false;
        if (active instanceof Boolean b && !b) return false;
        int wx = widgetInt(widget, "getX", "x", Integer.MIN_VALUE);
        int wy = widgetInt(widget, "getY", "y", Integer.MIN_VALUE);
        int ww = widgetInt(widget, "getWidth", "width", 0);
        int wh = widgetInt(widget, "getHeight", "height", 0);
        return wx != Integer.MIN_VALUE && wy != Integer.MIN_VALUE && x >= wx && x < wx + ww && y >= wy && y < wy + wh;
    }

    static void reapplyCurrentView(Object screen) {
        State state = STATES.get(screen);
        if (state == null) return;
        if (state.view == View.HUB) {
            showHub(state);
        } else {
            showCategory(state, tabForView(state.view));
        }
    }

    private static void showHub(State state) {
        if (state == null) return;
        state.scannedHealthGeneration++;
        state.scannedHealthProbeInFlight = false;
        state.view = View.HUB;
        restoreFullRows(state);
        refreshCachedSaved(state, false);
        applyLayout(state);
        updateButtons(state);
    }

    private static void showCategory(State state, ServerCategoryStore.Tab tab) {
        if (state == null || tab == null) return;
        state.scannedHealthGeneration++;
        state.scannedHealthProbeInFlight = false;
        if (tab == ServerCategoryStore.Tab.SCANNED)
            state.nextScannedHealthProbeAt = System.currentTimeMillis() + SCANNED_HEALTH_INITIAL_DELAY_MS;
        View requested = switch (tab) {
            case FAVOURITES -> View.FAVOURITES;
            case SERVERS -> View.SERVERS;
            case SCANNED -> View.SCANNED;
            case RECENT -> View.RECENT;
        };
        if (coreAutoJoinEnabled() && requested != autoJoinView) stopCoreAutoJoin(true);
        state.view = requested;
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
        if (state == null || STATES.get(state.screen) != state) return;
        Object client = RuntimeAccess.minecraftInstance();
        Object current = ScreenCompat.currentScreen(client);
        if (current != null && current != state.screen) return;

        if (finderOpen(state.screen)) {
            setVisibleActive(state.favouritesButton, false, false);
            setVisibleActive(state.serversButton, false, false);
            setVisibleActive(state.scannedButton, false, false);
            setVisibleActive(state.recentButton, false, false);
            setVisibleActive(state.categoriesButton, false, false);
            setVisibleActive(state.autoJoinButton, false, false);
            return;
        }

        refreshCachedSaved(state, true);
        tickScannedHealthCleanup(state);

        if (state.view != View.HUB) {
            String selected = ServerListAccess.selectedEndpoint(state.screen);
            if (!selected.isBlank()) {
                lastSelectedEndpoint = selected;
                lastSelectedAt = System.currentTimeMillis();
            }
        }

        if (coreAutoJoinEnabled()) {
            if (state.view != autoJoinView) stopCoreAutoJoin(true);
            else seedCoreAutoJoinExclusions(state.saved);
        }

        applyLayout(state);
        updateButtons(state);
    }

    /**
     * Existing Scanned Servers are rechecked in small batches while that tab is
     * open. A non-favourite scanned entry is removed only after three
     * consecutive failed direct Minecraft status handshakes. Any successful
     * reply immediately resets its failure streak.
     */
    private static void tickScannedHealthCleanup(State state) {
        if (state == null || state.view != View.SCANNED || coreAutoJoinEnabled()) return;
        if (state.scannedHealthProbeInFlight) return;

        long now = System.currentTimeMillis();
        if (now < state.nextScannedHealthProbeAt) return;

        List<String> eligible = state.saved.stream()
                .filter(saved -> !saved.favourite() && ServerCategoryStore.isScanned(saved.endpoint()))
                .map(ServerListAccess.Saved::endpoint)
                .filter(endpoint -> endpoint != null && !endpoint.isBlank())
                .distinct()
                .toList();

        if (eligible.isEmpty()) {
            state.nextScannedHealthProbeAt = now + SCANNED_HEALTH_INTERVAL_MS;
            state.scannedHealthCursor = 0;
            return;
        }

        int start = Math.floorMod(state.scannedHealthCursor, eligible.size());
        int count = Math.min(SCANNED_HEALTH_BATCH, eligible.size());
        ArrayList<String> batch = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String endpoint = eligible.get((start + i) % eligible.size());
            if (VanillaStatusProbe.cachedLatencyMillis(endpoint) >= 0L) {
                SCANNED_HEALTH_FAILURES.remove(ServerListAccess.normalize(endpoint));
                continue;
            }
            batch.add(endpoint);
        }
        state.scannedHealthCursor = (start + count) % eligible.size();
        if (batch.isEmpty()) {
            state.nextScannedHealthProbeAt = now + SCANNED_HEALTH_INTERVAL_MS;
            return;
        }
        state.scannedHealthProbeInFlight = true;

        final long generation = state.scannedHealthGeneration;
        VanillaStatusProbe.probe(state.client, state.screen, batch, () ->
                STATES.get(state.screen) == state
                        && state.view == View.SCANNED
                        && state.scannedHealthGeneration == generation
        ).whenComplete((results, error) -> Reflection.execute(state.client, () -> {
            if (STATES.get(state.screen) != state || state.scannedHealthGeneration != generation) return;
            state.scannedHealthProbeInFlight = false;
            state.nextScannedHealthProbeAt = System.currentTimeMillis() + SCANNED_HEALTH_INTERVAL_MS;

            Map<String, VanillaStatusProbe.Result> byEndpoint = new HashMap<>();
            if (results != null) {
                for (VanillaStatusProbe.Result result : results) {
                    if (result != null) byEndpoint.put(ServerListAccess.normalize(result.endpoint()), result);
                }
            }

            int deleted = 0;
            for (String endpoint : batch) {
                String key = ServerListAccess.normalize(endpoint);
                VanillaStatusProbe.Result result = byEndpoint.get(key);

                if (result != null && result.replied()) {
                    ServerCategoryStore.recordHealthSuccess(endpoint);
                    Integer previous = SCANNED_HEALTH_FAILURES.remove(key);
                    if (previous != null && previous > 0) {
                        System.out.println("[Zazu's Server Tool] Scanned health recovered: " + endpoint
                                + " (failure streak reset from " + previous + ").");
                    }
                    continue;
                }

                // Internal/local probe errors (for example a saturated local
                // queue) are not evidence that the server is offline.
                if (result == null || result.failure() == VanillaStatusProbe.Failure.ERROR) continue;

                int failures = ServerCategoryStore.recordHealthFailure(endpoint);
                SCANNED_HEALTH_FAILURES.put(key, failures);
                System.out.println("[Zazu's Server Tool] Scanned health failed " + failures + "/"
                        + SCANNED_FAILURES_BEFORE_DELETE + ": " + endpoint
                        + " (" + result.failure() + ").");

                if (failures < SCANNED_FAILURES_BEFORE_DELETE) continue;
                if (!stillEligibleForScannedHealthDelete(state, endpoint)) {
                    SCANNED_HEALTH_FAILURES.remove(key);
                    continue;
                }

                if (ServerListAccess.isFavouriteEndpoint(state.client, state.screen, endpoint)) {
                    SCANNED_HEALTH_FAILURES.remove(key);
                    System.out.println("[Zazu's Server Tool] Kept favourite after failed scanned health checks: " + endpoint);
                    continue;
                }

                if (ServerListAccess.forceRemove(state.client, endpoint)) {
                    ServerCategoryStore.remove(endpoint);
                    SCANNED_HEALTH_FAILURES.remove(key);
                    deleted++;
                    System.out.println("[Zazu's Server Tool] Auto-deleted unreachable scanned server after "
                            + SCANNED_FAILURES_BEFORE_DELETE + " failed checks: " + endpoint);
                }
            }

            if (deleted > 0 && STATES.get(state.screen) == state) {
                requestViewAfterRefresh(state.screen, ServerCategoryStore.Tab.SCANNED);
                MultiplayerManagementEntrypoint.refreshMultiplayerScreen(state.client, state.screen);
            }
        }));
    }

    private static boolean stillEligibleForScannedHealthDelete(State state, String endpoint) {
        String target = ServerListAccess.normalize(endpoint);
        if (target.isBlank() || !ServerCategoryStore.isScanned(endpoint)) return false;
        for (ServerListAccess.Saved saved : ServerListAccess.savedFromScreen(state.screen)) {
            if (ServerListAccess.normalize(saved.endpoint()).equals(target)) {
                return !saved.favourite() && ServerCategoryStore.isScanned(saved.endpoint());
            }
        }
        return false;
    }

    private static void captureFullRows(State state, boolean migrate) {
        state.saved = ServerListAccess.savedFromScreen(state.screen);
        if (state.saved.isEmpty()) {
            state.saved = ServerListAccess.savedFromEntries(ServerListAccess.onlineEntries(state.screen));
        }
        List<String> endpoints = state.saved.stream().map(ServerListAccess.Saved::endpoint).toList();
        if (migrate) ServerCategoryStore.migrateExisting(endpoints);
        ServerCategoryStore.syncNew(endpoints);
        state.lastSignature = ServerListAccess.signature(state.saved);
        lastKnownSaved = List.copyOf(state.saved);
    }

    private static void refreshCachedSaved(State state, boolean detectChanges) {
        List<ServerListAccess.Saved> fresh = ServerListAccess.savedFromScreen(state.screen);
        if (fresh.isEmpty() && !state.saved.isEmpty()) fresh = state.saved;
        String signature = ServerListAccess.signature(fresh);
        boolean changed = !signature.equals(state.lastSignature);
        state.saved = List.copyOf(fresh);
        lastKnownSaved = state.saved;
        if (!detectChanges || changed) {
            state.lastSignature = signature;
            List<String> endpoints = state.saved.stream().map(ServerListAccess.Saved::endpoint).toList();
            ServerCategoryStore.syncNew(endpoints);
            if (detectChanges && state.view != View.HUB) {
                applyCategoryRows(state, tabForView(state.view));
                MultiplayerManagementEntrypoint.rebuildRowButtons(state.screen);
            }
        }
    }

    private static void restoreFullRows(State state) {
        try {
            ServerListAccess.applyCategory(state.client, state.screen, null);
            MultiplayerManagementEntrypoint.rebuildRowButtons(state.screen);
        } catch (Throwable t) {
            logOnce(state, "Could not restore full Multiplayer server list", t);
        }
    }

    private static void applyCategoryRows(State state, ServerCategoryStore.Tab tab) {
        try {
            ServerListAccess.applyCategory(state.client, state.screen, tab);
        } catch (Throwable t) {
            logOnce(state, "Could not rebuild filtered Multiplayer server list", t);
        }
    }

    /**
     * One authoritative layout path. A control that is not part of the current
     * view is removed from the active Fabric widget list rather than hidden or
     * moved off-screen. Minecraft's own controls keep their original bounds.
     */
    private static void applyLayout(State state) {
        if (state == null || finderOpen(state.screen)) return;

        List<Object> live = widgets(state.screen);
        boolean learnedWidgets = live != null && learnBaseWidgets(state, live);
        captureMissingOriginalBounds(state);
        boolean layoutNeeded = learnedWidgets || state.appliedView != state.view;

        Object tool = MultiplayerManagementEntrypoint.finderButton(state.screen);
        Object deleteNonFavourites = MultiplayerManagementEntrypoint.deleteNonFavouritesButton(state.screen);
        Object undoLastDelete = MultiplayerManagementEntrypoint.undoLastDeleteButton(state.screen);
        List<Object> rowWidgets = MultiplayerManagementEntrypoint.rowWidgets(state.screen);

        boolean hub = state.view == View.HUB;
        for (Object widget : state.baseWidgets) {
            if (widget == null || isCustom(state, widget) || MultiplayerManagementEntrypoint.isManagedWidget(widget)) continue;
            boolean show = hub ? isNativeBackWidget(widget) : shouldUseBaseWidget(state, widget);
            setVisibleActive(widget, show, show);
        }
        if (state.listWidget != null) setVisibleActive(state.listWidget, !hub, !hub);

        setVisibleActive(state.favouritesButton, hub, hub);
        setVisibleActive(state.serversButton, hub, hub);
        setVisibleActive(state.scannedButton, hub, hub);
        setVisibleActive(state.recentButton, hub, hub);
        setVisibleActive(state.categoriesButton, !hub, !hub);
        setVisibleActive(tool, true, true);

        boolean serversView = state.view == View.SERVERS;
        boolean scannedView = state.view == View.SCANNED;
        MultiplayerManagementEntrypoint.configureBulkDelete(state.screen, scannedView);
        boolean bulkDeleteView = serversView || scannedView;
        setVisibleActive(deleteNonFavourites, bulkDeleteView, bulkDeleteView);
        setVisibleActive(undoLastDelete, bulkDeleteView, bulkDeleteView && ServerCategoryStore.hasUndo());
        if (hub) {
            for (Object row : rowWidgets) setVisibleActive(row, false, false);
        }

        if (isAutoJoinView(state.view)) {
            ensureAutoJoinButton(state);
            boolean eligible = autoJoinEligibleTotal() > 0 || coreAutoJoinEnabled();
            setVisibleActive(state.autoJoinButton, true, eligible);
        } else {
            setVisibleActive(state.autoJoinButton, false, false);
        }

        // Only Zazu-owned navigation widgets are cleaned up here. Minecraft's
        // native footer, including Back and Refresh, is never removed or moved.
        purgeStaleOwnedWidgetsExceptCurrent(state);

        if (layoutNeeded) {
            if (hub) layoutHub(state, tool);
            else layoutCategoryControls(state);
        }
        state.appliedView = state.view;
    }

    private static void setVisibleActive(Object widget, boolean visible, boolean active) {
        if (widget == null) return;
        setFieldBoolean(widget, "visible", visible);
        setFieldBoolean(widget, "active", active);
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
        if (label.equals("Delete Non-Favourites") || label.equals("Confirm Delete Non-Favs")
                || label.equals("Delete All Scanned") || label.equals("Confirm Delete Scanned")) {
            return state.view == View.SERVERS || state.view == View.SCANNED;
        }
        return true;
    }

    private static boolean isNativeBackWidget(Object widget) {
        String label = widgetLabel(widget).trim();
        return label.equals("Back") || label.equals("Cancel") || label.equals("Done");
    }

    private static void ensureAutoJoinButton(State state) {
        if (state.autoJoinButton != null) return;
        try {
            state.autoJoinButton = makeButton(autoJoinLabel(), 0, 0, 130, 20,
                    b -> toggleCategoryAutoJoin(state));
            rememberOwned(state.autoJoinButton);
            Reflection.addWidget(state.screen, state.autoJoinButton);
        } catch (Throwable t) {
            logOnce(state, "Could not create category Auto Join control", t);
        }
    }

    private static void detachCustomWidgets(State state) {
        if (state == null) return;
        for (Object widget : Arrays.asList(
                state.favouritesButton, state.serversButton, state.scannedButton, state.recentButton,
                state.categoriesButton, state.autoJoinButton)) {
            setVisibleActive(widget, false, false);
            Reflection.removeWidget(state.screen, widget);
        }
    }

    private static void rememberOwned(Object... widgets) {
        if (widgets == null) return;
        for (Object widget : widgets) if (widget != null) OWNED_WIDGETS.add(widget);
    }

    private static void purgeStaleOwnedWidgets(Object screen) {
        if (screen == null) return;
        for (Object widget : new ArrayList<>(OWNED_WIDGETS)) {
            if (widget == null) continue;
            setVisibleActive(widget, false, false);
            Reflection.removeWidget(screen, widget);
        }
    }

    private static void purgeStaleOwnedWidgetsExceptCurrent(State state) {
        if (state == null) return;
        for (Object widget : new ArrayList<>(OWNED_WIDGETS)) {
            if (widget == null || isCustom(state, widget)) continue;
            setVisibleActive(widget, false, false);
            Reflection.removeWidget(state.screen, widget);
        }
    }

    private static boolean containsIdentity(Collection<Object> values, Object target) {
        if (values == null || target == null) return false;
        for (Object value : values) if (value == target) return true;
        return false;
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
        setBounds(state.recentButton, x, startY + gap * 3, buttonWidth, 20);
        if (tool != null) setBounds(tool, x, startY + gap * 4, buttonWidth, 20);
    }

    private static void layoutCategoryControls(State state) {
        int margin = 6;
        Object tool = MultiplayerManagementEntrypoint.finderButton(state.screen);

        // Keep Zazu-specific navigation in a dedicated left-side rail beside the
        // centred vanilla server rows. The old bottom-left placement occupied the
        // same Y coordinates as Join/Edit/Delete/Refresh/Back and was the source
        // of the overlapping buttons visible in 0.3.34/0.3.35.
        int listTop = widgetInt(state.listWidget, "getY", "y", 32);
        int listHeight = widgetInt(state.listWidget, "getHeight", "height", Math.max(120, state.height - 96));
        int listBottom = Math.min(state.height - 36, listTop + Math.max(80, listHeight));
        int rowWidth = state.listWidget == null ? 308 : widgetInt(state.listWidget, "getRowWidth", "rowWidth", 308);
        if (rowWidth <= 0 || rowWidth > state.width) rowWidth = Math.min(308, Math.max(120, state.width - 24));
        int rowLeft = Math.max(margin, (state.width - rowWidth) / 2);
        int availableLeft = Math.max(0, rowLeft - margin - 8);
        int railW = Math.min(170, Math.max(96, availableLeft));
        int railX = margin;
        if (availableLeft < 96) railW = Math.min(140, Math.max(96, state.width / 4));

        int toolH = 20;
        int finderY = Math.max(listTop + 48, listBottom - toolH);
        int categoriesY = Math.max(listTop + 24, finderY - toolH - 4);
        if (tool != null) setBounds(tool, railX, finderY, railW, toolH);
        setBounds(state.categoriesButton, railX, categoriesY, railW, toolH);

        if (state.autoJoinButton != null) {
            int autoY = Math.max(listTop, categoriesY - toolH - 4);
            setBounds(state.autoJoinButton, railX, autoY, railW, toolH);
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
        int favourites = 0, verified = 0, scanned = 0, recent = 0;
        for (ServerListAccess.Saved server : state.saved) {
            if (server.favourite()) favourites++;
            else if (ServerCategoryStore.isScanned(server.endpoint())) scanned++;
            else verified++;
            if (ServerCategoryStore.isRecent(server.endpoint())) recent++;
        }
        RuntimeAccess.setButtonText(state.favouritesButton, "Favourites (" + favourites + ")");
        RuntimeAccess.setButtonText(state.serversButton, "Servers (" + verified + ")");
        RuntimeAccess.setButtonText(state.scannedButton, "Scanned Servers (" + scanned + ")");
        RuntimeAccess.setButtonText(state.recentButton, "Recent Servers (" + recent + "/5)");

        boolean autoJoinTab = isAutoJoinView(state.view);
        int eligible = state.view == View.SERVERS ? verified : scanned;
        setActive(state.autoJoinButton, autoJoinTab && (eligible > 0 || coreAutoJoinEnabled()));
        RuntimeAccess.setButtonText(state.autoJoinButton, autoJoinLabel());
    }

    private static void toggleCategoryAutoJoin(State state) {
        if (!isAutoJoinView(state.view)) return;
        if (coreAutoJoinEnabled()) {
            stopCoreAutoJoin(true);
        } else {
            autoJoinView = state.view;
            AutoJoinEntrypoint.saveTargetCategory(autoJoinView == View.SERVERS ? "servers" : "scanned");
            long eligible = state.saved.stream().filter(ServerTabsEntrypoint::eligibleForCurrentAutoJoinView).count();
            if (eligible <= 0) return;
            RuntimeAccess.invokeStatic(CORE_AUTO_JOIN, "resetRuntimeState");
            setStaticBoolean(CORE_AUTO_JOIN, "enabled", true);
            RuntimeAccess.invokeStatic(CORE_AUTO_JOIN, "saveEnabled", true);
            seedCoreAutoJoinExclusions(state.saved);
            System.out.println("[Zazu's Server Tool] Auto Join started for " + autoJoinViewLabel()
                    + " (" + eligible + " eligible; favourites excluded).");
        }
        RuntimeAccess.setButtonText(state.autoJoinButton, autoJoinLabel());
    }

    @SuppressWarnings("unchecked")
    private static void seedCoreAutoJoinExclusions(List<ServerListAccess.Saved> saved) {
        Object attemptedObject = RuntimeAccess.staticField(CORE_AUTO_JOIN, "ATTEMPTED");
        if (!(attemptedObject instanceof Set<?> raw)) return;
        Set<Object> attempted = (Set<Object>) raw;
        for (ServerListAccess.Saved server : saved) {
            if (!eligibleForCurrentAutoJoinView(server)) {
                attempted.add(ServerListAccess.normalize(server.endpoint()));
            }
        }
    }

    private static boolean isAutoJoinView(View view) {
        return view == View.SERVERS || view == View.SCANNED;
    }

    private static boolean eligibleForCurrentAutoJoinView(ServerListAccess.Saved server) {
        if (server == null || server.favourite()) return false;
        boolean scanned = ServerCategoryStore.isScanned(server.endpoint());
        return autoJoinView == View.SCANNED ? scanned : autoJoinView == View.SERVERS && !scanned;
    }

    private static String autoJoinViewLabel() {
        return autoJoinView == View.SERVERS ? "Servers" : "Scanned Servers";
    }

    private static View loadAutoJoinView() {
        return "servers".equalsIgnoreCase(AutoJoinEntrypoint.loadTargetCategory())
                ? View.SERVERS : View.SCANNED;
    }

    private static boolean coreAutoJoinEnabled() {
        return RuntimeAccess.staticBoolean(CORE_AUTO_JOIN, "enabled", false);
    }

    private static void stopCoreAutoJoin(boolean log) {
        setStaticBoolean(CORE_AUTO_JOIN, "enabled", false);
        RuntimeAccess.invokeStatic(CORE_AUTO_JOIN, "saveEnabled", false);
        RuntimeAccess.invokeStatic(CORE_AUTO_JOIN, "resetRuntimeState");
        if (log) System.out.println("[Zazu's Server Tool] Auto Join stopped for " + autoJoinViewLabel() + ".");
    }

    private static String autoJoinLabel() {
        return "Auto Join: " + (coreAutoJoinEnabled() ? "ON" : "OFF");
    }


    private static int widgetInt(Object widget, String getter, String field, int fallback) {
        if (widget == null) return fallback;
        Object value = RuntimeAccess.invoke(widget, getter);
        if (value instanceof Number n) return n.intValue();
        value = RuntimeAccess.field(widget, field);
        return value instanceof Number n ? n.intValue() : fallback;
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
                || widget == state.recentButton || widget == state.categoriesButton || widget == state.autoJoinButton;
    }

    private static ServerCategoryStore.Tab tabForView(View view) {
        return switch (view) {
            case FAVOURITES -> ServerCategoryStore.Tab.FAVOURITES;
            case SERVERS -> ServerCategoryStore.Tab.SERVERS;
            case SCANNED -> ServerCategoryStore.Tab.SCANNED;
            case RECENT -> ServerCategoryStore.Tab.RECENT;
            case HUB -> throw new IllegalStateException("Hub has no server category");
        };
    }

    static String recentAttemptEndpoint() {
        long now = System.currentTimeMillis();
        if (!activeAttemptEndpoint.isBlank() && activeAttemptAt != 0L
                && now - activeAttemptAt >= 0L && now - activeAttemptAt <= ATTEMPT_CONTEXT_MS) {
            return activeAttemptEndpoint;
        }
        String core = RuntimeAccess.staticString(CORE_AUTO_JOIN, "lastAutoJoinEndpoint");
        if (!core.isBlank()) return core;
        if (!lastSelectedEndpoint.isBlank() && lastSelectedAt != 0L
                && now - lastSelectedAt >= 0L && now - lastSelectedAt <= ATTEMPT_CONTEXT_MS) {
            return lastSelectedEndpoint;
        }
        return "";
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
        if (endpoint.isBlank()) endpoint = currentConnectedEndpoint(RuntimeAccess.minecraftInstance());
        if (endpoint.isBlank()) return;

        connectedEndpoint = endpoint;
        final String candidate = endpoint;
        final boolean scannedCandidate = ServerCategoryStore.isScanned(candidate);
        final long generation = ++playGeneration;
        System.out.println("[Zazu's Server Tool] " + candidate
                + " entered PLAY; verifying for 8 seconds before recording successful join.");
        CompletableFuture.delayedExecutor(STABLE_JOIN_MS, TimeUnit.MILLISECONDS).execute(() -> {
            if (playGeneration != generation) return;
            ServerCategoryStore.recordSuccessfulJoin(candidate);
            if (scannedCandidate && ServerCategoryStore.promoteVerified(candidate)) {
                System.out.println("[Zazu's Server Tool] Stable connection verified; moved to Servers: " + candidate);
            }
        });
    }

    private static void onPlayDisconnect() {
        playGeneration++;
        connectedEndpoint = "";
    }

    static int autoJoinEligibleTotal() {
        int count = 0;
        for (ServerListAccess.Saved s : lastKnownSaved) {
            if (eligibleForCurrentAutoJoinView(s)) count++;
        }
        return count;
    }

    static int autoJoinAttemptedScannedCount() {
        Object attemptedObject = RuntimeAccess.staticField(CORE_AUTO_JOIN, "ATTEMPTED");
        if (!(attemptedObject instanceof Set<?> attempted)) return 0;
        int count = 0;
        for (ServerListAccess.Saved s : lastKnownSaved) {
            if (!eligibleForCurrentAutoJoinView(s)) continue;
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

    private enum View { HUB, FAVOURITES, SERVERS, SCANNED, RECENT }

    private record Bounds(int x, int y, int width, int height) {}

    private static final class State {
        final Object client, screen;
        final int width, height;
        final List<Object> baseWidgets = new ArrayList<>();
        final Map<Object, Bounds> originalBounds = new IdentityHashMap<>();
        Object listWidget;
        Object favouritesButton, serversButton, scannedButton, recentButton, categoriesButton, autoJoinButton;
        List<ServerListAccess.Saved> saved = List.of();
        String lastSignature = "";
        View view = View.HUB;
        View appliedView;
        boolean loggedFailure;
        boolean scannedHealthProbeInFlight;
        int scannedHealthCursor;
        long nextScannedHealthProbeAt;
        long scannedHealthGeneration;

        State(Object client, Object screen, int width, int height) {
            this.client = client;
            this.screen = screen;
            this.width = width;
            this.height = height;
        }
    }
}
