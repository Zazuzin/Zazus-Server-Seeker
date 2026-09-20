package dev.zazuzin.zst;

import net.fabricmc.api.ClientModInitializer;

import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;

/** Installs the Multiplayer-screen management controls. */
public final class MultiplayerManagementEntrypoint implements ClientModInitializer {
    static final String FAV_PREFIX = "★ ";
    private static final Map<Object, MultiplayerState> STATES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<Object> MANAGED_WIDGETS = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    @Override
    public void onInitializeClient() {
        BreakBlocksContributor.initialize();
        try {
            registerGlobalAfterInit();
            System.out.println("[Zazu's Server Seeker] Multiplayer management hook registered.");
        } catch (Throwable t) {
            System.err.println("[Zazu's Server Seeker] Multiplayer management hook failed to register: " + Reflection.unwrap(t));
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

        // The category hub owns the visible ordering. Sorting and saving the
        // complete servers.dat here did no visible work, and repeated that
        // synchronous disk pass for the hub and every category screen.

        state.finderButton = Reflection.makeButton("Zazu's Server Seeker", 6, Math.max(6, height - 28), 170, 20,
                b -> openFinder(state));
        Reflection.addWidget(screen, state.finderButton);
        MANAGED_WIDGETS.add(state.finderButton);

        state.deleteAllButton = Reflection.makeButton("Delete All Servers", 6, 6, 148, 20,
                b -> deleteAllPressed(state));
        Reflection.addWidget(screen, state.deleteAllButton);
        MANAGED_WIDGETS.add(state.deleteAllButton);
        state.undoButton = Reflection.makeButton("Undo Last Delete", 6, 30, 148, 20, b -> undoLastDelete(state));
        Reflection.addWidget(screen, state.undoButton); MANAGED_WIDGETS.add(state.undoButton);
        // ServerTabsEntrypoint owns category visibility. Start hidden so the
        // control cannot flash on the category hub before its first layout.
        Reflection.setBoolean(state.undoButton, "visible", false);
        Reflection.setBoolean(state.undoButton, "active", false);

        initializeRowButtonState(state);
        registerRowButtonMouseInterceptor(state);
        registerAfterTick(state);
        registerBeforeExtract(state);
        System.out.println("[Zazu's Server Seeker] Multiplayer controls installed. ViaFabricPlus integration: " + (ViaFabricPlusBridge.isAvailable() ? "available" : "not installed"));
    }

    private static void detachManagedWidgets(MultiplayerState state) {
        if (state == null) return;
        Reflection.removeWidget(state.screen, state.finderButton);
        Reflection.removeWidget(state.screen, state.deleteAllButton);
        Reflection.removeWidget(state.screen, state.undoButton);
        clearPerServerButtons(state);
    }

    private static void createPerServerButtons(MultiplayerState state) throws Exception {
        clearPerServerButtons(state);
        initializeRowButtonState(state);
        List<Object> entries = onlineServerEntries(state.screen);
        for (Object entry : entries) {
            Object data = getServerData(entry);
            if (data == null) continue;
            String endpoint = ServerFinderClient.ServerListBridge.serverEndpoint(data);
            if (!endpoint.isBlank()) state.serverButtons.add(new ServerButtons(entry, data, endpoint));
        }
        // Widgets are materialized only for the viewport-sized range below.
        // A large category therefore no longer creates four Minecraft widgets
        // for every saved server before its first frame can be displayed.
        updatePerServerButtons(state);
    }

    private static void initializeRowButtonState(MultiplayerState state) {
        state.listWidget = Reflection.getField(state.screen, "serverSelectionList", "serverList");
        state.geometryDirty = true;
        state.nextStructureValidationAt = System.currentTimeMillis() + 750L;
        state.lastScrollAmount = Double.NaN;
        state.lastVisibleStart = -1;
        state.lastVisibleEnd = -1;
        state.nextMaterializationIndex = 0;
    }

    private static void clearPerServerButtons(MultiplayerState state) {
        for (ServerButtons buttons : new ArrayList<>(state.serverButtons)) {
            removeRowControls(state, buttons);
        }
        state.serverButtons.clear();
        state.lastVisibleStart = -1;
        state.lastVisibleEnd = -1;
    }

    private static void removeRowControls(MultiplayerState state, ServerButtons buttons) {
        if (buttons == null) return;
        for (Object widget : new Object[]{buttons.notes, buttons.favourite, buttons.auth, buttons.delete}) {
            if (widget != null) Reflection.removeWidget(state.screen, widget);
        }
        buttons.notes = buttons.favourite = buttons.auth = buttons.delete = null;
    }

    private static void ensureRowControls(MultiplayerState state, ServerButtons sb) throws Exception {
        if (sb.notes != null) return;
        sb.notes = makeNotesButton(0, -100, b -> openNotes(state, sb));
        Reflection.setTooltip(sb.notes, "Notes");
        boolean favourite = isFavourite(sb.serverData);
        sb.favourite = Reflection.makeButton(favourite ? "★" : "☆", 0, -100, 20, 20, b -> toggleFavourite(state, sb));
        Reflection.setTooltip(sb.favourite, favourite ? "Unfavourite" : "Favourite");
        sb.auth = Reflection.makeButton(authRowLabel(sb.endpoint), 0, -100, 20, 20, b -> recheckAuth(state, sb));
        Reflection.setTooltip(sb.auth, authRowTooltip(sb.endpoint));
        sb.delete = makeDeleteButton(0, -100, b -> deleteSingle(state, sb));
        Reflection.setTooltip(sb.delete, favourite ? "Delete (unfavourite first)" : "Delete");
        for (Object widget : List.of(sb.notes, sb.favourite, sb.auth, sb.delete)) {
            Reflection.setBoolean(widget, "visible", false);
            Reflection.setBoolean(widget, "active", false);
            Reflection.addWidget(state.screen, widget);
            MANAGED_WIDGETS.add(widget);
        }
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
            double x = mouseCoordinate(mouse, "x"), y = mouseCoordinate(mouse, "y");
            try {
                // Ask the real Minecraft widgets to consume the click. This is
                // deliberately more robust than duplicating SDL button-number
                // checks here and keeps every configured callback on its native
                // Button.onPress path after Auto Join rebuilds the screen.
                if (dispatchManagedWidgetClick(state, mouse, x, y)) {
                    return Boolean.FALSE;
                }

                // Never intercept ordinary server-row clicks here. Minecraft's
                // OnlineServerEntry must receive them directly; the entry mixin
                // restores its own double-click fallback at that exact boundary.
            } catch (Throwable t) {
                System.err.println("[Zazu's Server Seeker] Row click handling failed: " + Reflection.unwrap(t));
            }
            return Boolean.TRUE;
        });
        RuntimeAccess.registerEvent(event, listener);
    }

    private static boolean dispatchManagedWidgetClick(MultiplayerState state, Object mouse, double x, double y) {
        if (mouse == null || Double.isNaN(x) || Double.isNaN(y)) return false;
        if (dispatchIfHit(state.deleteAllButton, mouse, x, y)) return true;
        if (dispatchIfHit(state.undoButton, mouse, x, y)) return true;
        for (ServerButtons buttons : state.serverButtons) {
            if (dispatchIfHit(buttons.notes, mouse, x, y)
                    || dispatchIfHit(buttons.favourite, mouse, x, y)
                    || dispatchIfHit(buttons.auth, mouse, x, y)
                    || dispatchIfHit(buttons.delete, mouse, x, y)) return true;
        }
        return false;
    }

    private static boolean dispatchIfHit(Object widget, Object mouse, double x, double y) {
        return visibleAndContains(widget, x, y) && ServerTabsEntrypoint.dispatchWidgetClick(widget, mouse);
    }

    private static double mouseCoordinate(Object event, String axis) {
        Object value = Reflection.invokeQuiet(event, axis);
        if (!(value instanceof Number)) value = Reflection.getField(event, axis);
        return value instanceof Number n ? n.doubleValue() : Double.NaN;
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

                int newWidth = Reflection.screenWidth(state.screen, state.width);
                int newHeight = Reflection.screenHeight(state.screen, state.height);
                if (newWidth != state.width || newHeight != state.height) {
                    state.width = newWidth;
                    state.height = newHeight;
                    state.geometryDirty = true;
                }

                updatePerServerButtons(state);
                if (state.deleteAllArmedUntil != 0L) updateDeleteAllConfirmation(state);

                long now = System.currentTimeMillis();
                if (now >= state.nextSelectionSyncAt) {
                    state.nextSelectionSyncAt = now + 200L;
                    syncViaFabricPlusForSelected(state);
                    protectVanillaDeleteButton(state);
                }
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

    private static void registerBeforeExtract(MultiplayerState state) {
        try {
            Class<?> screenEvents = Class.forName("net.fabricmc.fabric.api.client.screen.v1.ScreenEvents");
            Object beforeExtractEvent = null;
            for (Method m : screenEvents.getMethods()) {
                if (Modifier.isStatic(m.getModifiers()) && m.getName().equals("beforeExtract") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isInstance(state.screen)) {
                    beforeExtractEvent = m.invoke(null, state.screen);
                    break;
                }
            }
            if (beforeExtractEvent == null) {
                System.out.println("[Zazu's Server Seeker] Per-frame row alignment unavailable; using tick fallback.");
                return;
            }
            Class<?> callback = Class.forName("net.fabricmc.fabric.api.client.screen.v1.ScreenEvents$BeforeExtract");
            Object listener = Proxy.newProxyInstance(callback.getClassLoader(), new Class<?>[]{callback}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) return Reflection.objectMethod(proxy, method, args);
                try {
                    if (STATES.get(state.screen) != state) return null;
                    if (Reflection.currentScreen(state.client) != state.screen) return null;
                    updateRowPositionsOnly(state);
                } catch (Throwable t) {
                    if (!state.loggedRenderFailure) {
                        state.loggedRenderFailure = true;
                        System.err.println("[Zazu's Server Seeker] Per-frame row alignment failed: " + Reflection.unwrap(t));
                    }
                }
                return null;
            });
            registerEventObject(beforeExtractEvent, listener);
        } catch (Throwable t) {
            System.out.println("[Zazu's Server Seeker] Per-frame row alignment unavailable; using tick fallback: " + Reflection.unwrap(t));
        }
    }

    /**
     * Cheap geometry-only pass used after every rendered frame. It intentionally
     * avoids server-list rescans, auth probing, favourite persistence checks and
     * tooltip reconstruction. Those remain in the throttled/tick maintenance path.
     */
    private static void updateRowPositionsOnly(MultiplayerState state) throws Exception {
        if (state == null || !ServerTabsEntrypoint.isServerListView(state.screen)) return;
        Object listWidget = state.listWidget;
        if (listWidget == null) return;

        boolean finderOpen = ServerFinderClient.isOverlayOpen(state.screen);
        boolean authEnabled = ToolState.authDetectionEnabled;
        int totalWidth = authEnabled ? 20 * 4 + 3 * 3 : 20 * 3 + 3 * 2;
        int centeredRowLeft = Math.max(6, (state.width - state.cachedRowWidth) / 2);
        VisibleRange visible = visibleRange(state, listWidget);
        int first = state.lastVisibleStart < 0 ? visible.first()
                : visible.first() < 0 ? state.lastVisibleStart : Math.min(visible.first(), state.lastVisibleStart);
        int last = Math.max(visible.last(), state.lastVisibleEnd);

        for (int i = Math.max(0, first); i <= last && i < state.serverButtons.size(); i++) {
            ServerButtons sb = state.serverButtons.get(i);
            if (!visible.contains(i)) {
                hideRowControls(sb);
                continue;
            }
            ensureRowControls(state, sb);
            int top = currentRowTop(listWidget, sb.entry, i, state.cachedListTop);
            int controlY = currentRowControlY(listWidget, sb.entry, top);
            boolean onScreen = controlY >= state.cachedListTop && controlY + 20 <= state.cachedListBottom;
            boolean show = !finderOpen && onScreen;
            boolean authShow = show && authEnabled;

            if (!Objects.equals(sb.lastShow, show)) {
                sb.lastShow = show;
                Reflection.setBoolean(sb.notes, "visible", show);
                Reflection.setBoolean(sb.favourite, "visible", show);
                Reflection.setBoolean(sb.delete, "visible", show);
                Reflection.setBoolean(sb.notes, "active", show);
                Reflection.setBoolean(sb.favourite, "active", show);
            }
            if (!Objects.equals(sb.lastAuthShow, authShow)) {
                sb.lastAuthShow = authShow;
                Reflection.setBoolean(sb.auth, "visible", authShow);
            }
            boolean authActive = authShow && !ServerAuthService.isChecking(sb.endpoint);
            if (!Objects.equals(sb.lastAuthActive, authActive)) {
                sb.lastAuthActive = authActive;
                Reflection.setBoolean(sb.auth, "active", authActive);
            }
            boolean favourite = Boolean.TRUE.equals(sb.lastFavourite);
            boolean deleteActive = show && !favourite;
            if (!Objects.equals(sb.lastDeleteActive, deleteActive)) {
                sb.lastDeleteActive = deleteActive;
                Reflection.setBoolean(sb.delete, "active", deleteActive);
            }

            int actualRowLeft = currentRowLeft(listWidget, sb.entry, state.cachedRowLeft);
            boolean implausibleLeft = actualRowLeft < totalWidth + 12
                    || actualRowLeft + Math.max(1, state.cachedRowWidth) > state.width + 4;
            if (implausibleLeft) actualRowLeft = centeredRowLeft;
            int rowRight = actualRowLeft + state.cachedRowWidth;
            int notesX = Math.min(Math.max(6, state.width - totalWidth - 6),
                    Math.max(rowRight + 16, state.cachedScrollbarX + 16));
            int favX = notesX + 23;
            int authX = favX + 23;
            int deleteX = authEnabled ? authX + 23 : favX + 23;

            if (sb.lastControlY != controlY || sb.lastNotesX != notesX) {
                Reflection.setPosition(sb.notes, notesX, controlY);
                sb.lastNotesX = notesX;
            }
            if (sb.lastControlY != controlY || sb.lastFavouriteX != favX) {
                Reflection.setPosition(sb.favourite, favX, controlY);
                sb.lastFavouriteX = favX;
            }
            if (sb.lastControlY != controlY || sb.lastAuthX != authX) {
                Reflection.setPosition(sb.auth, authX, controlY);
                sb.lastAuthX = authX;
            }
            if (sb.lastControlY != controlY || sb.lastDeleteX != deleteX) {
                Reflection.setPosition(sb.delete, deleteX, controlY);
                sb.lastDeleteX = deleteX;
            }
            sb.lastControlY = controlY;
        }
        state.lastVisibleStart = visible.first();
        state.lastVisibleEnd = visible.last();
    }

    /**
     * Returns a small candidate window around the viewport. Exact row bounds are
     * still checked before controls are shown, but off-screen rows no longer pay
     * for reflective position lookups every frame.
     */
    private static VisibleRange visibleRange(MultiplayerState state, Object listWidget) {
        int total = state == null ? 0 : state.serverButtons.size();
        if (total == 0 || listWidget == null) return VisibleRange.EMPTY;
        int itemHeight = Math.max(1, Reflection.intValue(listWidget, "itemHeight", 36));
        // Use Minecraft's authoritative row coordinates instead of deriving an
        // index solely from getScrollAmount(). Some compatible list
        // implementations expose a different scroll accessor and otherwise
        // materialize only the rows visible when the category first opens.
        int first = total;
        int low = 0, high = total - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            ServerButtons row = state.serverButtons.get(middle);
            int top = currentRowTop(listWidget, row.entry, middle, state.cachedListTop);
            if (top + itemHeight >= state.cachedListTop) {
                first = middle;
                high = middle - 1;
            } else {
                low = middle + 1;
            }
        }
        if (first >= total) return VisibleRange.EMPTY;

        int last = -1;
        low = first;
        high = total - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            ServerButtons row = state.serverButtons.get(middle);
            int top = currentRowTop(listWidget, row.entry, middle, state.cachedListTop);
            if (top <= state.cachedListBottom) {
                last = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        if (last < first) return VisibleRange.EMPTY;
        return new VisibleRange(Math.max(0, first - 2), Math.min(total - 1, last + 2));
    }

    private static void hideRowControls(ServerButtons sb) {
        if (sb == null || sb.notes == null) return;
        if (!Boolean.FALSE.equals(sb.lastShow)) {
            sb.lastShow = false;
            Reflection.setBoolean(sb.notes, "visible", false);
            Reflection.setBoolean(sb.favourite, "visible", false);
            Reflection.setBoolean(sb.delete, "visible", false);
            Reflection.setBoolean(sb.notes, "active", false);
            Reflection.setBoolean(sb.favourite, "active", false);
        }
        if (!Boolean.FALSE.equals(sb.lastAuthShow)) {
            sb.lastAuthShow = false;
            Reflection.setBoolean(sb.auth, "visible", false);
        }
        if (!Boolean.FALSE.equals(sb.lastAuthActive)) {
            sb.lastAuthActive = false;
            Reflection.setBoolean(sb.auth, "active", false);
        }
        if (!Boolean.FALSE.equals(sb.lastDeleteActive)) {
            sb.lastDeleteActive = false;
            Reflection.setBoolean(sb.delete, "active", false);
        }
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
        ArrayList<Object> out = new ArrayList<>(state.serverButtons.size() * 2);
        for (ServerButtons buttons : state.serverButtons) {
            if (buttons.favourite != null) out.add(buttons.favourite);
            if (buttons.delete != null) out.add(buttons.delete);
        }
        return out;
    }

    /** All native per-row controls. rowWidgets intentionally remains Favourite/Delete pairs. */
    static List<Object> allRowWidgets(Object screen) {
        MultiplayerState state = STATES.get(screen);
        if (state == null) return List.of();
        ArrayList<Object> out = new ArrayList<>(state.serverButtons.size() * 4);
        for (ServerButtons buttons : state.serverButtons) {
            if (buttons.notes != null) out.add(buttons.notes);
            if (buttons.favourite != null) out.add(buttons.favourite);
            if (buttons.auth != null) out.add(buttons.auth);
            if (buttons.delete != null) out.add(buttons.delete);
        }
        return out;
    }

    static void rebuildRowButtons(Object screen) {
        MultiplayerState state = STATES.get(screen);
        if (state == null) return;
        try { createPerServerButtons(state); } catch (Throwable ignored) {}
    }

    static void clearRowButtons(Object screen) {
        MultiplayerState state = STATES.get(screen);
        if (state == null) return;
        clearPerServerButtons(state);
        initializeRowButtonState(state);
    }

    private static void updatePerServerButtons(MultiplayerState state) throws Exception {
        if (!ServerTabsEntrypoint.isServerListView(state.screen)) return;

        long now = System.currentTimeMillis();
        boolean finderOpen = ServerFinderClient.isOverlayOpen(state.screen);
        boolean authEnabled = ToolState.authDetectionEnabled;

        // The screen/category code explicitly rebuilds row controls whenever the
        // list changes. Keep a low-frequency safety validation for changes made
        // by other mods instead of rescanning every server row 20 times/second.
        if (now >= state.nextStructureValidationAt) {
            state.nextStructureValidationAt = now + 750L;
            List<Object> entries = onlineServerEntries(state.screen);
            if (entries.size() != state.serverButtons.size()) {
                createPerServerButtons(state);
                return;
            }
            for (int i = 0; i < entries.size(); i++) {
                Object data = getServerData(entries.get(i));
                String endpoint = data == null ? "" : ServerFinderClient.ServerListBridge.serverEndpoint(data);
                if (!ToolState.normalize(endpoint).equals(ToolState.normalize(state.serverButtons.get(i).endpoint))) {
                    createPerServerButtons(state);
                    return;
                }
            }
        }

        Object listWidget = state.listWidget;
        if (listWidget == null) {
            listWidget = Reflection.getField(state.screen, "serverSelectionList", "serverList");
            state.listWidget = listWidget;
            state.geometryDirty = true;
        }

        boolean geometryRefresh = state.geometryDirty || now >= state.nextGeometryRefreshAt;
        if (geometryRefresh) {
            state.nextGeometryRefreshAt = now + 1_000L;
            state.geometryDirty = false;
            state.cachedListTop = listWidget == null ? 32
                    : Reflection.intValue(listWidget, "getY", Reflection.intValue(listWidget, "y0", 32));
            int rawBottom = listWidget == null ? state.height - 64
                    : state.cachedListTop + Reflection.intValue(listWidget, "getHeight", state.height - state.cachedListTop - 64);
            state.cachedListBottom = Math.min(rawBottom, vanillaFooterTop(state) - 4);
            state.cachedRowLeft = listWidget == null ? state.width / 2 - 154
                    : Reflection.intValue(listWidget, "getRowLeft", state.width / 2 - 154);
            state.cachedRowWidth = listWidget == null ? 308 : Reflection.intValue(listWidget, "getRowWidth", 308);
            state.cachedScrollbarX = listWidget == null ? state.cachedRowLeft + state.cachedRowWidth + 4
                    : Reflection.intValue(listWidget, "getScrollbarPosition", state.cachedRowLeft + state.cachedRowWidth + 4);
        }

        double scroll = listWidget == null ? 0.0 : Reflection.doubleValue(listWidget, "getScrollAmount", 0.0);
        // Always keep a tick-rate fallback for row positioning. A separate
        // after-render pass below updates the same coordinates every rendered
        // frame, but this path still keeps controls aligned if another mod or
        // Fabric version does not expose ScreenEvents.afterRender.
        boolean positionRefresh = true;
        state.lastScrollAmount = scroll;
        state.lastFinderOpen = finderOpen;
        state.lastAuthEnabled = authEnabled;

        int totalWidth = authEnabled ? 20 * 4 + 3 * 3 : 20 * 3 + 3 * 2;
        int centeredRowLeft = Math.max(6, (state.width - state.cachedRowWidth) / 2);
        VisibleRange visible = visibleRange(state, listWidget);
        int first = state.lastVisibleStart < 0 ? visible.first()
                : visible.first() < 0 ? state.lastVisibleStart : Math.min(visible.first(), state.lastVisibleStart);
        int last = Math.max(visible.last(), state.lastVisibleEnd);

        for (int i = Math.max(0, first); i <= last && i < state.serverButtons.size(); i++) {
            ServerButtons sb = state.serverButtons.get(i);
            if (!visible.contains(i)) {
                hideRowControls(sb);
                continue;
            }
            ensureRowControls(state, sb);

            boolean fav = ServerCategoryStore.isFavourite(sb.endpoint);
            if (sb.lastFavourite == null || sb.lastFavourite != fav) {
                sb.lastFavourite = fav;
                Reflection.setButtonText(sb.favourite, fav ? "★" : "☆");
                Reflection.setTooltip(sb.favourite, fav ? "Unfavourite" : "Favourite");
                Reflection.setTooltip(sb.delete, fav ? "Delete (unfavourite first)" : "Delete");
            }

            String authLabel = authRowLabel(sb.endpoint);
            String authTooltip = authRowTooltip(sb.endpoint);
            if (!authLabel.equals(sb.lastAuthLabel)) {
                sb.lastAuthLabel = authLabel;
                Reflection.setButtonText(sb.auth, authLabel);
            }
            if (!authTooltip.equals(sb.lastAuthTooltip)) {
                sb.lastAuthTooltip = authTooltip;
                Reflection.setTooltip(sb.auth, authTooltip);
            }

            if (positionRefresh || sb.lastShow == null) {
                int top = currentRowTop(listWidget, sb.entry, i, state.cachedListTop);
                int controlY = currentRowControlY(listWidget, sb.entry, top);
                boolean onScreen = controlY >= state.cachedListTop && controlY + 20 <= state.cachedListBottom;
                boolean show = !finderOpen && onScreen;
                boolean authShow = show && authEnabled;
                boolean deleteActive = show && !fav;

                if (!Objects.equals(sb.lastShow, show)) {
                    sb.lastShow = show;
                    Reflection.setBoolean(sb.notes, "visible", show);
                    Reflection.setBoolean(sb.favourite, "visible", show);
                    Reflection.setBoolean(sb.delete, "visible", show);
                    Reflection.setBoolean(sb.notes, "active", show);
                    Reflection.setBoolean(sb.favourite, "active", show);
                }
                boolean authActive = authShow && !ServerAuthService.isChecking(sb.endpoint);
                if (!Objects.equals(sb.lastAuthShow, authShow)) {
                    sb.lastAuthShow = authShow;
                    Reflection.setBoolean(sb.auth, "visible", authShow);
                }
                if (!Objects.equals(sb.lastAuthActive, authActive)) {
                    sb.lastAuthActive = authActive;
                    Reflection.setBoolean(sb.auth, "active", authActive);
                }
                if (!Objects.equals(sb.lastDeleteActive, deleteActive)) {
                    sb.lastDeleteActive = deleteActive;
                    Reflection.setBoolean(sb.delete, "active", deleteActive);
                }

                int actualRowLeft = currentRowLeft(listWidget, sb.entry, state.cachedRowLeft);
                boolean implausibleLeft = actualRowLeft < totalWidth + 12
                        || actualRowLeft + Math.max(1, state.cachedRowWidth) > state.width + 4;
                if (implausibleLeft) actualRowLeft = centeredRowLeft;
                int rowRight = actualRowLeft + state.cachedRowWidth;
                int notesX = Math.min(Math.max(6, state.width - totalWidth - 6),
                        Math.max(rowRight + 16, state.cachedScrollbarX + 16));
                int favX = notesX + 23;
                int authX = favX + 23;
                int deleteX = authEnabled ? authX + 23 : favX + 23;

                if (sb.lastControlY != controlY || sb.lastNotesX != notesX) {
                    Reflection.setPosition(sb.notes, notesX, controlY);
                    sb.lastNotesX = notesX;
                }
                if (sb.lastControlY != controlY || sb.lastFavouriteX != favX) {
                    Reflection.setPosition(sb.favourite, favX, controlY);
                    sb.lastFavouriteX = favX;
                }
                if (sb.lastControlY != controlY || sb.lastAuthX != authX) {
                    Reflection.setPosition(sb.auth, authX, controlY);
                    sb.lastAuthX = authX;
                }
                if (sb.lastControlY != controlY || sb.lastDeleteX != deleteX) {
                    Reflection.setPosition(sb.delete, deleteX, controlY);
                    sb.lastDeleteX = deleteX;
                }
                sb.lastControlY = controlY;
            } else {
                // Favourite state can change without a list rebuild in unusual
                // integrations. Keep Delete's active state correct without doing
                // any geometry work.
                boolean deleteActive = Boolean.TRUE.equals(sb.lastShow) && !fav;
                if (!Objects.equals(sb.lastDeleteActive, deleteActive)) {
                    sb.lastDeleteActive = deleteActive;
                    Reflection.setBoolean(sb.delete, "active", deleteActive);
                }
            }
        }
        state.lastVisibleStart = visible.first();
        state.lastVisibleEnd = visible.last();
        materializeNextRowControl(state);
    }

    /**
     * Completes one off-screen row per tick. Visible rows are still created
     * immediately, while this bounded fallback guarantees every saved row has
     * controls even when another mod supplies unusual list geometry.
     */
    private static void materializeNextRowControl(MultiplayerState state) throws Exception {
        while (state.nextMaterializationIndex < state.serverButtons.size()) {
            ServerButtons next = state.serverButtons.get(state.nextMaterializationIndex++);
            if (next.notes == null) {
                ensureRowControls(state, next);
                return;
            }
        }
    }

    private static void openNotes(MultiplayerState state, ServerButtons sb) {
        try {
            Class<?> normalizer = Class.forName("dev.zazu.servernotes.util.ServerAddressNormalizer");
            Method normalize = normalizer.getMethod("normalize", String.class);
            Object identity = normalize.invoke(null, sb.endpoint);

            Class<?> bootstrap = Class.forName("dev.zazu.servernotes.client.ZazusServerNotesClient");
            Object app = bootstrap.getMethod("app").invoke(null);
            Object store = Reflection.invokeQuiet(app, "store");
            Method getOrCreate = Reflection.findCompatibleMethod(store.getClass(), "getOrCreate", identity);
            if (getOrCreate == null) throw new NoSuchMethodException("ServerProfileStore.getOrCreate(ServerIdentity)");
            Object profile = getOrCreate.invoke(store, identity);
            Object keyValue = Reflection.invokeQuiet(profile, "key");
            String key = keyValue == null ? "" : keyValue.toString();
            if (key.isBlank()) throw new IllegalStateException("Server Notes profile key is blank");

            Class<?> notesScreen = Class.forName("dev.zazu.servernotes.ui.ServerNotesScreen");
            Object screen = null;
            for (Constructor<?> constructor : notesScreen.getConstructors()) {
                Class<?>[] types = constructor.getParameterTypes();
                if (types.length != 2 || types[1] != String.class) continue;
                if (state.screen != null && !types[0].isInstance(state.screen)) continue;
                screen = constructor.newInstance(state.screen, key);
                break;
            }
            if (screen == null) throw new NoSuchMethodException("ServerNotesScreen(Screen,String)");
            ScreenCompat.setScreen(state.client, screen);
        } catch (Throwable t) {
            System.err.println("[Zazu's Server Seeker] Could not open Server Notes for " + sb.endpoint + ": " + Reflection.unwrap(t));
        }
    }

    private static Object makeNotesButton(int x, int y, Consumer<Object> pressed) throws Exception {
        return makeSpriteButton(x, y, "Notes", "book", "N", pressed);
    }

    private static Object makeDeleteButton(int x, int y, Consumer<Object> pressed) throws Exception {
        return makeSpriteButton(x, y, "Delete", "trash", "X", pressed);
    }

    private static Object makeSpriteButton(int x, int y, String label, String spritePath,
                                           String fallbackLabel, Consumer<Object> pressed) throws Exception {
        try {
            Class<?> spriteButtonClass = Class.forName("net.minecraft.client.gui.components.SpriteIconButton");
            Class<?> buttonClass = Class.forName("net.minecraft.client.gui.components.Button");
            Class<?> onPressClass = null;
            for (Class<?> nested : buttonClass.getDeclaredClasses()) {
                if (nested.getSimpleName().equals("OnPress")) { onPressClass = nested; break; }
            }
            if (onPressClass == null) throw new ClassNotFoundException("Button.OnPress");
            Object callback = Proxy.newProxyInstance(onPressClass.getClassLoader(), new Class<?>[]{onPressClass}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) return Reflection.objectMethod(proxy, method, args);
                pressed.accept(args != null && args.length > 0 ? args[0] : null);
                return null;
            });
            Object message = Reflection.literal(label);
            Method builderMethod = Reflection.findStaticCompatible(spriteButtonClass, "builder", message, callback, Boolean.TRUE);
            if (builderMethod == null) throw new NoSuchMethodException("SpriteIconButton.builder(Component, OnPress, boolean)");
            Object builder = builderMethod.invoke(null, message, callback, true);
            Method size = Reflection.findCompatibleMethod(builder.getClass(), "size", 20, 20);
            if (size != null) size.invoke(builder, 20, 20);
            Class<?> identifierClass = Class.forName("net.minecraft.resources.Identifier");
            Object sprite = identifierClass.getMethod("fromNamespaceAndPath", String.class, String.class)
                    .invoke(null, "zazus_server_notes", spritePath);
            Method spriteMethod = Reflection.findCompatibleMethod(builder.getClass(), "sprite", sprite, 14, 14);
            if (spriteMethod == null) throw new NoSuchMethodException("SpriteIconButton.Builder.sprite(Identifier,int,int)");
            spriteMethod.invoke(builder, sprite, 14, 14);
            Method tooltip = Reflection.findCompatibleMethod(builder.getClass(), "tooltip", message);
            if (tooltip != null) tooltip.invoke(builder, message);
            Method build = Reflection.findMethod(builder.getClass(), "build", 0);
            if (build == null) throw new NoSuchMethodException("SpriteIconButton.Builder.build()");
            Object button = build.invoke(builder);
            Reflection.setPosition(button, x, y);
            return button;
        } catch (Throwable ignored) {
            Object fallback = Reflection.makeButton(fallbackLabel, x, y, 20, 20, pressed);
            Reflection.setTooltip(fallback, label);
            return fallback;
        }
    }

    private static void recheckAuth(MultiplayerState state, ServerButtons sb) {
        if (state == null || sb == null || !ToolState.authDetectionEnabled || ServerAuthService.isChecking(sb.endpoint)) return;
        int protocol = ToolState.protocolFor(sb.endpoint);
        boolean started = ServerAuthService.recheckAsync(state.client, sb.endpoint, protocol, () -> {
            sb.lastAuthLabel = "";
            sb.lastAuthTooltip = "";
            sb.lastAuthActive = null;
            state.geometryDirty = true;
            try { updatePerServerButtons(state); }
            catch (Throwable t) {
                System.err.println("[Zazu's Server Seeker] Could not refresh auth button after recheck: " + Reflection.unwrap(t));
            }
        });
        if (!started) return;
        sb.lastAuthLabel = "";
        sb.lastAuthTooltip = "";
        sb.lastAuthActive = null;
        state.geometryDirty = true;
        try { updatePerServerButtons(state); }
        catch (Throwable t) {
            System.err.println("[Zazu's Server Seeker] Could not show auth recheck state: " + Reflection.unwrap(t));
        }
    }

    private static String authRowLabel(String endpoint) {
        if (!ToolState.authDetectionEnabled) return "-";
        return ServerAuthService.shortLabel(endpoint);
    }

    private static String authRowTooltip(String endpoint) {
        if (!ToolState.authDetectionEnabled) return "Authentication: Disabled";
        String label = ServerAuthService.shortLabel(endpoint);
        return switch (label) {
            case "M" -> "Authentication: Microsoft — Click to recheck";
            case "C" -> "Authentication: Cracked — Click to recheck";
            case "?" -> "Authentication: Unknown — Click to recheck";
            case "…" -> "Authentication: Checking…";
            default -> "Authentication: Not checked — Click to check";
        };
    }

    private static int vanillaFooterTop(MultiplayerState state) throws Exception {
        int footerTop = state.height - 52;
        for (Object widget : Reflection.widgets(state.screen)) {
            if (isManagedWidget(widget)) continue;
            String label = widgetLabel(widget).trim().toLowerCase(Locale.ROOT);
            if (!(label.equals("join server") || label.equals("direct connection") || label.equals("add server")
                    || label.equals("edit") || label.equals("delete") || label.equals("refresh") || label.equals("back"))) continue;
            int y = Reflection.intValue(widget, "getY", Reflection.intValue(widget, "y", state.height));
            if (y > state.height / 2) footerTop = Math.min(footerTop, y);
        }
        return footerTop;
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
        // which some compatible list wrappers leave at zero until render time.
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
        String updated = removingFavourite && name.startsWith(FAV_PREFIX)
                ? name.substring(FAV_PREFIX.length()) : removingFavourite ? name : FAV_PREFIX + name;
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
            String updated = removingFavourite && name.startsWith(FAV_PREFIX)
                    ? name.substring(FAV_PREFIX.length()) : removingFavourite ? name : FAV_PREFIX + name;
            ServerFinderClient.ServerListBridge.setServerName(server, updated);
            // Keep every source-list and rendered-row copy in sync before any
            // category refresh. A filtered screen can hold more than one
            // ServerData instance for the same endpoint.
            ServerListAccess.synchronizeServerName(state.screen, sb.endpoint, updated);
            if (sb.serverData != server) ServerFinderClient.ServerListBridge.setServerName(sb.serverData, updated);
            ServerFinderClient.ServerListBridge.save(list);
            ServerCategoryStore.setFavourite(sb.endpoint, !removingFavourite);
            if (removingFavourite) {
                // User preference: unfavouriting always promotes the server to the
                // established Servers category, even if it originally came from Scanned.
                ServerCategoryStore.promoteVerified(sb.endpoint);
                // Stay in Favourites. Recreating this category removes only the
                // clicked row from the filtered view and never exposes the
                // Servers-only Auto Join / Delete All controls.
                ServerTabsEntrypoint.requestViewAfterRefresh(state.screen, ServerCategoryStore.Tab.FAVOURITES);
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
                    state.scannedDeleteMode ? "Confirm Delete Scanned" : "Confirm Delete Servers");
            return;
        }
        state.deleteAllArmedUntil = 0;
        Reflection.setButtonText(state.deleteAllButton, bulkDeleteLabel(state));
        try {
            Object list = ServerFinderClient.ServerListBridge.createLoadedList(state.client);
            List<Object> servers = new ArrayList<>(ServerFinderClient.ServerListBridge.servers(list));
            List<ServerCategoryStore.DeletedServer> undoBatch = new ArrayList<>();
            int deleted = 0;
            for (Object server : servers) {
                if (isFavourite(server)) continue;
                String endpoint = ServerFinderClient.ServerListBridge.serverEndpoint(server);
                boolean scanned = ServerCategoryStore.isScanned(endpoint);
                if (state.scannedDeleteMode != scanned) continue;
                if (deleteFromLoadedList(list, server)) {
                    undoBatch.add(new ServerCategoryStore.DeletedServer(
                            ServerFinderClient.ServerListBridge.serverName(server), endpoint,
                            ServerCategoryStore.isFavourite(endpoint), scanned));
                    ToolState.recordDeleted(endpoint);
                    ServerCategoryStore.remove(endpoint);
                    deleted++;
                }
            }
            ServerCategoryStore.recordUndoBatch(undoBatch);
            ServerFinderClient.ServerListBridge.save(list);
            refreshMultiplayerScreen(state.client, state.screen);
            System.out.println("[Zazu's Server Seeker] Deleted " + deleted
                    + (state.scannedDeleteMode ? " scanned servers." : " established servers."));
        } catch (Throwable t) {
            System.err.println("[Zazu's Server Seeker] Bulk delete failed: " + Reflection.unwrap(t));
        }
    }

    private static String bulkDeleteLabel(MultiplayerState state) {
        return state.scannedDeleteMode ? "Delete All Scanned" : "Delete All Servers";
    }

    private static boolean deleteFromLoadedList(Object list, Object server) throws Exception {
        List<Object> backing = ServerFinderClient.ServerListBridge.servers(list);
        return backing.remove(server);
    }

    private static void undoLastDelete(MultiplayerState state) {
        if (!ServerCategoryStore.undoLastDelete(state.client)) return;
        refreshMultiplayerScreen(state.client, state.screen);
        System.out.println("[Zazu's Server Seeker] Restored last deleted server.");
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
        if (ServerTabsEntrypoint.refreshCurrentScreen(screen)) {
            System.out.println("[Zazu's Server Seeker] Refreshed current category in place.");
            return;
        }
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

    private static void protectVanillaDeleteButton(MultiplayerState state) {
        try {
            Object selected = Reflection.invokeQuiet(state.screen, "getSelected");
            Object data = selected == null ? null : getServerData(selected);
            String endpoint = data == null ? "" : ServerFinderClient.ServerListBridge.serverEndpoint(data);
            boolean fav = !endpoint.isBlank() && ServerCategoryStore.isFavourite(endpoint);
            String protectionKey = ToolState.normalize(endpoint) + "|" + fav;
            if (protectionKey.equals(state.lastDeleteProtectionKey)) return;
            state.lastDeleteProtectionKey = protectionKey;

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

    /**
     * Called from the Minecraft server-entry mixin before native row joining.
     * Keeping connection context here preserves cleanup and ViaFabricPlus
     * behavior without intercepting the row's mouse event.
     */
    public static String prepareNativeServerEntryClick(Object serverData) {
        if (serverData == null) return "";
        String endpoint;
        try {
            endpoint = ServerFinderClient.ServerListBridge.serverEndpoint(serverData);
        } catch (Throwable ignored) {
            return "";
        }
        if (endpoint == null || endpoint.isBlank()) return "";
        WhitelistAutoDeleteEntrypoint.noteAttempt(endpoint);
        applyViaFabricPlusForEndpoint(endpoint);
        return endpoint;
    }

    static String selectedEndpoint(Object multiplayerScreen) {
        Object list = Reflection.getField(multiplayerScreen, "serverSelectionList", "serverList");
        Object selected = Reflection.invokeQuiet(list, "getSelected");
        Object data = selected == null ? null : getServerData(selected);
        if (data == null) return "";
        try { return ServerFinderClient.ServerListBridge.serverEndpoint(data); } catch (Throwable ignored) { return ""; }
    }

    static final class MultiplayerState {
        final Object client, screen;
        int width, height;
        final List<ServerButtons> serverButtons = new ArrayList<>();
        Object finderButton, deleteAllButton, undoButton, listWidget;
        boolean disabledVanillaDelete, loggedTickFailure, loggedRenderFailure, loggedViaFailure,
                geometryDirty = true;
        boolean lastFinderOpen, lastAuthEnabled;
        String lastViaEndpoint = "", lastDeleteProtectionKey = "";
        long deleteAllArmedUntil, nextStructureValidationAt, nextGeometryRefreshAt, nextSelectionSyncAt;
        boolean scannedDeleteMode;
        double lastScrollAmount = Double.NaN;
        int cachedListTop = 32, cachedListBottom, cachedRowLeft, cachedRowWidth = 308, cachedScrollbarX;
        int lastVisibleStart = -1, lastVisibleEnd = -1, nextMaterializationIndex;
        MultiplayerState(Object client, Object screen, int width, int height) {
            this.client = client; this.screen = screen; this.width = width; this.height = height;
            this.cachedListBottom = Math.max(32, height - 64);
        }
    }

    static final class ServerButtons {
        final Object entry, serverData;
        final String endpoint;
        Object notes, favourite, auth, delete;
        Boolean lastShow, lastAuthShow, lastAuthActive, lastDeleteActive, lastFavourite;
        String lastAuthLabel = "", lastAuthTooltip = "";
        int lastNotesX = Integer.MIN_VALUE, lastFavouriteX = Integer.MIN_VALUE;
        int lastAuthX = Integer.MIN_VALUE, lastDeleteX = Integer.MIN_VALUE, lastControlY = Integer.MIN_VALUE;
        ServerButtons(Object entry, Object serverData, String endpoint) { this.entry = entry; this.serverData = serverData; this.endpoint = endpoint; }
    }

    private record VisibleRange(int first, int last) {
        private static final VisibleRange EMPTY = new VisibleRange(-1, -1);
        boolean contains(int index) { return first >= 0 && index >= first && index <= last; }
    }
}
