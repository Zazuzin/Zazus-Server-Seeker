package dev.zazuzin.zst;

import net.fabricmc.api.ClientModInitializer;

import java.lang.reflect.*;
import java.util.*;

/** Installs the Multiplayer-screen management controls. */
public final class MultiplayerManagementEntrypoint implements ClientModInitializer {
    static final String FAV_PREFIX = "★ ";
    private static final Map<Object, MultiplayerState> STATES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<Object> MANAGED_WIDGETS = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    @Override
    public void onInitializeClient() {
        try {
            registerGlobalAfterInit();
            System.out.println("[Zazu's Server Tool] 0.3.33 multiplayer management hook registered.");
        } catch (Throwable t) {
            System.err.println("[Zazu's Server Tool] 0.3.33 failed to register: " + Reflection.unwrap(t));
        }
    }

    private static void registerGlobalAfterInit() throws Exception {
        Reflection.registerStaticEvent(
                "net.fabricmc.fabric.api.client.screen.v1.ScreenEvents",
                "AFTER_INIT",
                "net.fabricmc.fabric.api.client.screen.v1.ScreenEvents$AfterInit",
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) return Reflection.objectMethod(proxy, method, args);
                    if (args != null && args.length >= 4) {
                        Object client = args[0], screen = args[1];
                        int width = args[2] instanceof Number n ? n.intValue() : Reflection.screenWidth(screen, 854);
                        int height = args[3] instanceof Number n ? n.intValue() : Reflection.screenHeight(screen, 480);
                        if (Reflection.isScreen(screen, "JoinMultiplayerScreen")) {
                            try { installMultiplayerControls(client, screen, width, height); }
                            catch (Throwable t) { System.err.println("[Zazu's Server Tool] Multiplayer UI setup failed: " + Reflection.unwrap(t)); }
                        }
                    }
                    return null;
                });
    }

    private static void installMultiplayerControls(Object client, Object screen, int width, int height) throws Exception {
        MultiplayerState state = new MultiplayerState(client, screen, width, height);
        STATES.put(screen, state);

        if (ToolState.favouritesFirst) {
            try { sortSavedServersFavouritesFirst(client); }
            catch (Throwable t) { System.err.println("[Zazu's Server Tool] Favourites-first sorting skipped: " + Reflection.unwrap(t)); }
        }

        state.finderButton = Reflection.makeButton("Zazu's Server Tool", 6, Math.max(6, height - 28), 170, 20,
                b -> openFinder(state));
        Reflection.addWidget(screen, state.finderButton);
        MANAGED_WIDGETS.add(state.finderButton);

        state.deleteAllButton = Reflection.makeButton("Delete Non-Favourites", 6, 6, 148, 20,
                b -> deleteAllPressed(state));
        Reflection.addWidget(screen, state.deleteAllButton);
        MANAGED_WIDGETS.add(state.deleteAllButton);

        createPerServerButtons(state);
        registerRowButtonMouseInterceptor(state);
        registerAfterTick(state);
        System.out.println("[Zazu's Server Tool] 0.3.33 multiplayer controls installed. ViaFabricPlus integration: " + (ViaFabricPlusBridge.isAvailable() ? "available" : "not installed"));
    }

    private static void createPerServerButtons(MultiplayerState state) throws Exception {
        for (ServerButtons buttons : state.serverButtons) {
            try { Reflection.widgets(state.screen).remove(buttons.favourite); Reflection.widgets(state.screen).remove(buttons.delete); } catch (Throwable ignored) {}
        }
        state.serverButtons.clear();
        List<Object> entries = onlineServerEntries(state.screen);
        for (Object entry : entries) {
            Object data = getServerData(entry);
            if (data == null) continue;
            String endpoint = ServerFinderClient.ServerListBridge.serverEndpoint(data);
            if (endpoint.isBlank()) continue;
            ServerButtons sb = new ServerButtons(entry, data, endpoint);
            sb.favourite = Reflection.makeButton(isFavourite(data) ? "★" : "☆", 0, -100, 22, 20, b -> toggleFavourite(state, sb));
            sb.delete = Reflection.makeButton(isFavourite(data) ? "Locked" : "Delete", 0, -100, 48, 20, b -> deleteSingle(state, sb));
            Reflection.addWidget(state.screen, sb.favourite);
            Reflection.addWidget(state.screen, sb.delete);
            MANAGED_WIDGETS.add(sb.favourite);
            MANAGED_WIDGETS.add(sb.delete);
            state.serverButtons.add(sb);
        }
        updatePerServerButtons(state);
    }

    private static void registerRowButtonMouseInterceptor(MultiplayerState state) throws Exception {
        Class<?> holder = Class.forName("net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents");
        Method factory = null;
        for (Method method : holder.getMethods()) {
            if (Modifier.isStatic(method.getModifiers()) && method.getName().equals("allowMouseClick")
                    && method.getParameterCount() == 1 && method.getParameterTypes()[0].isInstance(state.screen)) {
                factory = method;
                break;
            }
        }
        if (factory == null) throw new IllegalStateException("ScreenMouseEvents.allowMouseClick(Screen) not found");
        Object event = factory.invoke(null, state.screen);
        Class<?> callback = Class.forName("net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents$AllowMouseClick");
        Object listener = Proxy.newProxyInstance(callback.getClassLoader(), new Class<?>[]{callback}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return Reflection.objectMethod(proxy, method, args);
            if (!ServerTabsEntrypoint.isServerListView(state.screen) || ServerFinderClient.isOverlayOpen(state.screen)) return Boolean.TRUE;
            Object mouse = args == null || args.length == 0 ? null : args[args.length - 1];
            if (mouse == null || mouseButton(mouse) != 0) return Boolean.TRUE;
            double x = mouseCoordinate(mouse, "x"), y = mouseCoordinate(mouse, "y");
            try {
                for (ServerButtons buttons : state.serverButtons) {
                    if (visibleAndContains(buttons.favourite, x, y)) {
                        toggleFavourite(state, buttons);
                        return Boolean.FALSE;
                    }
                    if (visibleAndContains(buttons.delete, x, y)) {
                        deleteSingle(state, buttons);
                        return Boolean.FALSE;
                    }
                }
                for (ServerButtons buttons : state.serverButtons) {
                    if (entryContains(buttons.entry, x, y)) {
                        applyViaFabricPlusForEndpoint(buttons.endpoint);
                        break;
                    }
                }
            } catch (Throwable t) {
                System.err.println("[Zazu's Server Tool] Row click handling failed: " + Reflection.unwrap(t));
            }
            return Boolean.TRUE;
        });
        RuntimeAccess.registerEvent(event, listener);
    }

    private static double mouseCoordinate(Object event, String axis) {
        Object value = Reflection.invokeQuiet(event, axis);
        if (!(value instanceof Number)) value = Reflection.getField(event, axis);
        return value instanceof Number n ? n.doubleValue() : Double.NaN;
    }

    private static int mouseButton(Object event) {
        Object value = Reflection.invokeQuiet(event, "button");
        if (!(value instanceof Number)) value = Reflection.getField(event, "button");
        return value instanceof Number n ? n.intValue() : -1;
    }

    private static boolean visibleAndContains(Object widget, double x, double y) {
        if (widget == null || Double.isNaN(x) || Double.isNaN(y)) return false;
        Object visible = Reflection.getField(widget, "visible");
        Object active = Reflection.getField(widget, "active");
        if (visible instanceof Boolean b && !b) return false;
        if (active instanceof Boolean b && !b) return false;
        int wx = Reflection.intValue(widget, "getX", Reflection.intValue(widget, "x", Integer.MIN_VALUE));
        int wy = Reflection.intValue(widget, "getY", Reflection.intValue(widget, "y", Integer.MIN_VALUE));
        int ww = Reflection.intValue(widget, "getWidth", Reflection.intValue(widget, "width", 0));
        int wh = Reflection.intValue(widget, "getHeight", Reflection.intValue(widget, "height", 0));
        return wx != Integer.MIN_VALUE && wy != Integer.MIN_VALUE && x >= wx && x < wx + ww && y >= wy && y < wy + wh;
    }

    private static boolean entryContains(Object entry, double x, double y) {
        if (entry == null || Double.isNaN(x) || Double.isNaN(y)) return false;
        Object value = Reflection.invokeQuiet(entry, "isMouseOver", x, y);
        if (value instanceof Boolean b) return b;
        value = Reflection.invokeQuiet(entry, "contains", x, y);
        return value instanceof Boolean b && b;
    }

    private static void registerAfterTick(MultiplayerState state) throws Exception {
        Class<?> screenEvents = Class.forName("net.fabricmc.fabric.api.client.screen.v1.ScreenEvents");
        Object afterTickEvent = null;
        for (Method m : screenEvents.getMethods()) {
            if (Modifier.isStatic(m.getModifiers()) && m.getName().equals("afterTick") && m.getParameterCount() == 1) {
                afterTickEvent = m.invoke(null, state.screen); break;
            }
        }
        if (afterTickEvent == null) throw new IllegalStateException("ScreenEvents.afterTick(Screen) was not found");
        Class<?> callback = Class.forName("net.fabricmc.fabric.api.client.screen.v1.ScreenEvents$AfterTick");
        Object listener = Proxy.newProxyInstance(callback.getClassLoader(), new Class<?>[]{callback}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return Reflection.objectMethod(proxy, method, args);
            try {
                if (Reflection.currentScreen(state.client) != state.screen) return null;
                updatePerServerButtons(state);
                updateDeleteAllConfirmation(state);
                syncViaFabricPlusForSelected(state);
            } catch (Throwable t) {
                if (!state.loggedTickFailure) {
                    state.loggedTickFailure = true;
                    System.err.println("[Zazu's Server Tool] Row button update failed: " + Reflection.unwrap(t));
                }
            }
            return null;
        });
        registerEventObject(afterTickEvent, listener);
    }

    private static void registerEventObject(Object event, Object listener) throws Exception {
        RuntimeAccess.registerEvent(event, listener);
    }

    static boolean isManagedWidget(Object widget) {
        return widget != null && MANAGED_WIDGETS.contains(widget);
    }

    static Object finderButton(Object screen) {
        MultiplayerState state = STATES.get(screen);
        return state == null ? null : state.finderButton;
    }

    static Object deleteNonFavouritesButton(Object screen) {
        MultiplayerState state = STATES.get(screen);
        return state == null ? null : state.deleteAllButton;
    }

    static List<Object> rowWidgets(Object screen) {
        MultiplayerState state = STATES.get(screen);
        if (state == null) return List.of();
        ArrayList<Object> out = new ArrayList<>(state.serverButtons.size() * 2);
        for (ServerButtons buttons : state.serverButtons) {
            if (buttons.favourite != null) out.add(buttons.favourite);
            if (buttons.delete != null) out.add(buttons.delete);
        }
        return out;
    }

    static void rebuildRowButtons(Object screen) {
        MultiplayerState state = STATES.get(screen);
        if (state == null) return;
        try { createPerServerButtons(state); } catch (Throwable ignored) {}
    }

    private static void updatePerServerButtons(MultiplayerState state) throws Exception {
        if (!ServerTabsEntrypoint.isServerListView(state.screen)) return;
        boolean finderOpen = ServerFinderClient.isOverlayOpen(state.screen);
        List<Object> entries = onlineServerEntries(state.screen);
        if (entries.size() != state.serverButtons.size()) {
            createPerServerButtons(state);
            return;
        }

        Object listWidget = Reflection.getField(state.screen, "serverSelectionList", "serverList");
        int listTop = listWidget == null ? 32 : Reflection.intValue(listWidget, "getY", Reflection.intValue(listWidget, "y0", 32));
        int listBottom = listWidget == null ? state.height - 64 : listTop + Reflection.intValue(listWidget, "getHeight", state.height - listTop - 64);
        int rowLeft = listWidget == null ? state.width / 2 - 154 : Reflection.intValue(listWidget, "getRowLeft", state.width / 2 - 154);
        int rowWidth = listWidget == null ? 308 : Reflection.intValue(listWidget, "getRowWidth", 308);
        int scrollbarX = listWidget == null ? rowLeft + rowWidth + 4 : Reflection.intValue(listWidget, "getScrollbarPosition", rowLeft + rowWidth + 4);

        for (int i = 0; i < state.serverButtons.size(); i++) {
            ServerButtons sb = state.serverButtons.get(i);
            Object entry = entries.get(i);
            Object data = getServerData(entry);
            String endpoint = data == null ? "" : ServerFinderClient.ServerListBridge.serverEndpoint(data);
            if (!ToolState.normalize(endpoint).equals(ToolState.normalize(sb.endpoint))) {
                createPerServerButtons(state); return;
            }
            int top = currentRowTop(listWidget, entry, i, listTop);
            boolean onScreen = top + 20 >= listTop && top <= listBottom - 2;
            boolean show = !finderOpen && onScreen;
            Reflection.setBoolean(sb.favourite, "visible", show);
            Reflection.setBoolean(sb.delete, "visible", show);
            Reflection.setBoolean(sb.favourite, "active", show);
            boolean fav = isFavourite(data);
            Reflection.setBoolean(sb.delete, "active", show && !fav);
            Reflection.setButtonText(sb.favourite, fav ? "★" : "☆");
            Reflection.setButtonText(sb.delete, fav ? "Locked" : "Delete");

            // Keep controls clear of the scrollbar. They sit inside the right side of the row.
            int deleteX = Math.min(rowLeft + rowWidth - 50, scrollbarX - 52);
            int favX = deleteX - 24;
            Reflection.setPosition(sb.favourite, favX, top);
            Reflection.setPosition(sb.delete, deleteX, top);
        }
        protectVanillaDeleteButton(state);
    }

    private static int currentRowTop(Object listWidget, Object entry, int index, int fallbackTop) {
        if (listWidget != null) {
            for (String method : List.of("getRowTop", "getEntryPosition")) {
                Method m = Reflection.findMethod(listWidget.getClass(), method, 1);
                if (m != null) {
                    try {
                        Object arg = m.getParameterTypes()[0].isPrimitive() ? index : entry;
                        Object value = m.invoke(listWidget, arg);
                        if (value instanceof Number n) return n.intValue();
                    } catch (Throwable ignored) {}
                }
            }
            double scroll = Reflection.doubleValue(listWidget, "getScrollAmount", 0);
            int itemHeight = Reflection.intValue(listWidget, "itemHeight", 36);
            int header = Reflection.intValue(listWidget, "headerHeight", 0);
            return fallbackTop + header + index * itemHeight - (int)Math.round(scroll);
        }
        return fallbackTop + index * 36;
    }

    private static void toggleFavourite(MultiplayerState state, ServerButtons sb) {
        try {
            Object list = ServerFinderClient.ServerListBridge.createLoadedList(state.client);
            Object server = ServerFinderClient.ServerListBridge.findServer(list, sb.endpoint);
            if (server == null) return;
            String name = ServerFinderClient.ServerListBridge.serverName(server);
            String updated = name.startsWith(FAV_PREFIX) ? name.substring(FAV_PREFIX.length()) : FAV_PREFIX + name;
            ServerFinderClient.ServerListBridge.setServerName(server, updated);
            ServerFinderClient.ServerListBridge.save(list);
            refreshMultiplayerScreen(state.client, state.screen);
            System.out.println("[Zazu's Server Tool] Favourite toggled for " + sb.endpoint);
        } catch (Throwable t) {
            System.err.println("[Zazu's Server Tool] Favourite toggle failed: " + Reflection.unwrap(t));
        }
    }

    private static void deleteSingle(MultiplayerState state, ServerButtons sb) {
        try {
            Object list = ServerFinderClient.ServerListBridge.createLoadedList(state.client);
            Object server = ServerFinderClient.ServerListBridge.findServer(list, sb.endpoint);
            if (server != null && isFavourite(server)) {
                System.out.println("[Zazu's Server Tool] Delete blocked for favourite " + sb.endpoint);
                return;
            }
            if (ServerFinderClient.ServerListBridge.remove(state.client, sb.endpoint)) {
                refreshMultiplayerScreen(state.client, state.screen);
                System.out.println("[Zazu's Server Tool] Quick deleted " + sb.endpoint);
            }
        } catch (Throwable t) {
            System.err.println("[Zazu's Server Tool] Quick delete failed: " + Reflection.unwrap(t));
        }
    }

    private static void deleteAllPressed(MultiplayerState state) {
        long now = System.currentTimeMillis();
        if (now > state.deleteAllArmedUntil) {
            state.deleteAllArmedUntil = now + 3500;
            Reflection.setButtonText(state.deleteAllButton, "Confirm Delete Non-Favs");
            return;
        }
        state.deleteAllArmedUntil = 0;
        Reflection.setButtonText(state.deleteAllButton, "Delete Non-Favourites");
        try {
            Object list = ServerFinderClient.ServerListBridge.createLoadedList(state.client);
            List<Object> servers = new ArrayList<>(ServerFinderClient.ServerListBridge.servers(list));
            int deleted = 0;
            for (Object server : servers) {
                if (isFavourite(server)) continue;
                String endpoint = ServerFinderClient.ServerListBridge.serverEndpoint(server);
                // This control is exposed only in the established Servers view.
                // Do not delete hidden Scanned entries from the shared servers.dat store.
                if (ServerCategoryStore.isScanned(endpoint)) continue;
                if (deleteFromLoadedList(list, server)) {
                    ToolState.recordDeleted(endpoint);
                    ServerCategoryStore.remove(endpoint);
                    deleted++;
                }
            }
            ServerFinderClient.ServerListBridge.save(list);
            refreshMultiplayerScreen(state.client, state.screen);
            System.out.println("[Zazu's Server Tool] Deleted " + deleted + " non-favourite servers.");
        } catch (Throwable t) {
            System.err.println("[Zazu's Server Tool] Delete Non-Favourites failed: " + Reflection.unwrap(t));
        }
    }

    private static boolean deleteFromLoadedList(Object list, Object server) throws Exception {
        List<Object> backing = ServerFinderClient.ServerListBridge.servers(list);
        return backing.remove(server);
    }

    private static void updateDeleteAllConfirmation(MultiplayerState state) {
        if (state.deleteAllArmedUntil != 0 && System.currentTimeMillis() > state.deleteAllArmedUntil) {
            state.deleteAllArmedUntil = 0;
            Reflection.setButtonText(state.deleteAllButton, "Delete Non-Favourites");
        }
    }

    private static void openFinder(MultiplayerState state) {
        try { ServerFinderClient.openOverlay(state.client, state.screen, state.width, state.height); }
        catch (Throwable t) { System.err.println("[Zazu's Server Tool] Could not open finder: " + Reflection.unwrap(t)); }
    }

    private static void sortSavedServersFavouritesFirst(Object client) throws Exception {
        Object list = ServerFinderClient.ServerListBridge.createLoadedList(client);
        List<Object> servers = ServerFinderClient.ServerListBridge.servers(list);
        if (servers instanceof ArrayList<?> || !servers.getClass().getName().contains("Immutable")) {
            servers.sort(Comparator.comparing(server -> !isFavourite(server)));
            ServerFinderClient.ServerListBridge.save(list);
        }
    }

    private static void syncViaFabricPlusForSelected(MultiplayerState state) {
        if (!ViaFabricPlusBridge.isAvailable()) return;
        try {
            Object selected = Reflection.invokeQuiet(state.screen, "getSelected");
            Object data = selected == null ? null : getServerData(selected);
            String endpoint = data == null ? "" : ServerFinderClient.ServerListBridge.serverEndpoint(data);
            if (endpoint.equalsIgnoreCase(state.lastViaEndpoint)) return;
            state.lastViaEndpoint = endpoint;
            if (endpoint.isBlank()) {
                ViaFabricPlusBridge.setTargetVersion("26.2");
                return;
            }
            applyViaFabricPlusForEndpoint(endpoint);
        } catch (Throwable t) {
            if (!state.loggedViaFailure) {
                state.loggedViaFailure = true;
                System.err.println("[Zazu's Server Tool] ViaFabricPlus selection sync failed: " + Reflection.unwrap(t));
            }
        }
    }

    static void applyViaFabricPlusForEndpoint(String endpoint) {
        if (!ViaFabricPlusBridge.isAvailable()) return;
        int protocol = ToolState.protocolFor(endpoint);
        if (protocol > 0 && ViaFabricPlusBridge.setTargetProtocol(protocol)) return;
        String version = ToolState.versionFor(endpoint);
        if (!version.isBlank()) ViaFabricPlusBridge.setTargetVersion(version);
    }

    static void refreshMultiplayerScreen(Object client, Object screen) {
        try {
            Method refresh = Reflection.findMethod(screen.getClass(), "refreshServerList", 0);
            if (refresh != null) {
                refresh.invoke(screen);
                rebuildRowButtons(screen);
                System.out.println("[Zazu's Server Tool] Vanilla Multiplayer Refresh invoked.");
                return;
            }
        } catch (Throwable ignored) {}
        // A full-screen recreation is deliberately a fallback only; it is less compatible with other mods.
        rebuildRowButtons(screen);
    }

    static void reopenMultiplayerScreen(Object client, Object parentHint) {
        Object current = Reflection.currentScreen(client);
        if (current != null && Reflection.isScreen(current, "JoinMultiplayerScreen")) {
            refreshMultiplayerScreen(client, current);
            return;
        }
        Object multiplayer = findMultiplayerAncestor(parentHint);
        if (multiplayer != null) {
            try { Reflection.setScreen(client, multiplayer); refreshMultiplayerScreen(client, multiplayer); } catch (Throwable ignored) {}
        }
    }

    private static Object findMultiplayerAncestor(Object screen) {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Object cursor = screen;
        while (cursor != null && seen.add(cursor)) {
            if (Reflection.isScreen(cursor, "JoinMultiplayerScreen")) return cursor;
            Object next = Reflection.getField(cursor, "parent", "lastScreen");
            if (next == cursor) break;
            cursor = next;
        }
        return null;
    }

    private static void protectVanillaDeleteButton(MultiplayerState state) {
        try {
            Object selected = Reflection.invokeQuiet(state.screen, "getSelected");
            Object data = selected == null ? null : getServerData(selected);
            boolean fav = data != null && isFavourite(data);
            for (Object widget : Reflection.widgets(state.screen)) {
                if (widget == state.deleteAllButton) continue;
                String label = widgetLabel(widget).toLowerCase(Locale.ROOT);
                if (label.equals("delete") || label.contains("delete server")) {
                    int y = Reflection.intValue(widget, "getY", Reflection.intValue(widget, "y", 0));
                    if (y > state.height / 2) Reflection.setBoolean(widget, "active", !fav);
                }
            }
            state.disabledVanillaDelete = fav;
        } catch (Throwable ignored) {}
    }

    private static String widgetLabel(Object widget) {
        Object msg = Reflection.invokeQuiet(widget, "getMessage");
        if (msg == null) msg = Reflection.invokeQuiet(widget, "getText");
        if (msg == null) return "";
        Object text = Reflection.invokeQuiet(msg, "getString");
        return text == null ? String.valueOf(msg) : String.valueOf(text);
    }

    @SuppressWarnings("unchecked")
    static List<Object> onlineServerEntries(Object screen) {
        Object listWidget = Reflection.getField(screen, "serverSelectionList", "serverList");
        if (listWidget == null) return List.of();
        Object online = Reflection.getField(listWidget, "onlineServers", "serverEntries");
        if (online instanceof List<?> l) return (List<Object>) l;
        Object children = Reflection.invokeQuiet(listWidget, "children");
        if (children instanceof List<?> l) {
            ArrayList<Object> result = new ArrayList<>();
            for (Object e : l) if (getServerData(e) != null) result.add(e);
            return result;
        }
        return List.of();
    }

    static Object getServerData(Object entry) {
        if (entry == null) return null;
        Object value = Reflection.invokeQuiet(entry, "getServerData");
        if (value != null) return value;
        value = Reflection.getField(entry, "server", "serverData");
        if (value != null) return value;
        String simple = entry.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        if (simple.contains("server") && (Reflection.getField(entry, "ip", "address") != null)) return entry;
        return null;
    }

    static boolean isFavourite(Object serverData) {
        return ServerFinderClient.ServerListBridge.serverName(serverData).startsWith(FAV_PREFIX);
    }

    static String selectedEndpoint(Object multiplayerScreen) {
        Object selected = Reflection.invokeQuiet(multiplayerScreen, "getSelected");
        Object data = selected == null ? null : getServerData(selected);
        if (data == null) return "";
        try { return ServerFinderClient.ServerListBridge.serverEndpoint(data); } catch (Throwable ignored) { return ""; }
    }

    static final class MultiplayerState {
        final Object client, screen;
        final int width, height;
        final List<ServerButtons> serverButtons = new ArrayList<>();
        Object finderButton, deleteAllButton;
        boolean disabledVanillaDelete, loggedTickFailure, loggedViaFailure;
        String lastViaEndpoint = "";
        long deleteAllArmedUntil;
        MultiplayerState(Object client, Object screen, int width, int height) {
            this.client = client; this.screen = screen; this.width = width; this.height = height;
        }
    }

    static final class ServerButtons {
        final Object entry, serverData;
        final String endpoint;
        Object favourite, delete;
        ServerButtons(Object entry, Object serverData, String endpoint) { this.entry = entry; this.serverData = serverData; this.endpoint = endpoint; }
    }
}
