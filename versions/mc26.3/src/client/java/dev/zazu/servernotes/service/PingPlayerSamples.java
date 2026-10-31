package dev.zazu.servernotes.service;

import dev.zazu.servernotes.util.SimpleJson;
import java.util.*;

/** Reads only bounded, plausible names actually present in a status sample. */
public final class PingPlayerSamples {
    public static final int MAX_SAMPLE_PLAYERS = 100;
    private PingPlayerSamples() {}

    public static List<ServerPlayerService.ObservedPlayer> parseStatus(String json) {
        try {
            Object root = SimpleJson.parse(json);
            if (!(root instanceof Map<?, ?> data) || !(data.get("players") instanceof Map<?, ?> players)
                    || !(players.get("sample") instanceof List<?> sample)) return List.of();
            LinkedHashMap<String, ServerPlayerService.ObservedPlayer> result = new LinkedHashMap<>();
            for (int i = 0; i < Math.min(sample.size(), MAX_SAMPLE_PLAYERS); i++) {
                if (sample.get(i) instanceof Map<?, ?> player) add(result, player.get("id"), player.get("name"));
            }
            return List.copyOf(result.values());
        } catch (RuntimeException malformed) { return List.of(); }
    }

    public static List<ServerPlayerService.ObservedPlayer> fromProfiles(Iterable<?> profiles) {
        LinkedHashMap<String, ServerPlayerService.ObservedPlayer> result = new LinkedHashMap<>();
        int count = 0;
        for (Object profile : profiles) {
            if (count++ >= MAX_SAMPLE_PLAYERS) break;
            add(result, read(profile, "id", "getId"), read(profile, "name", "getName"));
        }
        return List.copyOf(result.values());
    }

    private static Object read(Object target, String... names) {
        if (target == null) return null;
        for (String name : names) {
            try { return target.getClass().getMethod(name).invoke(target); }
            catch (ReflectiveOperationException | RuntimeException ignored) { }
        }
        return null;
    }

    private static void add(Map<String, ServerPlayerService.ObservedPlayer> result, Object id, Object name) {
        if (!(name instanceof String username) || !username.matches("[A-Za-z0-9_]{1,16}")) return;
        String uuid = id == null ? "" : id.toString();
        if (uuid.matches("(?i)[0-9a-f]{32}")) uuid = uuid.replaceFirst("(.{8})(.{4})(.{4})(.{4})(.{12})", "$1-$2-$3-$4-$5");
        if (!uuid.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || uuid.equals("00000000-0000-0000-0000-000000000000")) uuid = "";
        uuid = uuid.toLowerCase(Locale.ROOT);
        String key = uuid.isEmpty() ? "name:" + username.toLowerCase(Locale.ROOT) : "uuid:" + uuid;
        result.putIfAbsent(key, new ServerPlayerService.ObservedPlayer(key, uuid, username));
    }
}
