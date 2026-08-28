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
            System.out.println("[Zazu's Server Seeker] 0.3.41 multiplayer management hook registered.");
        } catch (Throwable t) {
            System.err.println("[Zazu's Server Seeker] 0.3.41 failed to register: " + Reflection.unwrap(t));
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
                            catch (Throwable t) { System.err.println("[Zazu's Server Seeker] Multiplayer UI setup failed: " + Reflection.unwrap(t)); }
                        }
                    }
                    return null;
                });
    }

    private static void installMultiplayerControls(Object client, Object screen, int width, int height) throws Exception {
        MultiplayerState previous = STATES.get(screen);
        if (previous != null) detachManagedWidgets(previous);

        MultiplayerState state = new MultiplayerState(client, screen, width, height);
        STATES.put(screen, state);

        if (ToolState.favouritesFirst) {
            try { sortSavedServersFavouritesFirst(client); }
            catch (Throwable t) { System.err.println("[Zazu's Server Seeker] Favourites-first sorting skipped: " + Reflection.unwrap(t)); }
        }

        state.finderButton = Reflection.makeButton("Zazu's Server Seeker", 6, Math.max(6, height - 28), 170, 20,
                b -> openFinder(state));
        Reflection.addWidget(screen, state.finderButton);
        MANAGED_WIDGETS.add(state.finderButton);

        state.deleteAllButton = Reflection.makeButton("Delete Non-Favourites", 6, 6, 148, 20,
                b -> deleteAllPressed(state));
        Reflection.addWidget(screen, state.deleteAllButton);
        MANAGED_WIDGETS.add(state.deleteAllButton);
        state.undoButton = Reflection.makeButton("Undo Last Delete", 6, 30, 148, 20, b -> undoLastDelete(state));
        Reflection.addWidget(screen, state.undoButton); MANAGED_WIDGETS.add(state.undoButton);
        // ServerTabsEntrypoint owns category visibility. Start hidden so the
        // control cannot flash on the category hub before its first layout.
        Reflection.setBoolean(state.undoButton, "visible", false);
        Reflection.setBoolean(state.undoButton, "active", false);

        createPerServerButtons(state);
        registerRowButtonMouseInterceptor(state);
        registerAfterTick(state);
        System.out.println("[Zazu's Server Seeker] 0.3.41 multiplayer controls installed. ViaFabricPlus integration: " + (ViaFabricPlusBridge.isAvailable() ? "available" : "not installed"));
    }

    private static void detachManagedWidgets(MultiplayerState state) {
        if (state == null) return;
        Reflection.removeWidget(state.screen, state.finderButton);
        Reflection.removeWidget(state.screen, state.deleteAllButton);
        Reflection.removeWidget(state.screen, state.undoButton);
        for (ServerButtons buttons : new ArrayList<>(state.serverButtons)) {
            Reflection.removeWidget(state.screen, buttons.favourite);
            Reflection.removeWidget(state.screen, buttons.delete);
            Reflection.removeWidget(state.screen, buttons.health);
        }
        state.serverButtons.clear();
    }

    private static void createPerServerButtons(MultiplayerState state) throws Exception {
        for (ServerButtons buttons : state.serverButtons) {
            Reflection.removeWidget(state.screen, buttons.favourite);
            Reflection.removeWidget(state.screen, buttons.delete);
            Reflection.removeWidget(state.screen, buttons.health);
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
            sb.health = Reflection.makeButton(healthLabel(endpoint), 0, -100, 58, 20, b -> {
                ServerCategoryStore.resetHealth(endpoint); try { updatePerServerButtons(state); } catch (Throwable ignored) {}
            });
            Reflection.addWidget(state.screen, sb.favourite);
            Reflection.addWidget(state.screen, sb.delete);
            Reflection.addWidget(state.screen, sb.health);
            MANAGED_WIDGETS.add(sb.favourite);
            MANAGED_WIDGETS.add(sb.delete);
            MANAGED_WIDGETS.add(sb.health);
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
            if (STATES.get(state.screen) != state) return Boolean.TRUE;
            if (!ServerTabsEntrypoint.isServerListView(state.screen) || ServerFinderClient.isOverlayOpen(state.screen)) return Boolean.TRUE;
            Object mouse = args == null || args.length == 0 ? null : args[args.length - 1];
            if (mouse == null || mouseButton(mouse) != 0) return Boolean.TRUE;
            double x = mouseCoordinate(mouse, "x"), y = mouseCoordinate(mouse, "y");
            try {
                if (visibleAndContains(state.undoButton, x, y)) {
                    undoLastDelete(state); return Boolean.FALSE;
                }
                for (ServerButtons buttons : state.serverButtons) {
                    if (visibleAndContains(buttons.favourite, x, y)) {
                        toggleFavourite(state, buttons);
                        return Boolean.FALSE;
                    }
                    if (visibleAndContains(buttons.delete, x, y)) {
                        deleteSingle(state, buttons);
                        return Boolean.FALSE;
                    }
                    if (visibleAndContains(buttons.health, x, y)) {
                        ServerCategoryStore.resetHealth(buttons.endpoint);
                        updatePerServerButtons(state);
                        return Boolean.FALSE;
                    }
                }
                for (ServerButtons buttons : state.serverButtons) {
                    if (entryContains(buttons.entry, x, y)) {
                        // Record the exact endpoint before Minecraft can transition
                        // into ConnectScreen (including a same-tick double click).
                        WhitelistAutoDeleteEntrypoint.noteAttempt(buttons.endpoint);
                        applyViaFabricPlusForEndpoint(buttons.endpoint);
                        break;
                    }
                }
            } catch (Throwable t) {
                System.err.println("[Zazu's Server Seeker] Row click handling failed: " + Reflection.unwrap(t));
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
                if (STATES.get(state.screen) != state) return null;
                if (Reflection.currentScreen(state.client) != state.screen) return null;
                updatePerServerButtons(state);
                updateDeleteAllConfirmation(state);
                syncViaFabricPlusForSelected(state);
            } catch (Throwable t) {
                if (!state.loggedTickFailure) {
                    state.loggedTickFailure = true;
                    System.err.println("[Zazu's Server Seeker] Row button update failed: " + Reflection.unwrap(t));
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

    static Object undoLastDeleteButton(Object screen) {
        MultiplayerState state = STATES.get(screen);
        return state == null ? null : state.undoButton;
    }

    static void configureBulkDelete(Object screen, boolean scannedView) {
        MultiplayerState state = STATES.get(screen);
        if (state == null || state.scannedDeleteMode == scannedView) return;
        state.scannedDeleteMode = scannedView;
        state.deleteAllArmedUntil = 0;
        Reflection.setButtonText(state.deleteAllButton, bulkDeleteLabel(state));
    }

    static List<Object> rowWidgets(Object screen) {
        MultiplayerState state = STATES.get(screen);
        if (state == null) return List.of();
        ArrayList<Object> out = new ArrayList<>(state.serverButtons.size() * 4);
        for (ServerButtons buttons : state.serverButtons) {
            if (buttons.favourite != null) out.add(buttons.favourite);
            if (buttons.delete != null) out.add(buttons.delete);
            if (buttons.health != null) out.add(buttons.health);
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
            Reflection.setBoolean(sb.health, "visible", show);
            Reflection.setBoolean(sb.favourite, "active", show);
            boolean fav = isFavourite(data);
            Reflection.setBoolean(sb.delete, "active", show && !fav);
            Reflection.setBoolean(sb.health, "active", show);
            Reflection.setButtonText(sb.favourite, fav ? "★" : "☆");
            Reflection.setButtonText(sb.health, healthLabel(endpoint));
            Reflection.setButtonText(sb.delete, fav ? "Locked" : "Delete");

            // Keep row controls in one deterministic column immediately to the
            // LEFT of Minecraft's centred server row. 26.2 can report getRowLeft()
            // as zero through reflective/modded list wrappers; falling back to the
            // screen-centred row calculation prevents the buttons jumping to the
            // ping/player-count side of the row.
            int totalWidth = 22 + 4 + 58 + 4 + 48;
            int actualRowLeft = currentRowLeft(listWidget, entry, rowLeft);
            int centeredRowLeft = Math.max(6, (state.width - rowWidth) / 2);
            boolean implausibleLeft = actualRowLeft < totalWidth + 12
                    || actualRowLeft + Math.max(1, rowWidth) > state.width + 4;
            if (implausibleLeft) actualRowLeft = centeredRowLeft;
            int favX = Math.max(6, actualRowLeft - totalWidth - 6);
            int deleteX = favX + 26;
            int healthX = deleteX; deleteX = healthX + 62;
            int controlY = currentRowControlY(listWidget, entry, top);
            Reflection.setPosition(sb.favourite, favX, controlY);
            Reflection.setPosition(sb.delete, deleteX, controlY);
            Reflection.setPosition(sb.health, healthX, controlY);
        }
        protectVanillaDeleteButton(state);
    }

    private static int currentRowLeft(Object listWidget, Object entry, int fallbackLeft) {
        if (entry != null) {
            Object value = Reflection.invokeQuiet(entry, "getX");
            if (value instanceof Number n && n.intValue() >= 6) return n.intValue();
            value = Reflection.getField(entry, "x");
            if (value instanceof Number n && n.intValue() >= 6) return n.intValue();
        }
        return fallbackLeft;
    }

    private static int currentRowControlY(Object listWidget, Object entry, int rowTop) {
        int rowHeight = 36;
        if (entry != null) {
            Object value = Reflection.invokeQuiet(entry, "getHeight");
            if (value instanceof Number n && n.intValue() > 0) rowHeight = n.intValue();
            else {
                value = Reflection.getField(entry, "height");
                if (value instanceof Number n && n.intValue() > 0) rowHeight = n.intValue();
            }
        } else if (listWidget != null) {
            rowHeight = Reflection.intValue(listWidget, "itemHeight", rowHeight);
        }
        return rowTop + Math.max(0, (rowHeight - 20) / 2);
    }

    private static int currentRowTop(Object listWidget, Object entry, int index, int fallbackTop) {
        // AbstractSelectionList#getRowTop(index) is the authoritative layout
        // coordinate and already includes scrolling. Prefer it over entry fields,
        // which some 26.2 wrappers leave at zero until render time.
        if (listWidget != null) {
            Method rowTop = Reflection.findMethod(listWidget.getClass(), "getRowTop", 1);
            if (rowTop != null) {
                try {
                    Object value = rowTop.invoke(listWidget, index);
                    if (value instanceof Number n) return n.intValue();
                } catch (Throwable ignored) {}
            }
        }
        if (entry != null) {
            Object direct = Reflection.invokeQuiet(entry, "getY");
            if (direct instanceof Number n && n.intValue() != 0) return n.intValue();
            direct = Reflection.getField(entry, "y");
            if (direct instanceof Number n && n.intValue() != 0) return n.intValue();
        }
        if (listWidget != null) {
            double scroll = Reflection.doubleValue(listWidget, "getScrollAmount", 0);
            int itemHeight = Reflection.intValue(listWidget, "itemHeight", 36);
            int header = Reflection.intValue(listWidget, "headerHeight", 0);
            return fallbackTop + header + index * itemHeight - (int)Math.round(scroll);
        }
        return fallbackTop + index * 36;
    }

    static boolean isFavouriteEndpoint(Object client, String endpoint) {
        try {
            Object list = ServerFinderClient.ServerListBridge.createLoadedList(client);
            Object server = ServerFinderClient.ServerListBridge.findServer(list, endpoint);
            return ServerCategoryStore.isFavourite(endpoint) || server != null && isFavourite(server);
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean toggleFavouriteEndpoint(Object client, String endpoint, String suggestedName) throws Exception {
        if (endpoint == null || endpoint.isBlank()) throw new IllegalArgumentException("endpoint");
        Object list = ServerFinderClient.ServerListBridge.createLoadedList(client);
        Object server = ServerFinderClient.ServerListBridge.findServer(list, endpoint);
        if (server == null) {
            String name = suggestedName == null ? "" : suggestedName.trim();
            if (name.startsWith(FAV_PREFIX)) name = name.substring(FAV_PREFIX.length()).trim();
            if (name.isBlank()) name = "Zazu " + endpoint;
            server = ServerFinderClient.ServerListBridge.createServerData(name, endpoint);
            ServerFinderClient.ServerListBridge.addServerData(list, server);
            ServerCategoryStore.syncNew(List.of(endpoint));
        }

        String name = ServerFinderClient.ServerListBridge.serverName(server);
        boolean removingFavourite = name.startsWith(FAV_PREFIX) || ServerCategoryStore.isFavourite(endpoint);
        String updated = removingFavourite ? name.substring(FAV_PREFIX.length()) : FAV_PREFIX + name;
        ServerFinderClient.ServerListBridge.setServerName(server, updated);
        ServerFinderClient.ServerListBridge.save(list);
        ServerCategoryStore.setFavourite(endpoint, !removingFavourite);
        if (removingFavourite) ServerCategoryStore.promoteVerified(endpoint);
        return !removingFavourite;
    }

    private static void toggleFavourite(MultiplayerState state, ServerButtons sb) {
        try {
            Object list = ServerFinderClient.ServerListBridge.createLoadedList(state.client);
            Object server = ServerFinderClient.ServerListBridge.findServer(list, sb.endpoint);
            if (server == null) return;
            String name = ServerFinderClient.ServerListBridge.serverName(server);
            boolean removingFavourite = name.startsWith(FAV_PREFIX) || ServerCategoryStore.isFavourite(sb.endpoint);
            String updated = removingFavourite ? name.substring(FAV_PREFIX.length()) : FAV_PREFIX + name;
            ServerFinderClient.ServerListBridge.setServerName(server, updated);
            ServerFinderClient.ServerListBridge.save(list);
            ServerCategoryStore.setFavourite(sb.endpoint, !removingFavourite);
            if (removingFavourite) {
                // User preference: unfavouriting always promotes the server to the
                // established Servers category, even if it originally came from Scanned.
                ServerCategoryStore.promoteVerified(sb.endpoint);
                ServerTabsEntrypoint.requestViewAfterRefresh(state.screen, ServerCategoryStore.Tab.SERVERS);
            } else {
                ServerTabsEntrypoint.requestViewAfterRefresh(state.screen, ServerCategoryStore.Tab.FAVOURITES);
            }
            refreshMultiplayerScreen(state.client, state.screen);
            System.out.println("[Zazu's Server Seeker] Favourite toggled for " + sb.endpoint);
        } catch (Throwable t) {
            System.err.println("[Zazu's Server Seeker] Favourite toggle failed: " + Reflection.unwrap(t));
        }
    }

    private static void deleteSingle(MultiplayerState state, ServerButtons sb) {
        try {
            Object list = ServerFinderClient.ServerListBridge.createLoadedList(state.client);
            Object server = ServerFinderClient.ServerListBridge.findServer(list, sb.endpoint);
            if (server != null && isFavourite(server)) {
                System.out.println("[Zazu's Server Seeker] Delete blocked for favourite " + sb.endpoint);
                return;
            }
            if (ServerFinderClient.ServerListBridge.remove(state.client, sb.endpoint)) {
                refreshMultiplayerScreen(state.client, state.screen);
                System.out.println("[Zazu's Server Seeker] Quick deleted " + sb.endpoint);
            }
        } catch (Throwable t) {
            System.err.println("[Zazu's Server Seeker] Quick delete failed: " + Reflection.unwrap(t));
        }
    }

    private static void deleteAllPressed(MultiplayerState state) {
        long now = System.currentTimeMillis();
        if (now > state.deleteAllArmedUntil) {
            state.deleteAllArmedUntil = now + 3500;
            Reflection.setButtonText(state.deleteAllButton,
                    state.scannedDeleteMode ? "Confirm Delete Scanned" : "Confirm Delete Non-Favs");
            return;
        }
        state.deleteAllArmedUntil = 0;
        Reflection.setButtonText(state.deleteAllButton, bulkDeleteLabel(state));
        try {
            Object list = ServerFinderClient.ServerListBridge.createLoadedList(state.client);
            List<Object> servers = new ArrayList<>(ServerFinderClient.ServerListBridge.servers(list));
            int deleted = 0;
            for (Object server : servers) {
                if (isFavourite(server)) continue;
                String endpoint = ServerFinderClient.ServerListBridge.serverEndpoint(server);
                boolean scanned = ServerCategoryStore.isScanned(endpoint);
                if (state.scannedDeleteMode != scanned) continue;
                if (deleteFromLoadedList(list, server)) {
                    ServerCategoryStore.recordUndo(ServerFinderClient.ServerListBridge.serverName(server), endpoint);
                    ToolState.recordDeleted(endpoint);
                    ServerCategoryStore.remove(endpoint);
                    deleted++;
                }
            }
            ServerFinderClient.ServerListBridge.save(list);
            refreshMultiplayerScreen(state.client, state.screen);
            System.out.println("[Zazu's Server Seeker] Deleted " + deleted
                    + (state.scannedDeleteMode ? " scanned servers." : " non-favourite servers."));
        } catch (Throwable t) {
            System.err.println("[Zazu's Server Seeker] Bulk delete failed: " + Reflection.unwrap(t));
        }
    }

    private static String bulkDeleteLabel(MultiplayerState state) {
        return state.scannedDeleteMode ? "Delete All Scanned" : "Delete Non-Favourites";
    }

    private static boolean deleteFromLoadedList(Object list, Object server) throws Exception {
        List<Object> backing = ServerFinderClient.ServerListBridge.servers(list);
        return backing.remove(server);
    }

    private static String healthLabel(String endpoint) { int failures=ServerCategoryStore.healthFailures(endpoint); return failures==0?"H OK":"H "+failures+"/3"; }
    private static void undoLastDelete(MultiplayerState state) {
        if (ServerCategoryStore.undoLastDelete(state.client)) { refreshMultiplayerScreen(state.client,state.screen); System.out.println("[Zazu's Server Seeker] Restored last deleted server."); }
    }

    private static void updateDeleteAllConfirmation(MultiplayerState state) {
        if (state.deleteAllArmedUntil != 0 && System.currentTimeMillis() > state.deleteAllArmedUntil) {
            state.deleteAllArmedUntil = 0;
            Reflection.setButtonText(state.deleteAllButton, bulkDeleteLabel(state));
        }
    }

    private static void openFinder(MultiplayerState state) {
        try { ServerFinderClient.openOverlay(state.client, state.screen, state.width, state.height); }
        catch (Throwable t) { System.err.println("[Zazu's Server Seeker] Could not open finder: " + Reflection.unwrap(t)); }
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
                System.err.println("[Zazu's Server Seeker] ViaFabricPlus selection sync failed: " + Reflection.unwrap(t));
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
        ServerTabsEntrypoint.preserveCurrentViewAfterRefresh(screen);
        try {
            Method refresh = Reflection.findMethod(screen.getClass(), "refreshServerList", 0);
            if (refresh != null) {
                refresh.invoke(screen);
                ServerTabsEntrypoint.reapplyCurrentView(screen);
                rebuildRowButtons(screen);
                System.out.println("[Zazu's Server Seeker] Vanilla Multiplayer Refresh invoked and current category reapplied.");
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
        try {
            String endpoint = ServerFinderClient.ServerListBridge.serverEndpoint(serverData);
            return ServerFinderClient.ServerListBridge.serverName(serverData).startsWith(FAV_PREFIX)
                    || ServerCategoryStore.isFavourite(endpoint);
        } catch (Throwable ignored) {
            return ServerFinderClient.ServerListBridge.serverName(serverData).startsWith(FAV_PREFIX);
        }
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
        Object finderButton, deleteAllButton, undoButton;
        boolean disabledVanillaDelete, loggedTickFailure, loggedViaFailure;
        String lastViaEndpoint = "";
        long deleteAllArmedUntil;
        boolean scannedDeleteMode;
        MultiplayerState(Object client, Object screen, int width, int height) {
            this.client = client; this.screen = screen; this.width = width; this.height = height;
        }
    }

    static final class ServerButtons {
        final Object entry, serverData;
        final String endpoint;
        Object favourite, delete, health;
        ServerButtons(Object entry, Object serverData, String endpoint) { this.entry = entry; this.serverData = serverData; this.endpoint = endpoint; }
    }
}
