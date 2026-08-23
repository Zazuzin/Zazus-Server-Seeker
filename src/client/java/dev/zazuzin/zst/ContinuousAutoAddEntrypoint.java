package dev.zazuzin.zst;

import net.fabricmc.api.ClientModInitializer;

import java.lang.reflect.*;
import java.util.*;

/** Keeps Auto Add cycling after each completed BreakBlocks batch. */
public final class ContinuousAutoAddEntrypoint implements ClientModInitializer {
    private static final Map<Object, LoopState> STATES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final long BETWEEN_BATCHES_MS = 2_000L;
    private static final long AFTER_EXHAUSTED_MS = 60_000L;

    @Override
    public void onInitializeClient() {
        try {
            Reflection.registerStaticEvent(
                    "net.fabricmc.fabric.api.client.screen.v1.ScreenEvents",
                    "AFTER_INIT",
                    "net.fabricmc.fabric.api.client.screen.v1.ScreenEvents$AfterInit",
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) return Reflection.objectMethod(proxy, method, args);
                        if (args != null && args.length >= 2 && Reflection.isScreen(args[1], "JoinMultiplayerScreen")) {
                            install(args[0], args[1]);
                        }
                        return null;
                    });
            System.out.println("[Zazu's Server Tool] 0.3.34 continuous Auto Add enabled.");
        } catch (Throwable t) {
            System.err.println("[Zazu's Server Tool] Continuous Auto Add registration failed: " + Reflection.unwrap(t));
        }
    }

    private static void install(Object client, Object screen) {
        if (STATES.containsKey(screen)) return;
        LoopState loop = new LoopState(client, screen);
        STATES.put(screen, loop);
        try {
            Class<?> screenEvents = Class.forName("net.fabricmc.fabric.api.client.screen.v1.ScreenEvents");
            Object event = null;
            for (Method m : screenEvents.getMethods()) {
                if (Modifier.isStatic(m.getModifiers()) && m.getName().equals("afterTick") && m.getParameterCount() == 1) {
                    event = m.invoke(null, screen);
                    break;
                }
            }
            if (event == null) return;
            Class<?> cb = Class.forName("net.fabricmc.fabric.api.client.screen.v1.ScreenEvents$AfterTick");
            Object listener = Proxy.newProxyInstance(cb.getClassLoader(), new Class<?>[]{cb}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) return Reflection.objectMethod(proxy, method, args);
                tick(loop);
                return null;
            });
            register(event, listener);
        } catch (Throwable t) {
            System.err.println("[Zazu's Server Tool] Auto Add tick hook failed: " + Reflection.unwrap(t));
        }
    }

    private static void tick(LoopState loop) {
        if (Reflection.currentScreen(loop.client) != loop.screen) {
            loop.wasEnabled = false;
            loop.wasLoading = false;
            return;
        }
        ServerFinderClient.OverlayState state = ServerFinderClient.stateFor(loop.screen);
        if (state == null || !ServerFinderClient.isOverlayOpen(loop.screen) || !state.autoAdd) {
            loop.wasEnabled = false;
            loop.wasLoading = false;
            loop.nextRunAt = 0L;
            return;
        }

        long now = System.currentTimeMillis();
        if (!loop.wasEnabled) {
            loop.wasEnabled = true;
            loop.wasLoading = state.loading;
            loop.nextRunAt = state.loading ? 0L : now;
        }

        if (state.loading) {
            loop.wasLoading = true;
            return;
        }

        if (loop.wasLoading) {
            loop.wasLoading = false;
            // The API search marks exhausted when all currently available pages have been consumed.
            loop.nextRunAt = now + (state.exhausted ? AFTER_EXHAUSTED_MS : BETWEEN_BATCHES_MS);
        }

        if (loop.nextRunAt == 0L) loop.nextRunAt = now + BETWEEN_BATCHES_MS;
        if (now < loop.nextRunAt) return;

        // Respect the configured session limit before starting another request.
        if (ToolState.autoAddLimit > 0 && state.autoAddedThisSession >= ToolState.autoAddLimit) {
            state.autoAdd = false;
            Reflection.setButtonText(state.autoButton, "Auto-add: OFF");
            ToolState.autoAddDefault = false;
            ToolState.save();
            return;
        }

        if (state.exhausted) {
            ServerFinderClient.resetSearchState(state, "Refreshing search pool…");
        }
        loop.nextRunAt = 0L;
        ServerFinderClient.findNewServers(state);
    }

    private static void register(Object event, Object listener) throws Exception {
        RuntimeAccess.registerEvent(event, listener);
    }

    private static final class LoopState {
        final Object client;
        final Object screen;
        boolean wasEnabled;
        boolean wasLoading;
        long nextRunAt;
        LoopState(Object client, Object screen) { this.client = client; this.screen = screen; }
    }
}
