package dev.zazuzin.zst;

import net.fabricmc.api.ClientModInitializer;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Sequential Auto Join engine.
 *
 * UI ownership deliberately lives in {@link ServerTabsEntrypoint}; this class
 * contains only connection sequencing/state and therefore has no hidden or
 * unused Multiplayer button implementation.
 */
public final class AutoJoinEntrypoint implements ClientModInitializer {
    private static final String FAV_PREFIX = "★ ";
    private static final Set<String> ATTEMPTED = Collections.synchronizedSet(new LinkedHashSet<>());
    private static final Set<Object> CANCEL_WATCHED = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static final long STABLE_JOIN_MS = 8_000L;

    private static volatile boolean enabled = loadEnabled();
    private static volatile boolean joinInProgress;
    private static volatile boolean enteredPlay;
    private static volatile String lastAutoJoinEndpoint = "";
    private static volatile long playEnteredAt;
    private static volatile long endReachedSince;

    @Override
    public void onInitializeClient() {
        try {
            registerScreenWatcher();
            registerPlayJoinWatcher();
            registerPlayDisconnectWatcher();
            System.out.println("[Zazu's Server Tool] 0.3.33 Sequential Auto Join engine ready; current setting: " + (enabled ? "ON" : "OFF"));
        } catch (Throwable t) {
            System.err.println("[Zazu's Server Tool] Could not enable Sequential Auto Join:");
            Reflection.unwrap(t).printStackTrace();
        }
    }

