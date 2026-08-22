package dev.zazuzin.zst;

import net.fabricmc.api.ClientModInitializer;

import java.util.*;

/**
 * Deletes the explicitly attempted saved server when Minecraft reports a
 * definite whitelist rejection. Whitelist deletion intentionally bypasses
 * normal favourite protection.
 */
public final class WhitelistAutoDeleteEntrypoint implements ClientModInitializer {
    private static final long ATTEMPT_WINDOW_MS = 120_000L;
    private static volatile String lastAttemptedEndpoint = "";
    private static volatile long lastAttemptAt;

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
                            if (Reflection.isScreen(screen, "ConnectScreen")) captureAttempt(screen);
                            else if (Reflection.isScreen(screen, "DisconnectedScreen")) handleDisconnect(client, screen);
                        } catch (Throwable t) {
                            System.err.println("[Zazu's Server Tool] Whitelist watcher error: " + Reflection.unwrap(t));
                        }
                        return null;
                    });
            System.out.println("[Zazu's Server Tool] 0.3.33 whitelist auto-delete enabled.");
        } catch (Throwable t) {
            System.err.println("[Zazu's Server Tool] Whitelist watcher registration failed: " + Reflection.unwrap(t));
        }
    }

    static void noteAttempt(String endpoint) {
        String normalized = ToolState.normalize(endpoint);
        if (normalized.isBlank()) return;
        lastAttemptedEndpoint = normalized;
        lastAttemptAt = System.currentTimeMillis();
    }

    static void clearAttempt() {
        lastAttemptedEndpoint = "";
        lastAttemptAt = 0L;
    }

    private static void captureAttempt(Object connectScreen) {
        String endpoint = selectedEndpoint(connectScreen);
        if (!endpoint.isBlank()) noteAttempt(endpoint);
    }

    private static void handleDisconnect(Object client, Object screen) {
        String endpoint = lastAttemptedEndpoint;
        long age = System.currentTimeMillis() - lastAttemptAt;
        if (endpoint.isBlank()) {
            Object multiplayer = findMultiplayerScreen(Reflection.getField(screen, "parent", "lastScreen", "previousScreen"));
            if (multiplayer != null) {
                endpoint = ServerListAccess.selectedEndpoint(multiplayer);
                if (!endpoint.isBlank()) {
                    noteAttempt(endpoint);
                    age = 0L;
                }
            }
        }
        if (endpoint.isBlank() || lastAttemptAt == 0L || age < 0L || age > ATTEMPT_WINDOW_MS) {
            clearAttempt();
            return;
        }
        if (!isWhitelistRejection(normalizeReason(extractDisconnectReason(screen)))) {
            clearAttempt();
            return;
        }

        boolean removed = ServerListAccess.forceRemove(client, endpoint);
        if (removed) {
            ServerCategoryStore.remove(endpoint);
            ToolState.recordDeleted(endpoint);
            removeFromOpenMultiplayerBackings(screen, endpoint);
            System.out.println("[Zazu's Server Tool] Removed whitelist-rejected server: " + endpoint);
        }

        returnToMultiplayer(client, screen);
        clearAttempt();
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
            System.err.println("[Zazu's Server Tool] Whitelist deletion succeeded but Multiplayer parent could not be found.");
            return;
        }
        Reflection.execute(client, () -> {
            try { ScreenCompat.setScreen(client, multiplayer); }
            catch (Throwable t) { System.err.println("[Zazu's Server Tool] Could not return to Multiplayer after whitelist deletion: " + Reflection.unwrap(t)); }
        });
    }

    private static String selectedEndpoint(Object screen) {
        Object direct = Reflection.getField(screen, "serverData", "server", "targetServer");
        if (direct != null) {
            String endpoint = ServerListAccess.endpoint(direct);
            if (!endpoint.isBlank()) return endpoint;
        }
        Object parent = Reflection.getField(screen, "parent", "lastScreen", "previousScreen");
        return parent == null ? "" : ServerListAccess.selectedEndpoint(parent);
    }

    private static String extractDisconnectReason(Object screen) {
        Object details = Reflection.getField(screen, "details");
        if (details != null) {
            String text = RuntimeAccess.componentText(Reflection.invokeQuiet(details, "reason"));
            if (!text.isBlank()) return text;
        }
        for (String field : List.of("reason", "message", "title", "info")) {
            String text = RuntimeAccess.componentText(Reflection.getField(screen, field));
            if (!text.isBlank()) return text;
        }
        for (String method : List.of("getReason", "getMessage", "getTitle")) {
            String text = RuntimeAccess.componentText(Reflection.invokeQuiet(screen, method));
            if (!text.isBlank()) return text;
        }
        return "";
    }

    private static String normalizeReason(String reason) {
        return reason == null ? "" : reason.toLowerCase(Locale.ROOT).replace('\n', ' ').replace('-', ' ').trim();
    }

    private static boolean isWhitelistRejection(String reason) {
        return reason.contains("whitelist") || reason.contains("white list") || reason.contains("not whitelisted")
                || reason.contains("not on the whitelist") || reason.contains("not on whitelist");
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
}
