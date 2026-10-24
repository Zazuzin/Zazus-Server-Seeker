package dev.zazuzin.zst;

import dev.zazu.servernotes.util.SimpleJson;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.*;

/** Player discovery protocol. Entitlement is checked locally and by BreakBlocks. */
final class BreakBlocksPlayers {
    static final String PATREON_URL = "https://www.patreon.com/c/BreakBlocks";
    static final String ACCOUNT_URL = "https://breakblocks.com/";
    static final URI ACCESS_URI = URI.create("https://api.breakblocks.com/api/v0.1/servers/find?limit=1&page=1");
    static final int MAX_RECORDS = 1000;

    enum Mode {
        EXACT("Exact name"), PARTIAL("Partial name"), UUID("UUID");
        final String label;
        Mode(String label) { this.label = label; }
    }

    record Access(boolean reported, boolean authed, String tier) {
        boolean vip() { return hasVipAccess(authed, tier); }
    }
    record Name(String username, String changedAt) {}
    record Server(String address, int port, String name) {
        String endpoint() {
            String host = address.contains(":") && !address.startsWith("[") ? "[" + address + "]" : address;
            return host + ":" + port;
        }
    }
    record Player(String uuid, String username, List<Name> names, List<Server> servers) {}
    record Result(List<Player> players, int reportedCount, boolean truncated) {}

    private BreakBlocksPlayers() {}

    static boolean hasVipAccess(boolean authed, String tier) {
        return authed && tier != null && "VIP Treatment".equalsIgnoreCase(tier.trim());
    }

    static URI searchUri(Mode mode, String query) {
        String value = query == null ? "" : query.trim();
        if (mode == Mode.UUID) {
            if (!value.matches("[a-fA-F0-9]{32}|[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}")) {
                throw new IllegalArgumentException("Enter a UUID with 32 hex digits, with or without hyphens.");
            }
            value = value.replace("-", "").toLowerCase(Locale.ROOT);
            value = value.substring(0, 8) + "-" + value.substring(8, 12) + "-" + value.substring(12, 16)
                    + "-" + value.substring(16, 20) + "-" + value.substring(20);
        } else if (!value.matches("[A-Za-z0-9_]{1,16}")) {
            throw new IllegalArgumentException("Enter 1-16 letters, numbers or underscores for a player name.");
        }
        return URI.create("https://api.breakblocks.com/api/v0.1/players/find/"
                + (mode == Mode.UUID ? "uuid/" : "name/") + value + (mode == Mode.PARTIAL ? "/partial" : ""));
    }

    static HttpRequest searchRequest(Mode mode, String query, String key, Access access) {
        if (key == null || key.isBlank() || access == null || !access.reported() || !access.vip()) {
            throw new SecurityException("Player Search requires confirmed VIP Treatment access.");
        }
        return HttpRequest.newBuilder(searchUri(mode, query)).timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json").header("User-Agent", ReleaseInfo.USER_AGENT)
                .header("Authorization", "Bearer " + key.trim()).GET().build();
    }

    static Access parseAccess(String json) {
        Map<?, ?> top = object(SimpleJson.parse(json));
        Map<?, ?> data = object(top.get("data"));
        Object auth = first(top, data, "authed"), tier = first(top, data, "patreon_tier");
        return new Access(auth != null, Boolean.TRUE.equals(auth) || "true".equalsIgnoreCase(String.valueOf(auth)), text(tier));
    }

    static Result parse(String json) {
        Map<?, ?> top = object(SimpleJson.parse(json));
        if (top.isEmpty() || Boolean.FALSE.equals(top.get("success"))) {
            throw new IllegalArgumentException("BreakBlocks did not complete the player search.");
        }
        Map<?, ?> data = top.get("data") instanceof Map<?, ?> nested ? nested : top;
        if (Boolean.FALSE.equals(data.get("success"))) throw new IllegalArgumentException("Player search was unsuccessful.");
        Object rows = data.get("players");
        List<?> raw = rows instanceof List<?> list ? list
                : data.get("player") instanceof Map<?, ?> player ? List.of(player) : List.of();
        if (!(rows instanceof List<?>) && !data.containsKey("player") && !data.containsKey("count")) {
            throw new IllegalArgumentException("BreakBlocks returned an unrecognised player response.");
        }
        List<Player> players = new ArrayList<>();
        boolean truncated = raw.size() > MAX_RECORDS;
        for (Object row : raw.subList(0, Math.min(raw.size(), MAX_RECORDS))) {
            if (!(row instanceof Map<?, ?> player)) continue;
            List<Name> names = new ArrayList<>();
            if (player.get("name_history") instanceof List<?> history) {
                truncated |= history.size() > MAX_RECORDS;
                for (Object item : history.subList(0, Math.min(history.size(), MAX_RECORDS))) {
                    Map<?, ?> name = object(item);
                    if (!text(name.get("username")).isBlank()) names.add(new Name(text(name.get("username")), text(name.get("changed_at"))));
                }
            }
            Map<String, Server> servers = new LinkedHashMap<>();
            if (player.get("connected_servers") instanceof List<?> connected) {
                truncated |= connected.size() > MAX_RECORDS;
                for (Object item : connected.subList(0, Math.min(connected.size(), MAX_RECORDS))) {
                    Map<?, ?> server = object(item);
                    String address = text(server.get("address")).trim();
                    int port = server.containsKey("port") ? number(server.get("port"), -1) : 25565;
                    if (address.isBlank() || address.length() > 253 || address.chars().anyMatch(Character::isWhitespace)
                            || address.contains("/") || port < 1 || port > 65535) continue;
                    Server entry = new Server(address, port, text(server.get("name")));
                    servers.putIfAbsent(entry.endpoint().toLowerCase(Locale.ROOT), entry);
                }
            }
            String uuid = text(player.get("uuid")), username = text(player.get("current_username"));
            if (uuid.isBlank() && username.isBlank()) continue;
            players.add(new Player(uuid, username.isBlank() ? "Unknown name" : username, List.copyOf(names), List.copyOf(servers.values())));
        }
        return new Result(List.copyOf(players), Math.max(players.size(), number(data.get("count"), players.size())), truncated);
    }

    private static Object first(Map<?, ?> top, Map<?, ?> nested, String key) {
        return top.containsKey(key) ? top.get(key) : nested.get(key);
    }
    private static Map<?, ?> object(Object value) { return value instanceof Map<?, ?> map ? map : Map.of(); }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private static int number(Object value, int fallback) {
        if (value instanceof Number numeric) {
            double raw = numeric.doubleValue();
            return Double.isFinite(raw) && raw == Math.rint(raw) && raw >= Integer.MIN_VALUE && raw <= Integer.MAX_VALUE
                    ? (int) raw : fallback;
        }
        try { return value == null ? fallback : Integer.parseInt(value.toString()); }
        catch (NumberFormatException ignored) { return fallback; }
    }
}
