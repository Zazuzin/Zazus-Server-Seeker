package dev.zazuzin.zst;

import java.lang.reflect.*;
import java.util.*;

/** Renders a compact live Auto Join progress/status line without altering Auto Join decisions. */
final class AutoJoinDiagnostics {
    private static final String CORE_AUTO_JOIN = "dev.zazuzin.zst.AutoJoinEntrypoint";
    private static final long STABLE_JOIN_MS = 8_000L;
    private static final int TEXT_COLOR = 0xFFAAAAAA;
    private static final Set<Object> WATCHED = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private AutoJoinDiagnostics() {}

    static void install() {
        try {
            Class<?> holder = Class.forName("net.fabricmc.fabric.api.client.screen.v1.ScreenEvents");
            Class<?> callback = Class.forName("net.fabricmc.fabric.api.client.screen.v1.ScreenEvents$AfterInit");
            Object event = holder.getField("AFTER_INIT").get(null);
            Object listener = Proxy.newProxyInstance(callback.getClassLoader(), new Class<?>[]{callback}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) return RuntimeAccess.objectMethod(proxy, method, args);
                if (args != null && args.length >= 2 && isRelevant(args[1])) {
                    try { watchScreen(args[1]); } catch (Throwable t) { logOnce("Auto Join status render hook failed", t); }
                }
                return null;
            });
            RuntimeAccess.registerEvent(event, listener);
        } catch (Throwable t) {
            System.err.println("[Zazu's Server Tool] Auto Join diagnostics unavailable: " + root(t));
        }
    }

    private static boolean isRelevant(Object screen) {
        return RuntimeAccess.isScreen(screen, "JoinMultiplayerScreen")
                || RuntimeAccess.isScreen(screen, "ConnectScreen")
                || RuntimeAccess.isScreen(screen, "DisconnectedScreen");
    }

    private static void watchScreen(Object screen) throws Exception {
        synchronized (WATCHED) {
            if (!WATCHED.add(screen)) return;
        }
        Class<?> holder = Class.forName("net.fabricmc.fabric.api.client.screen.v1.ScreenEvents");
        Method factory = null;
        for (Method method : holder.getMethods()) {
            if (Modifier.isStatic(method.getModifiers()) && method.getName().equals("afterExtract") && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].isInstance(screen)) {
                factory = method;
                break;
            }
        }
        if (factory == null) throw new NoSuchMethodException("ScreenEvents.afterExtract(Screen)");
        Object event = factory.invoke(null, screen);
        Class<?> callback = Class.forName("net.fabricmc.fabric.api.client.screen.v1.ScreenEvents$AfterExtract");
        Object listener = Proxy.newProxyInstance(callback.getClassLoader(), new Class<?>[]{callback}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return RuntimeAccess.objectMethod(proxy, method, args);
            if (args != null && args.length >= 2 && args[0] != null && args[1] != null) {
                try { render(args[0], args[1]); } catch (Throwable ignored) {}
            }
            return null;
        });
        RuntimeAccess.registerEvent(event, listener);
    }

    private static void render(Object screen, Object extractor) {
        if (!RuntimeAccess.staticBoolean(CORE_AUTO_JOIN, "enabled", false)) return;
        Object font = RuntimeAccess.font(screen);
        if (font == null) return;

        Snapshot snapshot = snapshot(screen);
        String text = snapshot.text();
        int screenWidth = RuntimeAccess.intField(screen, "width", 854);
        text = RuntimeAccess.trimToWidth(font, text, Math.max(120, screenWidth - 12));

        int y = RuntimeAccess.isScreen(screen, "JoinMultiplayerScreen") ? 29 : 6;
        RuntimeAccess.drawText(extractor, font, text, 6, y, TEXT_COLOR);
    }

    @SuppressWarnings("unchecked")
    private static Snapshot snapshot(Object screen) {
        boolean inProgress = RuntimeAccess.staticBoolean(CORE_AUTO_JOIN, "joinInProgress", false);
        boolean enteredPlay = RuntimeAccess.staticBoolean(CORE_AUTO_JOIN, "enteredPlay", false);
        String endpoint = RuntimeAccess.staticString(CORE_AUTO_JOIN, "lastAutoJoinEndpoint");
        long playEnteredAt = RuntimeAccess.staticLong(CORE_AUTO_JOIN, "playEnteredAt", 0L);

        int attempted = ServerTabsEntrypoint.autoJoinAttemptedScannedCount();
        int total = eligibleTotal();

        String progress;
        if (total > 0) {
            int current = inProgress ? Math.max(1, attempted) : Math.min(total, attempted + 1);
            progress = current + "/" + total;
        } else {
            progress = attempted > 0 ? String.valueOf(attempted) : "-";
        }

        String phase;
        if (enteredPlay && inProgress) {
            long elapsed = playEnteredAt <= 0L ? 0L : Math.max(0L, System.currentTimeMillis() - playEnteredAt);
            long remainingMs = Math.max(0L, STABLE_JOIN_MS - elapsed);
            long seconds = Math.max(1L, (remainingMs + 999L) / 1000L);
            phase = "Waiting for stable connection (" + seconds + "s)";
        } else if (RuntimeAccess.isScreen(screen, "ConnectScreen") && inProgress) {
            phase = "Connecting…";
        } else if (RuntimeAccess.isScreen(screen, "DisconnectedScreen")) {
            String reason = disconnectReason(screen).toLowerCase(Locale.ROOT);
            phase = reason.contains("whitelist") ? "Whitelist — removing and continuing…" : "Failed — continuing…";
        } else if (inProgress) {
            phase = "Connecting…";
        } else if (total > 0 && attempted >= total) {
            phase = "Finishing pass…";
        } else {
            phase = "Selecting next server…";
        }

        StringBuilder text = new StringBuilder("Auto Join: ").append(progress);
        if (!endpoint.isBlank()) text.append(" — ").append(endpoint);
        text.append(" — ").append(phase);
        return new Snapshot(text.toString());
    }

    private static int eligibleTotal() {
        return ServerTabsEntrypoint.autoJoinEligibleTotal();
    }

    private static String disconnectReason(Object screen) {
        for (String name : List.of("reason", "message", "title", "details")) {
            String text = RuntimeAccess.componentText(RuntimeAccess.field(screen, name));
            if (!text.isBlank()) return text;
        }
        for (String name : List.of("getReason", "getMessage", "getTitle")) {
            String text = RuntimeAccess.componentText(RuntimeAccess.invoke(screen, name));
            if (!text.isBlank()) return text;
        }
        return "";
    }

    private static volatile boolean logged;
    private static void logOnce(String message, Throwable t) {
        if (logged) return;
        logged = true;
        System.err.println("[Zazu's Server Tool] " + message + ": " + root(t));
    }

    private static Throwable root(Throwable t) {
        Throwable current = t;
        while ((current instanceof InvocationTargetException || current instanceof ExceptionInInitializerError) && current.getCause() != null) current = current.getCause();
        return current;
    }

    private record Snapshot(String text) {}
}
