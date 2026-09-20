package dev.zazuzin.zst;

import net.fabricmc.api.ClientModInitializer;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Multiplayer category workflow.
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
    private static final long SAVED_REFRESH_INTERVAL_MS = 500L;
    private static final long LAYOUT_MAINTENANCE_INTERVAL_MS = 1_000L;
    private static final long BUTTON_REFRESH_INTERVAL_MS = 500L;
    private static final long SELECTION_POLL_INTERVAL_MS = 200L;
    private static final int FOOTER_BUTTON_GAP = 10;

    private static final Map<Object, State> STATES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, ScreenRoute> SCREEN_ROUTES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<String, Integer> SCANNED_HEALTH_FAILURES = new ConcurrentHashMap<>();
    private static final Map<Object, List<Object>> PAUSE_MENU_WIDGETS = Collections.synchronizedMap(new WeakHashMap<>());
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
            System.out.println("[Zazu's Server Seeker] Multiplayer categories, scanned-server health checks, Recent Servers and Auto Join enabled.");
        } catch (Throwable t) {
            System.err.println("[Zazu's Server Seeker] Could not enable Multiplayer category hub: " + root(t));
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
                System.err.println("[Zazu's Server Seeker] Multiplayer category screen hook failed: " + root(t));
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
            List<Object> previous = PAUSE_MENU_WIDGETS.remove(screen);
            if (previous != null) previous.forEach(widget -> Reflection.removeWidget(screen, widget));

            String endpoint = currentConnectedEndpoint(client);
            if (endpoint.isBlank()) return;

            String displayName = currentConnectedServerName(client);
            boolean favourite = MultiplayerManagementEntrypoint.isFavouriteEndpoint(client, endpoint);
            Object[] holder = new Object[1];
            Object favouriteButton = makeButton(pauseFavouriteLabel(favourite), 6, 6, 160, 20, ignored -> {
                try {
                    boolean nowFavourite = MultiplayerManagementEntrypoint.toggleFavouriteEndpoint(client, endpoint, displayName);
                    Reflection.setButtonText(holder[0], pauseFavouriteLabel(nowFavourite));
                    System.out.println("[Zazu's Server Seeker] Pause-menu favourite "
                            + (nowFavourite ? "enabled for " : "removed from ") + endpoint);
                } catch (Throwable t) {
                    System.err.println("[Zazu's Server Seeker] Pause-menu favourite toggle failed: " + root(t));
                }
            });
            holder[0] = favouriteButton;

            Object font = RuntimeAccess.font(screen);
            String fullAddressLabel = "Server: " + endpoint;
            int maxAddressWidth = Math.max(80, Math.min(260, width - 12));
            int addressWidth = Math.min(maxAddressWidth,
                    Math.max(160, RuntimeAccess.width(font, fullAddressLabel) + 12));
            String addressLabel = RuntimeAccess.trimToWidth(font, fullAddressLabel, addressWidth - 12);
            Object addressDisplay = makeButton(addressLabel, 6, 30, addressWidth, 20, ignored -> {});
            setActive(addressDisplay, false);
            Reflection.setTooltip(addressDisplay, "Current server: " + endpoint);

            Object copyButton = makeButton("Copy IP", 6, 54, 80, 20, pressed -> {
                try {
                    copyToClipboard(client, endpoint);
                    Reflection.setButtonText(pressed, "Copied!");
                    System.out.println("[Zazu's Server Seeker] Copied current server address to clipboard: " + endpoint);
                } catch (Throwable t) {
                    Reflection.setButtonText(pressed, "Copy failed");
                    System.err.println("[Zazu's Server Seeker] Could not copy current server address: " + root(t));
                }
            });
            Reflection.setTooltip(copyButton, "Copy " + endpoint + " to the clipboard");

            List<Object> widgets = List.of(favouriteButton, addressDisplay, copyButton);
            PAUSE_MENU_WIDGETS.put(screen, widgets);
            for (Object widget : widgets) Reflection.addWidget(screen, widget);
        } catch (Throwable t) {
            System.err.println("[Zazu's Server Seeker] Could not add pause-menu server controls: " + root(t));
        }
    }

    private static String pauseFavouriteLabel(boolean favourite) {
        return favourite ? "★ Unfavourite Server" : "☆ Favourite Server";
    }

    private static void copyToClipboard(Object client, String text) throws Exception {
        Object keyboard = Reflection.getField(client, "keyboardHandler", "keyboard", "keyboardManager");
        if (keyboard == null) {
            for (String getter : List.of("getKeyboardHandler", "getKeyboard", "getKeyboardManager")) {
                keyboard = Reflection.invokeQuiet(client, getter);
                if (keyboard != null) break;
            }
        }
        if (keyboard == null) throw new NoSuchFieldException("Minecraft keyboard handler");
        Reflection.invoke(keyboard, "setClipboard", text);
    }

    static String currentConnectedEndpoint(Object client) {
        if (!connectedEndpoint.isBlank()) return connectedEndpoint;
        String endpoint = currentServerEndpoint(client);
        if (!endpoint.isBlank()) connectedEndpoint = endpoint;
        return endpoint;
    }

    /** Reads Minecraft's live server data without consulting our connection cache. */
    private static String currentServerEndpoint(Object client) {
        Object data = currentServerData(client);
        if (data == null) return "";
        try {
            return ServerFinderClient.ServerListBridge.serverEndpoint(data);
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
        // A screen can be initialised repeatedly after resize, refresh or return
        // from a child screen. Keep its route for the screen object's lifetime;
        // consuming it once made later inits fall back to the category hub.
        ScreenRoute route = SCREEN_ROUTES.get(screen);
        View requestedView = route == null ? consumePendingViewAfterRefresh() : route.view;
        Object hubScreen = route != null && route.hubScreen != null
                ? route.hubScreen : previous != null ? previous.hubScreen : screen;
        if (previous != null) detachCustomWidgets(previous);
        purgeStaleOwnedWidgets(screen);

        State state = new State(client, screen, hubScreen, width, height);
        if (previous != null) {
            state.nativeRefreshBounds = previous.nativeRefreshBounds;
            state.nativeBackBounds = previous.nativeBackBounds;
            state.nativeDeleteBounds = previous.nativeDeleteBounds;
        }
        STATES.put(screen, state);

        state.baseWidgets.addAll(widgets(screen));
        state.baseWidgets.removeIf(MultiplayerManagementEntrypoint::isManagedWidget);
        state.listWidget = ServerListAccess.listWidget(screen);
        captureOriginalBounds(state);
        captureOriginalBounds(state, MultiplayerManagementEntrypoint.finderButton(screen));
        captureOriginalBounds(state, MultiplayerManagementEntrypoint.deleteNonFavouritesButton(screen));
        // Native Back/Escape returns to the existing hub screen. Reload its
        // ServerList before reading category membership so a stale starred
        // name cannot undo an unfavourite made on the child category screen.
        if (previous != null && route == null && hubScreen == screen) {
            ServerListAccess.reloadCategory(client, screen, null);
        }
        captureFullRows(state, true);
        createNavigationControls(state);
        if (route != null && route.view != View.HUB) removeNativeRefreshControls(state);
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
        System.out.println("[Zazu's Server Seeker] Multiplayer category hub installed on "
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
        View view = switch (tab) {
            case FAVOURITES -> View.FAVOURITES;
            case SERVERS -> View.SERVERS;
            case SCANNED -> View.SCANNED;
            case RECENT -> View.RECENT;
        };
        State state = STATES.get(screen);
        if (state != null) state.requestedReplacementView = view;
        else requestViewAfterRefresh(view);
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

    /** Creates the category hub and category-owned navigation controls. */
    private static void createNavigationControls(State state) throws Exception {
        int buttonWidth = Math.min(300, Math.max(220, state.width / 3));
        int x = (state.width - buttonWidth) / 2;
        int gap = 24;
        int startY = Math.max(52, state.height / 2 - 62);

        state.favouritesButton = makeButton("Favourites", x, startY, buttonWidth, 20,
                b -> openCategoryScreen(state, ServerCategoryStore.Tab.FAVOURITES));
        state.serversButton = makeButton("Servers", x, startY + gap, buttonWidth, 20,
                b -> openCategoryScreen(state, ServerCategoryStore.Tab.SERVERS));
        state.scannedButton = makeButton("Scanned Servers", x, startY + gap * 2, buttonWidth, 20,
                b -> openCategoryScreen(state, ServerCategoryStore.Tab.SCANNED));
        state.recentButton = makeButton("Recent Servers", x, startY + gap * 3, buttonWidth, 20,
                b -> openCategoryScreen(state, ServerCategoryStore.Tab.RECENT));

        Object tool = MultiplayerManagementEntrypoint.finderButton(state.screen);
        Bounds toolBounds = originalBounds(state, tool);
        int buttonH = toolBounds != null ? toolBounds.height : 20;
        // Capture Minecraft's settled Refresh slot. The replacement remains in
        // this footer position; layoutCategoryControls trims it against the
        // live Delete and Back bounds after every resize/layout maintenance pass.
        for (Object widget : state.baseWidgets) {
            if (!isNativeRefreshWidget(state, widget)) continue;
            state.nativeRefreshBounds = originalBounds(state, widget);
            if (state.nativeRefreshBounds != null) break;
        }
        Bounds refreshBounds = state.nativeRefreshBounds;
        if (refreshBounds == null) {
            int footerGap = FOOTER_BUTTON_GAP;
            int footerWidth = Math.min(100, Math.max(70, (state.width - 360) / 4));
            int footerTotal = footerWidth * 4 + footerGap * 3;
            int footerStartX = Math.max(6, (state.width - footerTotal) / 2);
            refreshBounds = new Bounds(footerStartX + 2 * (footerWidth + footerGap),
                    Math.max(6, state.height - 28), footerWidth, buttonH);
        }
        // Cache both neighbours while the native footer is known to be intact.
        // Auto Join can later re-initialise the same screen with replacement
        // widget instances, so these bounds are also carried into the next State.
        liveFooterBounds(state, "Delete");
        liveBackBounds(state);
        state.categoryRefreshButton = makeButton("Refresh", refreshBounds.x(),
                refreshBounds.y(), refreshBounds.width(), refreshBounds.height(),
                b -> refreshCategoryInPlace(state));

        rememberOwned(state.favouritesButton, state.serversButton, state.scannedButton, state.recentButton,
                state.categoryRefreshButton);
        Reflection.addWidget(state.screen, state.favouritesButton);
        Reflection.addWidget(state.screen, state.serversButton);
        Reflection.addWidget(state.screen, state.scannedButton);
        Reflection.addWidget(state.screen, state.recentButton);
        Reflection.addWidget(state.screen, state.categoryRefreshButton);
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
            if (!RuntimeAccess.isPrimaryMouseButton(mouse)) return Boolean.TRUE;
            double x = mouseCoordinate(mouse, "x"), y = mouseCoordinate(mouse, "y");

            // Dispatch the real Minecraft mouseClicked(MouseButtonEvent, boolean)
            // method first for our category controls. Crucially, we cancel vanilla
            // processing only when the button actually consumed the click; a
            // failed reflective dispatch must never make a button dead.
            for (Object widget : Arrays.asList(state.autoJoinButton, state.categoryRefreshButton,
                    MultiplayerManagementEntrypoint.finderButton(state.screen))) {
                if (visibleActiveContains(widget, x, y) && dispatchWidgetClick(widget, mouse)) {
                    return Boolean.FALSE;
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

    private static void removeNativeRefreshControls(State state) {
        int removed = 0;
        for (Object widget : new ArrayList<>(Reflection.screenListElements(state.screen))) {
            if (!isNativeRefreshWidget(state, widget)) continue;
            setVisibleActive(widget, false, false);
            Reflection.removeWidget(state.screen, widget);
            state.baseWidgets.removeIf(candidate -> candidate == widget);
            state.originalBounds.remove(widget);
            removed++;
        }
        System.out.println("[Zazu's Server Seeker] Removed " + removed
                + " native Refresh control(s); fitted category refresh enabled.");
    }

    private static void suppressNativeRefreshControls(State state) {
        for (Object widget : new ArrayList<>(Reflection.screenListElements(state.screen))) {
            if (!isNativeRefreshWidget(state, widget)) continue;
            setVisibleActive(widget, false, false);
            Reflection.removeWidget(state.screen, widget);
            state.baseWidgets.removeIf(candidate -> candidate == widget);
            state.originalBounds.remove(widget);
        }
    }

    private static boolean isNativeRefreshWidget(State state, Object widget) {
        if (widget == null || widget == state.categoryRefreshButton) return false;
        if (widgetLabel(widget).trim().equals("Refresh")) return true;
        Object message = RuntimeAccess.invoke(widget, "getMessage");
        if (message == null) message = RuntimeAccess.invoke(widget, "getText");
        if (message == null) message = RuntimeAccess.field(widget, "message");
        String raw = String.valueOf(message).toLowerCase(Locale.ROOT);
        return raw.contains("selectserver.refresh") || raw.contains("select_server.refresh");
    }

    private static double mouseCoordinate(Object event, String axis) {
        Object value = RuntimeAccess.invoke(event, axis);
        if (!(value instanceof Number)) value = RuntimeAccess.field(event, axis);
        return value instanceof Number n ? n.doubleValue() : Double.NaN;
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

    static boolean refreshCurrentScreen(Object screen) {
        State state = STATES.get(screen);
        if (state == null || state.view == View.HUB) return false;
        refreshCategoryInPlace(state);
        return true;
    }

    private static void openCategoryScreen(State state, ServerCategoryStore.Tab tab) {
        if (state == null || tab == null) return;
        openCategoryScreen(state, switch (tab) {
            case FAVOURITES -> View.FAVOURITES;
            case SERVERS -> View.SERVERS;
            case SCANNED -> View.SCANNED;
            case RECENT -> View.RECENT;
        });
    }

    private static void openCategoryScreen(State state, View view) {
        try {
            Object hub = state.view == View.HUB ? state.screen : state.hubScreen;
            Object categoryScreen = newMultiplayerScreen(state.screen.getClass(), hub);
            SCREEN_ROUTES.put(categoryScreen, new ScreenRoute(view, hub));
            ScreenCompat.setScreen(state.client, categoryScreen);
        } catch (Throwable t) {
            logOnce(state, "Could not open separate category screen", t);
        }
    }

    private static void refreshCategoryInPlace(State state) {
        if (state == null || state.view == View.HUB) return;
        View view = state.requestedReplacementView == null ? state.view : state.requestedReplacementView;
        state.requestedReplacementView = null;
        try {
            // Reload servers.dat directly and update the existing selection
            // widget. Never call JoinMultiplayerScreen.refreshServerList(): it
            // reinitialises the screen and recreates vanilla footer controls.
            ServerListAccess.reloadCategory(state.client, state.screen, tabForView(view));
            captureFullRows(state, true);
            showCategory(state, tabForView(view));
            state.layoutDirty = true;
            applyLayout(state);
        } catch (Throwable t) {
            logOnce(state, "Could not refresh current category in place", t);
        }
    }

    private static Object newMultiplayerScreen(Class<?> type, Object parent) throws Exception {
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length != 1 || parent == null || !parameters[0].isInstance(parent)) continue;
            constructor.setAccessible(true);
            return constructor.newInstance(parent);
        }
        throw new NoSuchMethodException("JoinMultiplayerScreen(Screen parent)");
    }

    private static void showHub(State state) {
        if (state == null) return;
        state.scannedHealthGeneration++;
        state.scannedHealthProbeInFlight = false;
        state.view = View.HUB;
        // The hub's native list is already the full saved-server list and is
        // hidden behind category navigation. Rebuilding it here duplicated
        // every vanilla server row (and its status work) before the hub opened.
        MultiplayerManagementEntrypoint.clearRowButtons(state.screen);
        refreshCachedSaved(state, false);
        try {
            ServerListAccess.clearVisibleRows(state.client, state.screen);
        } catch (Throwable t) {
            logOnce(state, "Could not suspend hidden hub server pings", t);
        }
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
                    System.err.println("[Zazu's Server Seeker] Multiplayer category tick failed: " + root(t));
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

        long now = System.currentTimeMillis();
        // Minecraft 26.2 can rebuild the native footer between our periodic
        // layout passes. Remove its Refresh duplicate on every screen tick.
        if (state.view != View.HUB) suppressNativeRefreshControls(state);
        int liveWidth = RuntimeAccess.intField(state.screen, "width", state.width);
        int liveHeight = RuntimeAccess.intField(state.screen, "height", state.height);
        if (liveWidth > 0 && liveHeight > 0 && (liveWidth != state.width || liveHeight != state.height)) {
            state.width = liveWidth;
            state.height = liveHeight;
            state.layoutDirty = true;
        }

        if (finderOpen(state.screen)) {
            if (!state.finderWasOpen) {
                state.finderWasOpen = true;
                setVisibleActive(state.favouritesButton, false, false);
                setVisibleActive(state.serversButton, false, false);
                setVisibleActive(state.scannedButton, false, false);
                setVisibleActive(state.recentButton, false, false);
                setVisibleActive(state.autoJoinButton, false, false);
            }
            return;
        }
        if (state.finderWasOpen) {
            state.finderWasOpen = false;
            state.layoutDirty = true;
        }

        // The server list and category widgets do not need a full reflective
        // rescan every 50ms. Explicit refresh/category actions still update
        // immediately; this periodic pass only catches external changes.
        if (now >= state.nextSavedRefreshAt) {
            state.nextSavedRefreshAt = now + SAVED_REFRESH_INTERVAL_MS;
            refreshCachedSaved(state, true);
        }

        tickScannedHealthCleanup(state);

        if (state.view != View.HUB && now >= state.nextSelectionPollAt) {
            state.nextSelectionPollAt = now + SELECTION_POLL_INTERVAL_MS;
            String selected = ServerListAccess.selectedEndpoint(state.screen);
            if (!selected.isBlank()) {
                lastSelectedEndpoint = selected;
                lastSelectedAt = now;
            }
        }

        boolean autoJoinEnabled = coreAutoJoinEnabled();
        if (autoJoinEnabled && now >= state.nextAutoJoinMaintenanceAt) {
            state.nextAutoJoinMaintenanceAt = now + BUTTON_REFRESH_INTERVAL_MS;
            if (state.view != autoJoinView) stopCoreAutoJoin(true);
            else seedCoreAutoJoinExclusions(state.saved);
        }

        if (state.layoutDirty || state.appliedView != state.view || now >= state.nextLayoutMaintenanceAt) {
            state.nextLayoutMaintenanceAt = now + LAYOUT_MAINTENANCE_INTERVAL_MS;
            applyLayout(state);
        }

        if (now >= state.nextButtonRefreshAt) {
            state.nextButtonRefreshAt = now + BUTTON_REFRESH_INTERVAL_MS;
            updateButtons(state);
        }
    }

    /**
     * Existing Scanned Servers are rechecked in small batches while that tab is
     * open. A non-favourite scanned entry is removed only after three eligible
     * DNS/unreachable failures. Timeouts, local probe errors and protocol or
     * client-version mismatches never count. Any successful reply immediately
     * resets its failure streak.
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
            int knownProtocol = ToolState.protocolFor(endpoint);
            if (knownProtocol > 0) ServerAuthService.ensureAsync(state.client, endpoint, knownProtocol, null);
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
                    ServerAuthService.ensureAsync(state.client, endpoint, result.protocol(), null);
                    Integer previous = SCANNED_HEALTH_FAILURES.remove(key);
                    if (previous != null && previous > 0) {
                        System.out.println("[Zazu's Server Seeker] Scanned health recovered: " + endpoint
                                + " (failure streak reset from " + previous + ").");
                    }
                    continue;
                }

                // Internal/local probe errors and timeouts are not evidence of
                // a permanent failure. Version mismatches normally still give
                // a status reply and are deliberately left for ViaFabricPlus.
                if (result == null || result.failure() == VanillaStatusProbe.Failure.ERROR
                        || result.failure() == VanillaStatusProbe.Failure.TIMEOUT) continue;
                if (!ToolState.automaticCleanupEnabled) continue;
                if (result.failure() == VanillaStatusProbe.Failure.DNS
                        && !ToolState.cleanupInvalidAddressEnabled) continue;
                if (result.failure() == VanillaStatusProbe.Failure.UNREACHABLE
                        && !ToolState.cleanupUnreachableEnabled) continue;

                int failures = ServerCategoryStore.recordHealthFailure(endpoint);
                SCANNED_HEALTH_FAILURES.put(key, failures);
                System.out.println("[Zazu's Server Seeker] Scanned health failed " + failures + "/"
                        + SCANNED_FAILURES_BEFORE_DELETE + ": " + endpoint
                        + " (" + result.failure() + ").");

                if (failures < SCANNED_FAILURES_BEFORE_DELETE) continue;
                if (!stillEligibleForScannedHealthDelete(state, endpoint)) {
                    SCANNED_HEALTH_FAILURES.remove(key);
                    continue;
                }

                if (ServerListAccess.isFavouriteEndpoint(state.client, state.screen, endpoint)) {
                    SCANNED_HEALTH_FAILURES.remove(key);
                    System.out.println("[Zazu's Server Seeker] Kept favourite after failed scanned health checks: " + endpoint);
                    continue;
                }

                ServerCategoryStore.backupBeforeAutomaticRemoval();
                if (ServerListAccess.forceRemove(state.client, endpoint)) {
                    String savedName = state.saved.stream()
                            .filter(saved -> ServerListAccess.normalize(saved.endpoint()).equals(key))
                            .map(ServerListAccess.Saved::name).findFirst().orElse("");
                    String reason = result.failure() == VanillaStatusProbe.Failure.DNS
                            ? DisconnectReason.CleanupCause.INVALID_ADDRESS.label()
                            : DisconnectReason.CleanupCause.UNREACHABLE.label();
                    ServerCategoryStore.recordAutomaticRemoval(savedName, endpoint, reason, result.detail());
                    ServerCategoryStore.remove(endpoint);
                    ToolState.recordDeleted(endpoint);
                    SCANNED_HEALTH_FAILURES.remove(key);
                    deleted++;
                    System.out.println("[Zazu's Server Seeker] Auto-deleted scanned server after "
                            + SCANNED_FAILURES_BEFORE_DELETE + " eligible failed checks: " + endpoint
                            + " (" + reason + ").");
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
        ServerCategoryStore.reconcileSaved(state.saved, migrate);
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
            ServerCategoryStore.reconcileSaved(state.saved, false);
            if (detectChanges && state.view != View.HUB) {
                applyCategoryRows(state, tabForView(state.view));
                MultiplayerManagementEntrypoint.rebuildRowButtons(state.screen);
            }
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

        // Minecraft can recreate/update its native footer after AFTER_INIT.
        // Keep only the working fitted category refresh visible and clickable.
        if (state.view != View.HUB) suppressNativeRefreshControls(state);

        List<Object> live = widgets(state.screen);
        boolean learnedWidgets = live != null && learnBaseWidgets(state, live);
        captureMissingOriginalBounds(state);
        boolean layoutNeeded = learnedWidgets || state.appliedView != state.view || state.layoutDirty;

        Object tool = MultiplayerManagementEntrypoint.finderButton(state.screen);
        Object deleteNonFavourites = MultiplayerManagementEntrypoint.deleteNonFavouritesButton(state.screen);
        Object undoLastDelete = MultiplayerManagementEntrypoint.undoLastDeleteButton(state.screen);
        List<Object> rowWidgets = MultiplayerManagementEntrypoint.allRowWidgets(state.screen);

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
        setVisibleActive(state.categoryRefreshButton, !hub, !hub);
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

        if (layoutNeeded) {
            // Stale-widget cleanup is only needed when the screen/view actually
            // changed. Running the identity sweep every maintenance tick was a
            // measurable source of Multiplayer menu overhead.
            purgeStaleOwnedWidgetsExceptCurrent(state);
            if (hub) layoutHub(state, tool);
        }
        // Footer positions can be changed in place by Minecraft or another mod
        // without registering a new widget. Refit the handful of category
        // controls on every one-second layout-maintenance pass so Refresh always
        // follows the live Delete/Back geometry after GUI-scale changes.
        if (!hub) layoutCategoryControls(state);
        state.layoutDirty = false;
        state.appliedView = state.view;

        // Run after base-widget discovery and visibility restoration as well.
        // Vanilla may re-register its footer during the same layout cycle.
        if (!hub) suppressNativeRefreshControls(state);
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
        if (isNativeRefreshWidget(state, widget) && state.view != View.HUB) return false;
        if (label.equals("Delete All Servers") || label.equals("Confirm Delete Servers")
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
                state.categoryRefreshButton, state.autoJoinButton)) {
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
        Object bulkDelete = MultiplayerManagementEntrypoint.deleteNonFavouritesButton(state.screen);
        Object undoDelete = MultiplayerManagementEntrypoint.undoLastDeleteButton(state.screen);

        // Keep Zazu-specific controls in the unused left rail beside the centred
        // vanilla footer. Finder is aligned with the footer row and Auto Join is
        // directly above it; their X range remains outside the vanilla controls.
        int listTop = widgetInt(state.listWidget, "getY", "y", 32);
        int rowWidth = state.listWidget == null ? 308 : widgetInt(state.listWidget, "getRowWidth", "rowWidth", 308);
        if (rowWidth <= 0 || rowWidth > state.width) rowWidth = Math.min(308, Math.max(120, state.width - 24));
        int rowLeft = Math.max(margin, (state.width - rowWidth) / 2);
        int availableLeft = Math.max(0, rowLeft - margin - 8);
        int railW = Math.min(170, Math.max(96, availableLeft));
        int railX = margin;
        if (availableLeft < 96) railW = Math.min(140, Math.max(96, state.width / 4));

        int toolH = 20;
        if (bulkDelete != null) setBounds(bulkDelete, railX, listTop, railW, toolH);
        if (undoDelete != null) setBounds(undoDelete, railX, listTop + toolH + 4, railW, toolH);
        Bounds nativeRefreshBounds = state.nativeRefreshBounds;
        int finderY = nativeRefreshBounds != null ? nativeRefreshBounds.y() : Math.max(listTop + 48, state.height - 28);
        if (tool != null) setBounds(tool, railX, finderY, railW, toolH);

        Bounds fittedRefreshBounds = fittedFooterRefreshBounds(state);
        if (fittedRefreshBounds != null) {
            setBounds(state.categoryRefreshButton, fittedRefreshBounds.x(), fittedRefreshBounds.y(),
                    fittedRefreshBounds.width(), fittedRefreshBounds.height());
        }

        if (state.autoJoinButton != null) {
            int autoY = Math.max(listTop + 48, finderY - toolH - 4);
            setBounds(state.autoJoinButton, railX, autoY, railW, toolH);
        }

    }

    /**
     * Keeps Refresh in Minecraft's original footer slot while enforcing a real
     * gap from the live neighbouring buttons. The preferred bounds normally pass
     * through unchanged; only an overlap introduced by resize, GUI scale or a
     * late footer relayout causes an edge to be moved inward.
     */
    private static Bounds fittedFooterRefreshBounds(State state) {
        Bounds preferred = state.nativeRefreshBounds;
        if (preferred == null) return null;

        Bounds delete = liveFooterBounds(state, "Delete");
        Bounds back = liveBackBounds(state);
        int left = preferred.x();
        int right = preferred.x() + preferred.width();
        int gap = FOOTER_BUTTON_GAP;

        if (delete != null && verticallyOverlaps(preferred, delete)) {
            left = Math.max(left, delete.x() + delete.width() + gap);
        }
        if (back != null && verticallyOverlaps(preferred, back)) {
            right = Math.min(right, back.x() - gap);
        }

        // A very narrow custom layout may leave no usable portion of the old
        // native slot. If Delete and Back still leave a real gap, use that exact
        // space rather than moving Refresh to a different part of the screen.
        if (right <= left && delete != null && back != null && verticallyOverlaps(delete, back)) {
            left = delete.x() + delete.width() + gap;
            right = back.x() - gap;
        }
        if (right <= left) {
            // There is no full-width slot in an exceptionally narrow layout.
            // Preserve the non-overlap guarantee even there; normal Minecraft
            // minimum window sizes leave substantially more than one pixel.
            if (back != null && verticallyOverlaps(preferred, back)) {
                int safeRight = back.x() - gap;
                return new Bounds(Math.min(preferred.x(), safeRight - 1),
                        preferred.y(), 1, preferred.height());
            }
            return preferred;
        }
        return new Bounds(left, preferred.y(), right - left, preferred.height());
    }

    private static Bounds liveFooterBounds(State state, String label) {
        Bounds preferred = state.nativeRefreshBounds;
        Bounds best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Object widget : liveFooterWidgets(state)) {
            if (widget == null || !widgetLabel(widget).trim().equals(label)) continue;
            Bounds bounds = liveBounds(widget);
            if (bounds == null) continue;
            int distance = preferred == null ? 0 : Math.abs(bounds.y() - preferred.y());
            if (best == null || distance < bestDistance) {
                best = bounds;
                bestDistance = distance;
            }
        }
        if (best != null && label.equals("Delete")) state.nativeDeleteBounds = best;
        return best != null ? best : label.equals("Delete") ? state.nativeDeleteBounds : null;
    }

    private static Bounds liveBackBounds(State state) {
        Bounds preferred = state.nativeRefreshBounds;
        Bounds best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Object widget : liveFooterWidgets(state)) {
            if (widget == null || !isNativeBackWidget(widget)) continue;
            Bounds bounds = liveBounds(widget);
            if (bounds == null) continue;
            int distance = preferred == null ? 0 : Math.abs(bounds.y() - preferred.y());
            if (best == null || distance < bestDistance) {
                best = bounds;
                bestDistance = distance;
            }
        }
        if (best != null) state.nativeBackBounds = best;
        return best != null ? best : state.nativeBackBounds;
    }

    private static List<Object> liveFooterWidgets(State state) {
        ArrayList<Object> result = new ArrayList<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        // screenListElements includes the replacement button instances created
        // when Auto Join returns from Connect/Disconnected screens. baseWidgets
        // remains as a fallback for integrations that expose only Fabric widgets.
        for (Object widget : Reflection.screenListElements(state.screen)) {
            if (widget != null && seen.add(widget)) result.add(widget);
        }
        for (Object widget : state.baseWidgets) {
            if (widget != null && seen.add(widget)) result.add(widget);
        }
        return result;
    }

    private static Bounds liveBounds(Object widget) {
        int x = widgetInt(widget, "getX", "x", Integer.MIN_VALUE);
        int y = widgetInt(widget, "getY", "y", Integer.MIN_VALUE);
        int width = widgetInt(widget, "getWidth", "width", Integer.MIN_VALUE);
        int height = widgetInt(widget, "getHeight", "height", Integer.MIN_VALUE);
        if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE
                || width <= 0 || height <= 0) return null;
        return new Bounds(x, y, width, height);
    }

    private static boolean verticallyOverlaps(Bounds first, Bounds second) {
        return first != null && second != null
                && first.y() < second.y() + second.height()
                && second.y() < first.y() + first.height();
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
            System.out.println("[Zazu's Server Seeker] Auto Join started for " + autoJoinViewLabel()
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
        if (log) System.out.println("[Zazu's Server Seeker] Auto Join stopped for " + autoJoinViewLabel() + ".");
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
                || widget == state.recentButton || widget == state.autoJoinButton
                || widget == state.categoryRefreshButton;
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

    /**
     * Records the endpoint before Minecraft replaces Multiplayer with its
     * Connect screen. The whitelist watcher sees every normal row, keyboard and
     * Direct Connect attempt, so sharing that capture prevents a later JOIN
     * event from accidentally reusing the previous server's address.
     */
    static void noteConnectionAttempt(String endpoint) {
        String normalized = ServerListAccess.normalize(endpoint);
        if (normalized.isBlank()) return;
        activeAttemptEndpoint = normalized;
        activeAttemptAt = System.currentTimeMillis();
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
        Object client = RuntimeAccess.minecraftInstance();
        long now = System.currentTimeMillis();

        // Minecraft's current ServerData is authoritative once PLAY begins.
        // Attempt data remains a fallback because some compatible clients do
        // not expose currentServerData early enough in the JOIN callback.
        String liveEndpoint = currentServerEndpoint(client);
        String attemptedEndpoint = activeAttemptAt != 0L
                && now - activeAttemptAt >= 0L && now - activeAttemptAt <= ATTEMPT_CONTEXT_MS
                ? activeAttemptEndpoint : "";
        String coreEndpoint = RuntimeAccess.staticString(CORE_AUTO_JOIN, "lastAutoJoinEndpoint");
        String selectedEndpoint = lastSelectedAt != 0L
                && now - lastSelectedAt >= 0L && now - lastSelectedAt <= ATTEMPT_CONTEXT_MS
                ? lastSelectedEndpoint : "";

        String endpoint = firstPresent(liveEndpoint, attemptedEndpoint, coreEndpoint, selectedEndpoint);
        if (endpoint.isBlank()) return;

        connectedEndpoint = endpoint;
        final String candidate = endpoint;
        // Keep every reliable spelling of the destination. Minecraft may expose
        // the live address with an explicit default port while servers.dat kept
        // the original address without one; the captured attempt remains the
        // exact key used by the Scanned category in that situation.
        final List<String> promotionCandidates = distinctEndpoints(
                attemptedEndpoint, selectedEndpoint, coreEndpoint, liveEndpoint);
        final long generation = ++playGeneration;
        System.out.println("[Zazu's Server Seeker] " + candidate
                + " entered PLAY; verifying for 8 seconds before recording successful join.");
        CompletableFuture.delayedExecutor(STABLE_JOIN_MS, TimeUnit.MILLISECONDS).execute(() -> {
            if (playGeneration != generation) return;
            String promotedEndpoint = "";
            for (String possible : promotionCandidates) {
                if (ServerCategoryStore.promoteVerified(possible)) {
                    promotedEndpoint = possible;
                    break;
                }
            }
            String recordedEndpoint = promotedEndpoint.isBlank() ? candidate : promotedEndpoint;
            ServerCategoryStore.recordSuccessfulJoin(recordedEndpoint);
            BreakBlocksContributor.submitConnected(candidate);
            if (!promotedEndpoint.isBlank()) {
                System.out.println("[Zazu's Server Seeker] Stable connection verified; moved to Servers: " + promotedEndpoint);
            } else {
                System.out.println("[Zazu's Server Seeker] Stable connection verified: " + candidate
                        + " (already in Servers or not classified as Scanned).");
            }
            activeAttemptEndpoint = "";
            activeAttemptAt = 0L;
        });
    }

    private static void onPlayDisconnect() {
        playGeneration++;
        connectedEndpoint = "";
        activeAttemptEndpoint = "";
        activeAttemptAt = 0L;
    }

    private static String firstPresent(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    private static List<String> distinctEndpoints(String... values) {
        LinkedHashMap<String, String> endpoints = new LinkedHashMap<>();
        for (String value : values) {
            String normalized = ServerListAccess.normalize(value);
            if (!normalized.isBlank()) endpoints.putIfAbsent(normalized, value.trim());
        }
        return List.copyOf(endpoints.values());
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
        System.err.println("[Zazu's Server Seeker] " + message + ": " + root(t));
    }

    private static Throwable root(Throwable t) {
        Throwable current = t;
        while ((current instanceof InvocationTargetException || current instanceof ExceptionInInitializerError)
                && current.getCause() != null) current = current.getCause();
        return current;
    }

    private enum View { HUB, FAVOURITES, SERVERS, SCANNED, RECENT }

    private record Bounds(int x, int y, int width, int height) {}
    private record ScreenRoute(View view, Object hubScreen) {}

    private static final class State {
        final Object client, screen, hubScreen;
        int width, height;
        final List<Object> baseWidgets = new ArrayList<>();
        final Map<Object, Bounds> originalBounds = new IdentityHashMap<>();
        Bounds nativeRefreshBounds, nativeBackBounds, nativeDeleteBounds;
        Object listWidget;
        Object favouritesButton, serversButton, scannedButton, recentButton, categoryRefreshButton;
        Object autoJoinButton;
        List<ServerListAccess.Saved> saved = List.of();
        String lastSignature = "";
        View view = View.HUB;
        View appliedView;
        View requestedReplacementView;
        boolean loggedFailure;
        boolean layoutDirty;
        boolean finderWasOpen;
        boolean scannedHealthProbeInFlight;
        int scannedHealthCursor;
        long nextScannedHealthProbeAt;
        long scannedHealthGeneration;
        long nextSavedRefreshAt;
        long nextLayoutMaintenanceAt;
        long nextButtonRefreshAt;
        long nextSelectionPollAt;
        long nextAutoJoinMaintenanceAt;

        State(Object client, Object screen, Object hubScreen, int width, int height) {
            this.client = client;
            this.screen = screen;
            this.hubScreen = hubScreen;
            this.width = width;
            this.height = height;
        }
    }
}
