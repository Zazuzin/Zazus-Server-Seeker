package dev.zazuzin.zst;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/** Adds measured direct-ping latency to the existing Finder result rows. */
final class FinderLatencyOverlay {
    private static final String CORE_FINDER = "dev.zazuzin.zst.ServerFinderClient";
    private static final long CACHE_MS = 60_000L;
    private static final long FAILED_CACHE_MS = 10_000L;
    private static final Map<String, Sample> CACHE = new ConcurrentHashMap<>();
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    private static final Set<Object> WATCHED = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static final ExecutorService PROBES = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "Zazu-Server-Latency");
        thread.setDaemon(true);
        return thread;
    });

    private FinderLatencyOverlay() {}

    static void install() {
        try {
            Class<?> holder = Class.forName("net.fabricmc.fabric.api.client.screen.v1.ScreenEvents");
            Class<?> callback = Class.forName("net.fabricmc.fabric.api.client.screen.v1.ScreenEvents$AfterInit");
            Object event = holder.getField("AFTER_INIT").get(null);
            Object listener = Proxy.newProxyInstance(callback.getClassLoader(), new Class<?>[]{callback}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) return RuntimeAccess.objectMethod(proxy, method, args);
                if (args != null && args.length >= 2 && RuntimeAccess.isScreen(args[1], "JoinMultiplayerScreen")) {
                    try { watchScreen(args[1]); } catch (Throwable t) { logOnce("Finder latency hook failed", t); }
                }
                return null;
            });
            RuntimeAccess.registerEvent(event, listener);
        } catch (Throwable t) {
            System.err.println("[Zazu's Server Tool] Finder latency unavailable: " + root(t));
        }
    }

    private static void watchScreen(Object screen) throws Exception {
        synchronized (WATCHED) {
            if (!WATCHED.add(screen)) return;
        }
        Class<?> holder = Class.forName("net.fabricmc.fabric.api.client.screen.v1.ScreenEvents");
        Method factory = null;
        for (Method method : holder.getMethods()) {
            if (Modifier.isStatic(method.getModifiers()) && method.getName().equals("afterTick") && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].isInstance(screen)) {
                factory = method;
                break;
            }
        }
        if (factory == null) throw new NoSuchMethodException("ScreenEvents.afterTick(Screen)");
        Object event = factory.invoke(null, screen);
        Class<?> callback = Class.forName("net.fabricmc.fabric.api.client.screen.v1.ScreenEvents$AfterTick");
        Object listener = Proxy.newProxyInstance(callback.getClassLoader(), new Class<?>[]{callback}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return RuntimeAccess.objectMethod(proxy, method, args);
            try { updateRows(screen); } catch (Throwable t) { logOnce("Finder latency row update failed", t); }
            return null;
        });
        RuntimeAccess.registerEvent(event, listener);
    }

    @SuppressWarnings("unchecked")
    private static void updateRows(Object screen) {
        Object states = RuntimeAccess.staticField(CORE_FINDER, "STATES");
        if (!(states instanceof Map<?, ?> map)) return;
        Object state = map.get(screen);
        if (state == null || !Boolean.TRUE.equals(RuntimeAccess.field(state, "open"))) return;

        Object resultsObject = RuntimeAccess.field(state, "results");
        Object buttonsObject = RuntimeAccess.field(state, "resultButtons");
        if (!(resultsObject instanceof List<?> results) || !(buttonsObject instanceof List<?> buttons)) return;

        int count = Math.min(results.size(), buttons.size());
        for (int i = 0; i < count; i++) {
            Object record = results.get(i);
            Object button = buttons.get(i);
            if (record == null || button == null) continue;

            String endpoint = stringInvoke(record, "endpoint", "");
            String address = stringInvoke(record, "address", "");
            int port = intInvoke(record, "port", 25565);
            String version = stringInvoke(record, "version", "?");
            int online = intInvoke(record, "playersOnline", 0);
            int max = intInvoke(record, "playersMax", 0);
            if (endpoint.isBlank() || address.isBlank()) continue;

            Sample sample = CACHE.get(endpoint.toLowerCase(Locale.ROOT));
            long now = System.currentTimeMillis();
            long ttl = sample != null && sample.latencyMs >= 0 ? CACHE_MS : FAILED_CACHE_MS;
            if (sample == null || now - sample.at > ttl) requestProbe(endpoint, address, port);

            String latency = sample == null ? "… ms" : sample.latencyMs >= 0 ? sample.latencyMs + " ms" : "? ms";
            String row = shorten(endpoint, 25) + " | " + latency + " | " + online + "/" + max + " | " + shorten(version, 13) + " | LIVE";
            RuntimeAccess.setButtonText(button, shorten(row, 62));
        }
    }

    private static void requestProbe(String endpoint, String address, int port) {
        String key = endpoint.toLowerCase(Locale.ROOT);
        if (!IN_FLIGHT.add(key)) return;
        PROBES.execute(() -> {
            try {
                long latency = MinecraftStatusProbe.measureLatencyMillis(address, port);
                CACHE.put(key, new Sample(latency, System.currentTimeMillis()));
            } finally {
                IN_FLIGHT.remove(key);
            }
        });
    }

    private static String stringInvoke(Object target, String method, String fallback) {
        Object value = RuntimeAccess.invoke(target, method);
        return value == null ? fallback : String.valueOf(value);
    }

    private static int intInvoke(Object target, String method, int fallback) {
        Object value = RuntimeAccess.invoke(target, method);
        return value instanceof Number n ? n.intValue() : fallback;
    }

    private static String shorten(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, Math.max(0, max - 1)) + "…";
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

    private record Sample(long latencyMs, long at) {}
}
