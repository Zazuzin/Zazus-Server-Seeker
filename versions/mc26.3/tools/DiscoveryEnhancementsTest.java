package dev.zazuzin.zst;

import dev.zazu.servernotes.model.*;
import dev.zazu.servernotes.service.*;
import dev.zazu.servernotes.storage.ServerProfileStore;
import dev.zazu.servernotes.util.ServerAddressNormalizer;
import java.nio.file.*;
import java.util.*;

/** Real packaged code, offline fixtures, no public-server or player lookups. */
public final class DiscoveryEnhancementsTest {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("zst-discovery-test-");
        System.setProperty("user.dir", temp.toString());
        try {
            ToolState.load();
            ServerSearchFilters filters = new ServerSearchFilters("uk", "eu-*", "anarchy &page=99", "ATM9");
            String uri = ServerFinderClient.buildBrowserPageUri(1, 0, 7, 0, 0, 7, 2, filters).toString();
            check(uri.contains("country=GB") && uri.contains("region=eu-*") && uri.contains("modpack=ATM9"), "Filters missing from request");
            check(uri.contains("motd=*anarchy+%26page%3D99*") && !uri.contains("&page=99"), "Filter text escaped into query parameters");
            check(!ServerFinderClient.buildBrowserPageUri(1, 0, 7, 0, 0, 7, 2).toString().contains("country="), "Blank filters changed default requests");
            check(ToolState.setBreakBlocksApiKey("fake-retained-key"), "Could not create key fixture");
            check(ToolState.setSearchFilters(filters), "Could not save detailed filters");
            ToolState.load();
            check(ToolState.activeSearchFilters().equals(filters) && ToolState.finderSourceIndex == 2, "Saved filters/source did not reload");
            check(ToolState.breakBlocksApiKey().equals("fake-retained-key"), "Saving filters changed API key");
            ToolState.searchFiltersEnabled = false; ToolState.save(); ToolState.load();
            check(!ToolState.activeSearchFilters().active() && ToolState.searchFilters.equals(filters), "Pausing filters lost their draft values");
            check(ToolState.setSearchFilters(ServerSearchFilters.EMPTY), "Could not clear filters");
            ToolState.load(); check(!ToolState.searchFilters.active(), "Cleared filters reappeared");
            for (String invalid : List.of("bad\nvalue", "x".repeat(129))) {
                boolean rejected = false;
                try { new ServerSearchFilters(invalid, "", "", ""); } catch (IllegalArgumentException expected) { rejected = true; }
                check(rejected, "Invalid filter accepted");
            }
            for (int height : new int[]{200, 258, 480}) {
                var layout = new ServerFinderClient.OverlayState(null, null, 412, height);
                int rows = ServerFinderClient.detailedFilterRows(layout);
                check(85 + (rows - 1) * 26 + 20 <= height - 58, "Detailed filter fields cover Apply/navigation");
            }

            String id = "12345678-1234-5678-9abc-123456789abc";
            var sample = PingPlayerSamples.parseStatus("{\"players\":{\"sample\":["
                    + "{\"id\":\"" + id + "\",\"name\":\"SamplePlayer\"},"
                    + "{\"id\":\"" + id + "\",\"name\":\"SamplePlayer\"},"
                    + "{\"id\":\"00000000-0000-0000-0000-000000000000\",\"name\":\"OtherPlayer\"},"
                    + "{\"name\":\"§aFake message\"},{\"name\":\"Click our site!\"}]}}");
            check(sample.size() == 2 && sample.get(1).uuid().isEmpty(), "Sample validation/UUID fallback failed");
            for (String empty : List.of("{}", "{\"players\":{\"online\":10}}", "{\"players\":null}", "bad JSON")) {
                check(PingPlayerSamples.parseStatus(empty).isEmpty(), "Absent/hidden/malformed sample invented players");
            }
            var store = new ServerProfileStore(temp.resolve("config"));
            var identity = ServerAddressNormalizer.normalize("sample.example:25565");
            var profile = store.getOrCreate(identity);
            check(profile.lastJoinedAt().isEmpty(), "A ping-only profile was marked as joined");
            var service = new ServerPlayerService(store);
            service.recordPingSample(profile, sample, "2026-10-24T10:00:00Z");
            String key = "uuid:" + id;
            ServerPlayerRecord player = profile.players().get(key);
            check(player != null && player.encounters() == 0 && player.pingSightings() == 1, "Ping counted as connected encounter");
            service.recordPingSample(profile, sample, "2026-10-24T10:00:01Z");
            check(player.pingSightings() == 1, "Two verification passes inflated sightings");
            service.recordPingSample(profile, sample, "2026-10-24T10:01:00Z");
            check(player.pingSightings() == 2 && player.lastSeenAt().equals("2026-10-24T10:01:00Z"), "Later ping was not recorded");
            service.recordPingSample(profile, List.of(), "2026-10-24T10:02:00Z");
            check(profile.players().size() == 2 && player.lastSeenAt().equals("2026-10-24T10:01:00Z"), "Missing sample erased history or invented departure");
            var connected = List.of(new ServerPlayerService.ObservedPlayer(key, id, "SamplePlayer"));
            service.sync(profile, connected, Set.of());
            check(player.encounters() == 1 && player.pingSightings() == 2, "Joining after a ping did not count one real encounter");

            String otherId = "87654321-1234-5678-9abc-123456789abc";
            var old = profile.players().get("name:otherplayer");
            var data = new LinkedHashMap<>(old.toMap()); data.put("notes", "Keep my player note");
            profile.players().put("name:otherplayer", ServerPlayerRecord.fromMap("name:otherplayer", data));
            service.sync(profile, List.of(new ServerPlayerService.ObservedPlayer("uuid:" + otherId, otherId, "OtherPlayer")), Set.of());
            var upgraded = profile.players().get("uuid:" + otherId);
            check(!profile.players().containsKey("name:otherplayer") && upgraded.notes().equals("Keep my player note")
                    && upgraded.pingSightings() == 2 && upgraded.encounters() == 1, "UUID discovery duplicated a name or lost history/notes");
            var reopened = new ServerProfileStore(temp.resolve("config")).find(identity.key()).orElseThrow();
            check(reopened.players().get(key).pingSightings() == 2 && reopened.players().get("uuid:" + otherId).notes().equals("Keep my player note"), "Sighting/notes did not survive storage reload");
            check(ServerProfileStore.SCHEMA_VERSION == 2, "Existing Server Notes schema was unnecessarily changed");
            System.out.println("Detailed filter persistence/encoding/layout and ping history/identity/compatibility checks passed.");
        } finally {
            try (var paths = Files.walk(temp)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