    private static void registerPlayJoinWatcher() throws Exception {
        Reflection.registerStaticEvent(
                "net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents",
                "JOIN",
                "net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents$Join",
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) return Reflection.objectMethod(proxy, method, args);
                    if (!enabled || !joinInProgress || lastAutoJoinEndpoint.isBlank()) return null;
                    String endpoint = lastAutoJoinEndpoint;
                    long stamp = System.currentTimeMillis();
                    enteredPlay = true;
                    playEnteredAt = stamp;
                    System.out.println("[Zazu's Server Tool] Auto Join entered PLAY on " + endpoint + "; waiting 8 seconds for stability.");
                    CompletableFuture.delayedExecutor(STABLE_JOIN_MS, TimeUnit.MILLISECONDS).execute(() -> {
                        if (enabled && joinInProgress && enteredPlay && playEnteredAt == stamp && endpoint.equals(lastAutoJoinEndpoint)) {
                            stopPass("Sequential Auto Join succeeded on " + endpoint + " after a stable 8-second connection.");
                        }
                    });
                    return null;
                });
    }

    private static void registerPlayDisconnectWatcher() throws Exception {
        Reflection.registerStaticEvent(
                "net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents",
                "DISCONNECT",
                "net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents$Disconnect",
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) return Reflection.objectMethod(proxy, method, args);
                    if (!enabled || !joinInProgress || !enteredPlay) return null;
                    long duration = playEnteredAt == 0L ? 0L : Math.max(0L, System.currentTimeMillis() - playEnteredAt);
                    if (duration < STABLE_JOIN_MS) {
                        enteredPlay = false;
                        playEnteredAt = 0L;
                        System.out.println("[Zazu's Server Tool] Auto Join connection lasted " + duration + "ms on " + lastAutoJoinEndpoint + "; continuing.");
                    }
                    return null;
                });
    }

    private static void registerScreenWatcher() throws Exception {
        Reflection.registerStaticEvent(
                "net.fabricmc.fabric.api.client.screen.v1.ScreenEvents",
                "AFTER_INIT",
                "net.fabricmc.fabric.api.client.screen.v1.ScreenEvents$AfterInit",
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) return Reflection.objectMethod(proxy, method, args);
                    if (args == null || args.length < 4 || args[1] == null) return null;
                    Object client = args[0], screen = args[1];
                    try {
                        if (Reflection.isScreen(screen, "JoinMultiplayerScreen")) {
                            onMultiplayerInit(client, screen, ((Number) args[2]).intValue(), ((Number) args[3]).intValue());
                        } else if (Reflection.isScreen(screen, "ConnectScreen")) {
                            onConnectScreen(screen);
                        } else if (Reflection.isScreen(screen, "DisconnectedScreen")) {
                            onDisconnected(client, screen);
                        }
                    } catch (Throwable t) {
                        System.err.println("[Zazu's Server Tool] Sequential Auto Join screen hook failed:");
                        Reflection.unwrap(t).printStackTrace();
                    }
                    return null;
                });
    }

    private static void onMultiplayerInit(Object client, Object screen, int width, int height) throws Exception {
        // A failed attempt returns to Multiplayer while keeping the attempted set.
        // Clear only the current attempt; the next tick will select the next entry.
        if (joinInProgress) {
            System.out.println("[Zazu's Server Tool] Auto Join returned to Multiplayer after " + lastAutoJoinEndpoint + "; continuing to the next scanned server.");
            clearCurrentAttempt();
        }
        WatchState state = new WatchState(client, screen);
        registerAfterTick(state);
    }

    private static void onConnectScreen(Object screen) {
        if (!enabled || !joinInProgress || screen == null) return;
        try {
            registerCancelClickWatcher(screen);
        } catch (Throwable t) {
            System.err.println("[Zazu's Server Tool] Could not attach Auto Join Cancel watcher; Auto Join will continue normally.");
            Reflection.unwrap(t).printStackTrace();
        }
        System.out.println("[Zazu's Server Tool] Auto Join ConnectScreen active for " + lastAutoJoinEndpoint + "; the actual Cancel button will stop Auto Join.");
    }

    private static void registerCancelClickWatcher(Object screen) throws Exception {
        synchronized (CANCEL_WATCHED) {
            if (!CANCEL_WATCHED.add(screen)) return;
        }
        Class<?> holder = Class.forName("net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents");
        Method factory = null;
        for (Method m : holder.getMethods()) {
            if (Modifier.isStatic(m.getModifiers()) && m.getName().equals("allowMouseClick") && m.getParameterCount() == 1
                    && m.getParameterTypes()[0].isInstance(screen)) {
                factory = m;
                break;
            }
        }
        if (factory == null) throw new IllegalStateException("ScreenMouseEvents.allowMouseClick(Screen) not found");
        Object event = factory.invoke(null, screen);
        Class<?> callback = Class.forName("net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents$AllowMouseClick");
        Object listener = Proxy.newProxyInstance(callback.getClassLoader(), new Class<?>[]{callback}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return Reflection.objectMethod(proxy, method, args);
            try {
                Object mouseEvent = args == null || args.length == 0 ? null : args[args.length - 1];
                double x = mouseCoordinate(mouseEvent, "x");
                double y = mouseCoordinate(mouseEvent, "y");
                if (mouseButton(mouseEvent) == 0 && enabled && joinInProgress && isCancelButtonAt(screen, x, y)) {
                    stopPass("Sequential Auto Join stopped because the user pressed Cancel.");
                }
            } catch (Throwable t) {
                System.err.println("[Zazu's Server Tool] Auto Join Cancel click detection failed; leaving Auto Join running.");
            }
            return Boolean.TRUE;
        });
        RuntimeAccess.registerEvent(event, listener);
    }

    private static double mouseCoordinate(Object event, String axis) {
        if (event == null) return Double.NaN;
        Object value = Reflection.invokeQuiet(event, axis);
        if (!(value instanceof Number)) value = Reflection.invokeQuiet(event, "get" + Character.toUpperCase(axis.charAt(0)) + axis.substring(1));
        if (!(value instanceof Number)) value = Reflection.getField(event, axis);
        return value instanceof Number n ? n.doubleValue() : Double.NaN;
    }

    private static int mouseButton(Object event) {
        if (event == null) return -1;
        Object value = Reflection.invokeQuiet(event, "button");
        if (!(value instanceof Number)) value = Reflection.invokeQuiet(event, "getButton");
        if (!(value instanceof Number)) value = Reflection.getField(event, "button");
        return value instanceof Number n ? n.intValue() : -1;
    }

    private static boolean isCancelButtonAt(Object screen, double x, double y) {
        if (Double.isNaN(x) || Double.isNaN(y)) return false;
        try {
            for (Object widget : Reflection.widgets(screen)) {
                if (!"cancel".equals(widgetMessage(widget).toLowerCase(Locale.ROOT).trim())) continue;
                Object over = Reflection.invokeQuiet(widget, "isMouseOver", x, y);
                if (Boolean.TRUE.equals(over)) return true;
                int wx = widgetInt(widget, "getX"), wy = widgetInt(widget, "getY");
                int ww = widgetInt(widget, "getWidth"), wh = widgetInt(widget, "getHeight");
                if (wx != Integer.MIN_VALUE && wy != Integer.MIN_VALUE && ww > 0 && wh > 0
                        && x >= wx && x < wx + ww && y >= wy && y < wy + wh) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static String widgetMessage(Object widget) {
        Object value = Reflection.invokeQuiet(widget, "getMessage");
        if (value == null) value = Reflection.getField(widget, "message");
        return RuntimeAccess.componentText(value);
    }

    private static int widgetInt(Object widget, String method) {
        Object value = Reflection.invokeQuiet(widget, method);
        return value instanceof Number n ? n.intValue() : Integer.MIN_VALUE;
    }

    private static void registerAfterTick(WatchState state) throws Exception {
        Class<?> screenEvents = Class.forName("net.fabricmc.fabric.api.client.screen.v1.ScreenEvents");
        Method factory = null;
        for (Method m : screenEvents.getMethods()) {
            if (Modifier.isStatic(m.getModifiers()) && m.getName().equals("afterTick") && m.getParameterCount() == 1
                    && m.getParameterTypes()[0].isInstance(state.screen)) {
                factory = m;
                break;
            }
        }
        if (factory == null) throw new IllegalStateException("ScreenEvents.afterTick(Screen) not found");
        Object event = factory.invoke(null, state.screen);
        Class<?> callback = Class.forName("net.fabricmc.fabric.api.client.screen.v1.ScreenEvents$AfterTick");
        Object listener = Proxy.newProxyInstance(callback.getClassLoader(), new Class<?>[]{callback}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return Reflection.objectMethod(proxy, method, args);
            try { tick(state); }
            catch (Throwable t) {
                System.err.println("[Zazu's Server Tool] Sequential Auto Join tick failed: " + Reflection.unwrap(t));
            }
            return null;
        });
        RuntimeAccess.registerEvent(event, listener);
    }

    private static void tick(WatchState state) throws Exception {
        if (!enabled || joinInProgress) return;
        if (Reflection.currentScreen(state.client) != state.screen) return;
        if (ServerFinderClient.isOverlayOpen(state.screen)) return;

        SavedServer next = null;
        for (SavedServer server : loadSavedServers(state.client)) {
            if (server.favourite) continue;
            String key = ToolState.normalize(server.endpoint);
            if (!key.isBlank() && !ATTEMPTED.contains(key)) {
                next = server;
                break;
            }
        }

        if (next == null) {
            long now = System.currentTimeMillis();
            if (endReachedSince == 0L) {
                endReachedSince = now;
                System.out.println("[Zazu's Server Tool] Auto Join found no untried scanned servers; confirming end of list...");
                return;
            }
            if (now - endReachedSince >= 1_500L) {
                stopPass("Sequential Auto Join reached the end of the scanned server list.");
            }
            return;
        }

        endReachedSince = 0L;
        String normalized = ToolState.normalize(next.endpoint);
        ATTEMPTED.add(normalized);
        prepareViaFabricPlus(next.endpoint);
        WhitelistAutoDeleteEntrypoint.noteAttempt(next.endpoint);
        joinInProgress = true;
        enteredPlay = false;
        lastAutoJoinEndpoint = next.endpoint;
        playEnteredAt = 0L;

        if (connect(state.screen, next.serverData)) {
            int attemptNumber = ServerTabsEntrypoint.autoJoinAttemptedScannedCount();
            System.out.println("[Zazu's Server Tool] Sequential Auto Join attempting: " + next.endpoint + " (attempt " + attemptNumber + ")");
        } else {
            abandonCurrentAttempt("Could not start connection to " + next.endpoint + "; continuing.");
        }
    }

    private static void onDisconnected(Object client, Object screen) {
        if (!joinInProgress || lastAutoJoinEndpoint.isBlank()) return;
        String reason = extractDisconnectReason(screen);
        String normalized = normalizeReason(reason);
        System.out.println("[Zazu's Server Tool] Auto Join failed on " + lastAutoJoinEndpoint + " | reason: " + reason + " | moving to next scanned server.");
        if (isWhitelistRejection(normalized)) return; // whitelist watcher owns forced deletion + return
        returnToMultiplayer(client, screen);
    }

    private static void abandonCurrentAttempt(String message) {
        clearCurrentAttempt();
        if (message != null && !message.isBlank()) System.err.println("[Zazu's Server Tool] " + message);
    }

    private static void clearCurrentAttempt() {
        joinInProgress = false;
        enteredPlay = false;
        lastAutoJoinEndpoint = "";
        playEnteredAt = 0L;
        WhitelistAutoDeleteEntrypoint.clearAttempt();
    }

    private static void stopPass(String message) {
        enabled = false;
        saveEnabled(false);
        resetRuntimeState();
        System.out.println("[Zazu's Server Tool] " + message);
    }

    private static void resetRuntimeState() {
        ATTEMPTED.clear();
        joinInProgress = false;
        enteredPlay = false;
        lastAutoJoinEndpoint = "";
        playEnteredAt = 0L;
        endReachedSince = 0L;
        WhitelistAutoDeleteEntrypoint.clearAttempt();
    }

    private static void prepareViaFabricPlus(String endpoint) {
        try {
            if (!ViaFabricPlusBridge.isAvailable()) return;
            int protocol = ToolState.protocolFor(endpoint);
            if (protocol > 0 && ViaFabricPlusBridge.setTargetProtocol(protocol)) return;
            String version = ToolState.versionFor(endpoint);
            if (version != null && !version.isBlank()) ViaFabricPlusBridge.setTargetVersion(version);
        } catch (Throwable t) {
            System.err.println("[Zazu's Server Tool] ViaFabricPlus Auto Join preparation skipped: " + Reflection.unwrap(t));
        }
    }

    private static boolean connect(Object multiplayerScreen, Object serverData) {
        if (multiplayerScreen == null || serverData == null) return false;
        for (String name : List.of("join", "connect")) {
            for (Method m : allMethods(multiplayerScreen.getClass())) {
                if (!m.getName().equals(name) || m.getParameterCount() != 1 || !m.getParameterTypes()[0].isInstance(serverData)) continue;
                try {
                    m.setAccessible(true);
                    m.invoke(multiplayerScreen, serverData);
                    return true;
                } catch (Throwable t) {
                    System.err.println("[Zazu's Server Tool] Sequential Auto Join connection start failed: " + Reflection.unwrap(t));
                    return false;
                }
            }
        }
        return false;
    }

    private static List<SavedServer> loadSavedServers(Object client) throws Exception {
        ArrayList<SavedServer> result = new ArrayList<>();
        Object list = ServerFinderClient.ServerListBridge.createLoadedList(client);
        for (Object server : ServerFinderClient.ServerListBridge.servers(list)) {
            String endpoint = ServerFinderClient.ServerListBridge.serverEndpoint(server);
            if (endpoint.isBlank()) continue;
            boolean favourite = ServerFinderClient.ServerListBridge.serverName(server).startsWith(FAV_PREFIX);
            result.add(new SavedServer(endpoint, favourite, server));
        }
        return result;
    }

    private static void returnToMultiplayer(Object client, Object disconnectScreen) {
        Object multiplayer = findMultiplayerScreen(disconnectScreen);
        if (multiplayer == null) {
            System.err.println("[Zazu's Server Tool] Auto Join could not find Multiplayer parent after failed connection.");
            abandonCurrentAttempt("Could not restore Multiplayer automatically; Auto Join remains ON.");
            return;
        }
        Runnable task = () -> {
            try {
                ScreenCompat.setScreen(client, multiplayer);
                System.out.println("[Zazu's Server Tool] Auto Join returned to Multiplayer after " + lastAutoJoinEndpoint + "; continuing to the next scanned server.");
            } catch (Throwable t) {
                System.err.println("[Zazu's Server Tool] Could not return to Multiplayer for next Auto Join attempt: " + Reflection.unwrap(t));
                abandonCurrentAttempt("Return-to-list failed; Auto Join remains ON.");
            }
        };
        Reflection.execute(client, task);
    }

    private static Object findMultiplayerScreen(Object screen) {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Object current = screen;
        for (int depth = 0; current != null && depth < 12 && seen.add(current); depth++) {
            if (Reflection.isScreen(current, "JoinMultiplayerScreen")) return current;
            Object next = Reflection.getField(current, "parent", "lastScreen", "previousScreen");
            if (next == current) break;
            current = next;
        }
        return null;
    }

    private static String extractDisconnectReason(Object screen) {
        Object details = Reflection.getField(screen, "details");
        if (details != null) {
            Object value = Reflection.invokeQuiet(details, "reason");
            String text = RuntimeAccess.componentText(value);
            if (!text.isBlank()) return text;
        }
        for (String name : List.of("reason", "message", "title", "info")) {
            String text = RuntimeAccess.componentText(Reflection.getField(screen, name));
            if (!text.isBlank()) return text;
        }
        for (String name : List.of("getReason", "getMessage", "getTitle")) {
            String text = RuntimeAccess.componentText(Reflection.invokeQuiet(screen, name));
            if (!text.isBlank()) return text;
        }
        return "";
    }

    private static String componentText(Object value) {
        return RuntimeAccess.componentText(value);
    }

    private static String normalizeReason(String reason) {
        return reason == null ? "" : reason.toLowerCase(Locale.ROOT).replace('\n', ' ').replace('-', ' ').trim();
    }

    private static boolean isWhitelistRejection(String reason) {
        return reason.contains("whitelist") || reason.contains("white list") || reason.contains("not whitelisted")
                || reason.contains("not on the whitelist") || reason.contains("not on whitelist");
    }

    private static List<Method> allMethods(Class<?> type) {
        ArrayList<Method> out = new ArrayList<>();
        for (Class<?> c = type; c != null; c = c.getSuperclass()) out.addAll(Arrays.asList(c.getDeclaredMethods()));
        return out;
    }

    private static boolean loadEnabled() {
        Properties p = new Properties();
        Path file = configFile();
        try (InputStream in = Files.exists(file) ? Files.newInputStream(file) : null) {
            if (in != null) p.load(in);
        } catch (Throwable ignored) {}
        return Boolean.parseBoolean(p.getProperty("enabled", "false"));
    }

    private static void saveEnabled(boolean value) {
        Properties p = new Properties();
        p.setProperty("enabled", String.valueOf(value));
        Path file = configFile();
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                p.store(out, "Zazu's Server Tool Sequential Auto Join");
            }
        } catch (Throwable t) {
            System.err.println("[Zazu's Server Tool] Could not save Auto Join setting: " + Reflection.unwrap(t));
        }
    }

    private static Path configFile() {
        return ToolState.configDir().resolve("zazus-server-tool-autojoin.properties");
    }

    private record SavedServer(String endpoint, boolean favourite, Object serverData) {}

    private static final class WatchState {
        final Object client, screen;
        WatchState(Object client, Object screen) {
            this.client = client;
            this.screen = screen;
        }
    }
}
