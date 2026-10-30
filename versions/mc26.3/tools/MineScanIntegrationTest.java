package dev.zazuzin.zst;

import java.lang.reflect.*;
import java.net.URI;
import java.util.*;

/** Actual packaged selection, request and response code; no public-server pings. */
public final class MineScanIntegrationTest {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static Method choose;
    private static Map<String, Object> providers;
    private static String next(ServerFinderClient.OverlayState state) throws Exception {
        Object result = choose.invoke(null, state);
        return result == null ? "" : result.toString();
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void disable(ServerFinderClient.OverlayState state, String provider) {
        ((Set) state.disabledProviders).add(providers.get(provider));
    }

    public static void main(String[] args) throws Exception {
        Class<?> type = Class.forName("dev.zazuzin.zst.ServerFinderClient$Provider");
        providers = new HashMap<>();
        for (Object value : type.getEnumConstants()) providers.put(value.toString(), value);
        choose = ServerFinderClient.class.getDeclaredMethod("chooseProviderForNextRequest", ServerFinderClient.OverlayState.class);
        choose.setAccessible(true);
        var auto = new ServerFinderClient.OverlayState(null, null, 600, 400);
        check(next(auto).equals("BREAKBLOCKS"), "BreakBlocks lost first priority");
        disable(auto, "BREAKBLOCKS"); check(next(auto).equals("MINESCAN"), "MineScan is not the second fallback");
        disable(auto, "MINESCAN"); check(next(auto).equals("CORNBREAD"), "Remaining provider fallback failed");
        disable(auto, "CORNBREAD"); check(next(auto).isEmpty(), "Exhausted providers were retried");
        var all = new ServerFinderClient.OverlayState(null, null, 600, 400); all.sourceIndex = 1;
        for (String wanted : List.of("BREAKBLOCKS", "MINESCAN", "CORNBREAD", "BREAKBLOCKS"))
            check(next(all).equals(wanted), "All Sources rotation changed priority");
        var manual = new ServerFinderClient.OverlayState(null, null, 600, 400);
        manual.sourceIndex = 3; check(next(manual).equals("CORNBREAD"), "Saved Cornbread selection changed meaning");
        manual.sourceIndex = 4; check(next(manual).equals("MINESCAN"), "Saved MineScan selection changed meaning");
        manual.searchFilters = new ServerSearchFilters("GB", "", "", "");
        check(next(manual).equals("BREAKBLOCKS"), "Detailed filters fell back to an unfiltered provider");
        disable(manual, "BREAKBLOCKS"); check(next(manual).isEmpty(), "Unavailable filtered search leaked to another provider");

        URI uri = ServerFinderClient.buildMineScanUri(5, "26.2&count=100");
        check(uri.getHost().equals("data.minescan.xyz") && uri.getPath().equals("/servers/random"), "Wrong MineScan endpoint");
        check(uri.getRawQuery().equals("count=20&minPlayers=5&version=26.2%26count%3D100"), "Request limits/encoding differ from the documented API");
        check(ServerFinderClient.buildProviderRequest(uri).headers().firstValue("Authorization").isEmpty(), "MineScan received the BreakBlocks key");
        check(!ServerFinderClient.buildMineScanUri(-1, "").toString().contains("version="), "Any-version search sends an empty version filter");

        var rows = ServerFinderClient.parseMineScanResult("""
            {"count":2,"servers":[
              {"serverip":"example.invalid","port":25570,"version":null,"rawVersion":"Fabric 26.2",
               "authmode":"offline","onlinePlayers":5,"maxPlayers":20,"motd":null,
               "lastSeen":"2026-09-19T22:11:52.029863+00:00",
               "geolocation":{"country":"GB","countryName":"United Kingdom","city":"London"}},
              {"serverip":"whitelist.invalid","port":25565,"version":"26.3","authmode":"whitelist",
               "onlinePlayers":0,"maxPlayers":20,"geolocation":null}]}
            """);
        check(rows.size() == 2, "Documented response shape did not parse");
        var first = rows.get(0);
        check(first.version().equals("Fabric 26.2") && first.offlineMode() && first.port() == 25570, "Raw version/auth/port mapping failed");
        check(first.country().equals("United Kingdom") && first.countryCode().equals("GB") && first.city().equals("London"), "Geolocation mapping failed");
        check(first.motd().isEmpty() && first.lastPing().contains("+00:00"), "Nullable fields/timestamp mapping failed");
        check(rows.get(1).whitelisted() && rows.get(1).country().isEmpty(), "Whitelist or missing-geolocation mapping failed");
        System.out.println("MineScan Auto/All Sources priority, saved selections, filters, request encoding and v2 response checks passed.");
    }
}
