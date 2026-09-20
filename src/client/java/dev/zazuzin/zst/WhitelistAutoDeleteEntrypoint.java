package dev.zazuzin.zst;

import net.fabricmc.api.ClientModInitializer;

import java.lang.reflect.*;
import java.util.*;

/**
 * Conservatively removes Finder-owned servers after a confirmed permanent
 * connection failure. Favourites, manual servers and version mismatches are
 * always protected.
 */
public final class WhitelistAutoDeleteEntrypoint implements ClientModInitializer {
    private static final long ATTEMPT_WINDOW_MS = 120_000L;
    private static final Map<Object, DisconnectWatch> DISCONNECT_WATCHES = Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile String lastAttemptedEndpoint = "";
    private static volatile long lastAttemptAt;
    private static volatile String lastHandledEndpoint = "";
    private static volatile DisconnectReason.CleanupCause lastHandledCause = DisconnectReason.CleanupCause.NONE;
    private static volatile long lastHandledAt;

    @Override
    public void onInitializeClient() {
        try {
            Reflection.registerStaticEvent(
                    "net.fabricmc.fabric.api.client.screen.v1.ScreenEvents",
                    "AFTER_INIT",
                    "net.fabricmc.fabric.api.client.screen.v1.ScreenEvents$AfterInit",
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) return Reflection.objectMethod(proxy, method, args);
                        if (args == null || args.length < 2 || args[1] == null) return null;
                        Object client = args[0], screen = args[1];
                        try {
                            if (Reflection.isScreen(screen, "ConnectScreen")) {
                                captureAttempt(screen);
                            } else if (DisconnectReason.isDisconnectScreen(screen)) {
                                if (!tryHandleDisconnect(client, screen)) registerDelayedDisconnectWatcher(client, screen);
                            }
                        } catch (Throwable t) {
                            System.err.println("[Zazu's Server Seeker] Automatic cleanup watcher error: " + Reflection.unwrap(t));
                        }
                        return null;
                    });
            registerGlobalTickFallback();
            System.out.println("[Zazu's Server Seeker] Conservative automatic cleanup enabled (screen and client-tick detection).");
        } catch (Throwable t) {
            System.err.println("[Zazu's Server Seeker] Automatic cleanup watcher registration failed: " + Reflection.unwrap(t));
        }
    }

    private static void registerGlobalTickFallback() throws Exception {
        Reflection.registerStaticEvent(
                "net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents",
                "END_CLIENT_TICK",
                "net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents$EndTick",
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) return Reflection.objectMethod(proxy, method, args);
                    Object client = args != null && args.length > 0 ? args[0] : RuntimeAccess.minecraftInstance();
                    if (client == null) return null;
                    try {
                        Object screen = ScreenCompat.currentScreen(client);
                        if (Reflection.isScreen(screen, "ConnectScreen")) {
                            captureAttempt(screen);
                        } else if (DisconnectReason.isDisconnectScreen(screen)) {
                            tryHandleDisconnect(client, screen);
                        }
                    } catch (Throwable t) {
                        System.err.println("[Zazu's Server Seeker] Automatic cleanup client-tick fallback failed: " + Reflection.unwrap(t));
                    }
                    return null;
                });
    }

    static void noteAttempt(String endpoint) {
        String normalized = ToolState.normalize(endpoint);
        if (normalized.isBlank()) return;
        lastAttemptedEndpoint = normalized;
        lastAttemptAt = System.currentTimeMillis();
        ServerTabsEntrypoint.noteConnectionAttempt(normalized);
    }

    static void clearAttempt() {
        lastAttemptedEndpoint = "";
        lastAttemptAt = 0L;
    }

    private static void captureAttempt(Object connectScreen) {
        String endpoint = selectedEndpoint(connectScreen);
        if (!endpoint.isBlank()) noteAttempt(endpoint);
    }

    private static boolean tryHandleDisconnect(Object client, Object screen) {
        String endpoint = resolveAttemptEndpoint(screen);
        if (endpoint.isBlank()) return false;
        return handleCleanupFailure(client, screen, endpoint);
    }

    private static String resolveAttemptEndpoint(Object screen) {
        String endpoint = ToolState.normalize(lastAttemptedEndpoint);
        long age = System.currentTimeMillis() - lastAttemptAt;
        if (!endpoint.isBlank() && lastAttemptAt != 0L && age >= 0L && age <= ATTEMPT_WINDOW_MS) return endpoint;

        Object multiplayer = findMultiplayerScreen(screen);
        if (multiplayer != null) {
            endpoint = ToolState.normalize(ServerListAccess.selectedEndpoint(multiplayer));
            if (!endpoint.isBlank()) {
                noteAttempt(endpoint);
                return endpoint;
            }
        }

        endpoint = ToolState.normalize(ServerTabsEntrypoint.recentAttemptEndpoint());
        if (!endpoint.isBlank()) {
            noteAttempt(endpoint);
            return endpoint;
        }
        return "";
    }

    private static void registerDelayedDisconnectWatcher(Object client, Object screen) {
        if (screen == null || DISCONNECT_WATCHES.containsKey(screen)) return;
        DisconnectWatch watch = new DisconnectWatch();
        DISCONNECT_WATCHES.put(screen, watch);
        try {
            Class<?> holder = Class.forName("net.fabricmc.fabric.api.client.screen.v1.ScreenEvents");
            Method factory = null;
            for (Method m : holder.getMethods()) {
                if (Modifier.isStatic(m.getModifiers()) && m.getName().equals("afterTick") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isInstance(screen)) {
                    factory = m;
                    break;
                }
            }
            if (factory == null) return;
            Object event = factory.invoke(null, screen);
            Class<?> callback = Class.forName("net.fabricmc.fabric.api.client.screen.v1.ScreenEvents$AfterTick");
            Object listener = Proxy.newProxyInstance(callback.getClassLoader(), new Class<?>[]{callback}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) return Reflection.objectMethod(proxy, method, args);
                if (watch.finished) return null;
                try {
                    watch.ticks++;
                    if (tryHandleDisconnect(client, screen)) {
                        watch.finished = true;
                        return null;
                    }
                    Object current = Reflection.currentScreen(client);
                    if (current != screen || watch.ticks >= 40) {
                        watch.finished = true;
                        clearAttempt();
                    }
                } catch (Throwable t) {
                    watch.finished = true;
                    System.err.println("[Zazu's Server Seeker] Delayed cleanup detection failed: " + Reflection.unwrap(t));
                }
                return null;
            });
            RuntimeAccess.registerEvent(event, listener);
        } catch (Throwable t) {
            System.err.println("[Zazu's Server Seeker] Could not attach delayed cleanup watcher: " + Reflection.unwrap(t));
        }
    }

    static boolean handleWhitelistFailure(Object client, Object screen, String endpointHint) {
        String reason = DisconnectReason.extract(screen);
        if (!DisconnectReason.isWhitelistRejection(reason)) return false;
        return handleCleanupFailure(client, screen, endpointHint, reason,
                DisconnectReason.CleanupCause.WHITELIST);
    }

    static boolean handleCleanupFailure(Object client, Object screen, String endpointHint) {
        String reason = DisconnectReason.extract(screen);
        DisconnectReason.CleanupCause cause = DisconnectReason.cleanupCause(reason);
        if (cause == DisconnectReason.CleanupCause.NONE || !cleanupEnabled(cause)) return false;
        return handleCleanupFailure(client, screen, endpointHint, reason, cause);
    }

    private static boolean handleCleanupFailure(Object client, Object screen, String endpointHint,
                                                String reason, DisconnectReason.CleanupCause cause) {
        if (!cleanupEnabled(cause)) return false;

        String endpoint = ToolState.normalize(endpointHint);
        if (endpoint.isBlank()) endpoint = resolveAttemptEndpoint(screen);
        if (endpoint.isBlank()) {
            System.err.println("[Zazu's Server Seeker] " + cause.label()
                    + " detected but attempted endpoint was unknown. Reason: " + reason);
            return false;
        }

        long now = System.currentTimeMillis();
        if (endpoint.equals(lastHandledEndpoint) && cause == lastHandledCause
                && now - lastHandledAt >= 0L && now - lastHandledAt <= 2_000L) {
            AutoJoinEntrypoint.markReturningAfterFailure();
            return true;
        }
        lastHandledEndpoint = endpoint;
        lastHandledCause = cause;
        lastHandledAt = now;

        Object multiplayer = findMultiplayerScreen(screen);
        if (ServerListAccess.isFavouriteEndpoint(client, multiplayer, endpoint)) {
            System.out.println("[Zazu's Server Seeker] Kept favourite after " + cause.label() + ": " + endpoint);
            AutoJoinEntrypoint.markReturningAfterFailure();
            returnToMultiplayer(client, screen);
            clearAttempt();
            return true;
        }
        if (!finderOwned(endpoint)) {
            System.out.println("[Zazu's Server Seeker] Kept manually added server after "
                    + cause.label() + ": " + endpoint);
            AutoJoinEntrypoint.markReturningAfterFailure();
            returnToMultiplayer(client, screen);
            clearAttempt();
            return true;
        }

        if (cause == DisconnectReason.CleanupCause.INVALID_ADDRESS
                || cause == DisconnectReason.CleanupCause.UNREACHABLE) {
            int failures = ServerCategoryStore.recordHealthFailure(endpoint);
            if (failures < 3) {
                System.out.println("[Zazu's Server Seeker] Cleanup check " + failures
                        + "/3 for " + endpoint + " (" + cause.label() + "); keeping server for now.");
                AutoJoinEntrypoint.markReturningAfterFailure();
                returnToMultiplayer(client, screen);
                clearAttempt();
                return true;
            }
        }

        // If Auto Join owns the connection, mark the upcoming return as a failed
        // attempt rather than a user Cancel before switching back to Multiplayer.
        AutoJoinEntrypoint.markReturningAfterFailure();

        // Remove from the actual live JoinMultiplayerScreen ServerList first.
        // Otherwise that screen can later save its stale copy and resurrect a
        // server that was removed through a separately loaded ServerList.
        String savedName = ServerListAccess.savedName(client, multiplayer, endpoint);
        ServerCategoryStore.backupBeforeAutomaticRemoval();
        boolean removedLive = ServerListAccess.removeFromScreenServerList(multiplayer, endpoint);
        boolean removedPersisted = ServerListAccess.forceRemove(client, endpoint);
        boolean removed = removedLive || removedPersisted;

        if (removed) {
            ServerCategoryStore.recordAutomaticRemoval(savedName, endpoint, cause.label(),
                    DisconnectReason.concise(reason));
            ServerCategoryStore.remove(endpoint);
            ToolState.recordDeleted(endpoint);
            removeFromOpenMultiplayerBackings(screen, endpoint);
            System.out.println("[Zazu's Server Seeker] Automatically removed Finder server: " + endpoint
                    + " | live=" + removedLive + " persisted=" + removedPersisted
                    + " | cause=" + cause.label() + " | reason: " + reason);
        } else {
            System.err.println("[Zazu's Server Seeker] " + cause.label()
                    + " detected but saved server could not be removed: " + endpoint + " | reason: " + reason);
        }

        returnToMultiplayer(client, screen);
        clearAttempt();
        return true;
    }

    private static boolean finderOwned(String endpoint) {
        return ServerCategoryStore.isScanned(endpoint) || ToolState.wasAdded(endpoint);
    }

    private static boolean cleanupEnabled(DisconnectReason.CleanupCause cause) {
        if (!ToolState.automaticCleanupEnabled) return false;
        return switch (cause) {
            case WHITELIST -> ToolState.cleanupWhitelistEnabled;
            case REQUIRED_MODS -> ToolState.cleanupRequiredModsEnabled;
            case INVALID_ADDRESS -> ToolState.cleanupInvalidAddressEnabled;
            case UNREACHABLE -> ToolState.cleanupUnreachableEnabled;
            case NONE -> false;
        };
    }

    private static void removeFromOpenMultiplayerBackings(Object screen, String endpoint) {
        Object multiplayer = findMultiplayerScreen(screen);
        if (multiplayer == null) return;
        String target = ToolState.normalize(endpoint);
        for (List<Object> list : ServerListAccess.serverEntryLists(multiplayer)) {
            try {
                list.removeIf(entry -> {
                    Object data = ServerListAccess.serverData(entry);
                    return data != null && ToolState.normalize(ServerListAccess.endpoint(data)).equals(target);
                });
            } catch (Throwable ignored) {}
        }
    }

    private static void returnToMultiplayer(Object client, Object disconnectScreen) {
        Object multiplayer = findMultiplayerScreen(disconnectScreen);
        if (multiplayer == null) {
            System.err.println("[Zazu's Server Seeker] Whitelist deletion finished but Multiplayer parent could not be found.");
            return;
        }
        Reflection.execute(client, () -> {
            try { ScreenCompat.setScreen(client, multiplayer); }
            catch (Throwable t) { System.err.println("[Zazu's Server Seeker] Could not return to Multiplayer after whitelist deletion: " + Reflection.unwrap(t)); }
        });
    }

    private static String selectedEndpoint(Object screen) {
        Object direct = Reflection.getField(screen, "serverData", "server", "targetServer");
        if (direct != null) {
            String endpoint = ServerListAccess.endpoint(direct);
            if (!endpoint.isBlank()) return endpoint;
        }

        // Minecraft 26.2 may carry only a ServerAddress on ConnectScreen.
        // Capture it as a fallback so keyboard/Enter joins and unusual screen
        // transitions still retain enough context for whitelist deletion.
        Object address = Reflection.getField(screen, "serverAddress", "address", "targetAddress");
        String addressEndpoint = endpointFromAddress(address);
        if (!addressEndpoint.isBlank()) return addressEndpoint;

        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Object cursor = Reflection.getField(screen, "parent", "lastScreen", "previousScreen");
        for (int depth = 0; cursor != null && depth < 12 && seen.add(cursor); depth++) {
            if (Reflection.isScreen(cursor, "JoinMultiplayerScreen")) {
                String endpoint = ServerListAccess.selectedEndpoint(cursor);
                if (!endpoint.isBlank()) return endpoint;
            }
            Object next = Reflection.getField(cursor, "parent", "lastScreen", "previousScreen");
            if (next == cursor) break;
            cursor = next;
        }
        return ServerTabsEntrypoint.recentAttemptEndpoint();
    }

    private static String endpointFromAddress(Object address) {
        if (address == null) return "";
        Object host = Reflection.invokeQuiet(address, "getHost");
        if (host == null) host = Reflection.invokeQuiet(address, "host");
        if (host == null) host = Reflection.getField(address, "host");
        Object port = Reflection.invokeQuiet(address, "getPort");
        if (port == null) port = Reflection.invokeQuiet(address, "port");
        if (port == null) port = Reflection.getField(address, "port");
        if (host != null) {
            String h = String.valueOf(host).trim();
            if (!h.isBlank()) {
                if (port instanceof Number n) return h + ":" + n.intValue();
                return h;
            }
        }
        return "";
    }

    private static Object findMultiplayerScreen(Object screen) {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Object current = screen;
        for (int depth = 0; current != null && depth < 16 && seen.add(current); depth++) {
            if (Reflection.isScreen(current, "JoinMultiplayerScreen")) return current;
            Object next = Reflection.getField(current, "parent", "lastScreen", "previousScreen");
            if (next == current) break;
            current = next;
        }
        return null;
    }

    private static final class DisconnectWatch {
        int ticks;
        boolean finished;
    }
}
