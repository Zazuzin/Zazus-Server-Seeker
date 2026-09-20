package dev.zazuzin.zst;

import java.util.Locale;
import java.util.Map;

/** Session-only BreakBlocks authentication and Patreon-tier state. */
final class BreakBlocksAccount {
    private static String keyMarker = "";
    private static boolean observed;
    private static boolean authed;
    private static String patreonTier = "";

    private BreakBlocksAccount() {}

    static synchronized void configure(String apiKey) {
        String normalized = apiKey == null ? "" : apiKey.trim();
        String marker = normalized.isEmpty() ? "" : normalized.length() + ":" + normalized.hashCode();
        if (marker.equals(keyMarker)) return;
        keyMarker = marker;
        observed = false;
        authed = false;
        patreonTier = "";
    }

    static synchronized void rejected() {
        observed = true;
        authed = false;
        patreonTier = "";
    }

    static synchronized void observe(boolean responseAuthed, String responseTier) {
        observed = true;
        authed = responseAuthed;
        patreonTier = responseAuthed && responseTier != null ? responseTier.trim() : "";
    }

    static void observeJson(String json) {
        try {
            Object parsed = new ServerFinderClient.JsonParser(json == null ? "" : json).parse();
            if (!(parsed instanceof Map<?, ?> top)) return;
            Object dataValue = top.get("data");
            Map<?, ?> data = dataValue instanceof Map<?, ?> nested ? nested : Map.of();
            Object authedValue = firstPresent(top, data, "authed");
            Object tierValue = firstPresent(top, data, "patreon_tier");
            if (authedValue == null && tierValue == null) return;
            boolean responseAuthed = authedValue instanceof Boolean value
                    ? value : Boolean.parseBoolean(String.valueOf(authedValue));
            observe(responseAuthed, tierValue == null ? "" : String.valueOf(tierValue));
        } catch (RuntimeException ignored) {
            // A status response without account metadata must not erase the
            // most recent confirmed authentication state.
        }
    }

    private static Object firstPresent(Map<?, ?> top, Map<?, ?> data, String key) {
        if (top.containsKey(key)) return top.get(key);
        return data.containsKey(key) ? data.get(key) : null;
    }

    static synchronized Snapshot snapshot() {
        return new Snapshot(observed, authed, patreonTier, isPaidTier(authed, patreonTier));
    }

    static boolean isPaidTier(boolean responseAuthed, String tier) {
        if (!responseAuthed || tier == null || tier.isBlank()) return false;
        String normalized = tier.trim().toLowerCase(Locale.ROOT);
        return !normalized.equals("none")
                && !normalized.equals("free")
                && !normalized.equals("null")
                && !normalized.equals("not subscribed")
                && !normalized.equals("no subscription");
    }

    record Snapshot(boolean observed, boolean authed, String patreonTier, boolean paid) {}
}
