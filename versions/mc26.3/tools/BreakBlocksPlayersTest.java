package dev.zazuzin.zst;

import java.util.List;

/** Offline protocol and access-boundary regression checks; never queries real players. */
public final class BreakBlocksPlayersTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static void rejected(Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException | SecurityException expected) { return; }
        throw new AssertionError("Invalid or unauthorised request was accepted");
    }
    public static void main(String[] args) {
        var vip = new BreakBlocksPlayers.Access(true, true, "VIP Treatment");
        for (String tier : new String[]{"", "Free", "Advanced API Access", "Not VIP Treatment", "VIP Treatment expired", "VIP"}) {
            check(!BreakBlocksPlayers.hasVipAccess(true, tier), "Non-VIP tier granted access: " + tier);
        }
        check(BreakBlocksPlayers.hasVipAccess(true, " vip treatment "), "Case/space normalization failed");
        check(!BreakBlocksPlayers.hasVipAccess(false, "VIP Treatment"), "Unauthenticated VIP granted access");
        check(!BreakBlocksPlayers.parseAccess("{}").vip(), "Missing auth metadata granted access");
        check(BreakBlocksPlayers.parseAccess("{\"data\":{\"authed\":true,\"patreon_tier\":\"VIP Treatment\"}}").vip(), "Nested tier failed");
        check(!BreakBlocksPlayers.parseAccess("{\"authed\":false,\"patreon_tier\":\"VIP Treatment\"}").vip(), "False auth granted access");
        for (BreakBlocksPlayers.Mode mode : BreakBlocksPlayers.Mode.values()) {
            String query = mode == BreakBlocksPlayers.Mode.UUID ? "550e8400e29b41d4a716446655440000" : "ExamplePlayer";
            rejected(() -> BreakBlocksPlayers.searchRequest(mode, query, "", vip));
            rejected(() -> BreakBlocksPlayers.searchRequest(mode, query, "test-key", null));
            rejected(() -> BreakBlocksPlayers.searchRequest(mode, query, "test-key", new BreakBlocksPlayers.Access(false, true, "VIP Treatment")));
            rejected(() -> BreakBlocksPlayers.searchRequest(mode, query, "test-key", new BreakBlocksPlayers.Access(true, true, "Advanced API Access")));
            var request = BreakBlocksPlayers.searchRequest(mode, query, "test-key", vip);
            check(request.headers().firstValue("Authorization").orElse("").equals("Bearer test-key"), "Missing Bearer header");
            check(request.uri().getHost().equals("api.breakblocks.com"), "Wrong API host");
            check(request.uri().getRawQuery() == null, "API key leaked into query");
        }
        check(BreakBlocksPlayers.searchUri(BreakBlocksPlayers.Mode.PARTIAL, "Example").getPath().endsWith("/Example/partial"), "Partial mode failed");
        check(BreakBlocksPlayers.searchUri(BreakBlocksPlayers.Mode.UUID, "550e8400e29b41d4a716446655440000")
                .getPath().endsWith("550e8400-e29b-41d4-a716-446655440000"), "UUID normalization failed");
        for (String query : new String[]{"", "../players", "Name?key=secret", "bad name", "x/y", "abcdefghijklmnopq"}) {
            rejected(() -> BreakBlocksPlayers.searchUri(BreakBlocksPlayers.Mode.EXACT, query));
        }
        rejected(() -> BreakBlocksPlayers.searchUri(BreakBlocksPlayers.Mode.UUID, "550e8400-e29b-41d4-a716-nope"));

        String player = """
            {"uuid":"550e8400-e29b-41d4-a716-446655440000","current_username":"ExamplePlayer",
             "name_history":[{"username":"PreviousName","changed_at":"2025-06-15T10:30:00Z"}],
             "connected_servers":[{"address":"play.example.com","port":25565,"name":"Example"},
                                  {"address":"PLAY.EXAMPLE.COM","port":25565},
                                  {"address":"2001:db8::1","port":25566},
                                  {"address":"invalid.example","port":99999}]}
            """;
        var byName = BreakBlocksPlayers.parse("{\"success\":true,\"count\":1,\"players\":[" + player + "]}");
        var byUuid = BreakBlocksPlayers.parse("{\"success\":true,\"player\":" + player + "}");
        check(byName.players().equals(byUuid.players()), "Name and UUID response shapes differ");
        var record = byName.players().get(0);
        check(record.servers().size() == 2, "Duplicate/invalid servers were not filtered");
        check(record.servers().get(1).endpoint().equals("[2001:db8::1]:25566"), "IPv6 endpoint broken");
        check(record.names().get(0).username().equals("PreviousName"), "Name history missing");
        check(BreakBlocksPlayers.parse("{\"data\":{\"players\":[],\"count\":0}}").players().isEmpty(), "Empty/nested result failed");
        rejected(() -> BreakBlocksPlayers.parse("{\"success\":false,\"players\":[]}"));
        rejected(() -> BreakBlocksPlayers.parse("{\"data\":{\"success\":false,\"players\":[]}}"));
        rejected(() -> BreakBlocksPlayers.parse("{\"message\":\"not a result\"}"));
        rejected(() -> BreakBlocksPlayers.parse("not json"));
        String many = "{\"players\":[" + String.join(",", java.util.Collections.nCopies(1001, player)) + "]}";
        var limited = BreakBlocksPlayers.parse(many);
        check(limited.truncated() && limited.players().size() == 1000, "Result bound failed");
        System.out.println("VIP player-search access, request validation and response parsing passed.");
    }
}
