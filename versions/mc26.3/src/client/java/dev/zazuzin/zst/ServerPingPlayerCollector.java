package dev.zazuzin.zst;

import dev.zazu.servernotes.client.ZazusServerNotesClient;
import dev.zazu.servernotes.model.ServerIdentity;
import dev.zazu.servernotes.service.PingPlayerSamples;
import dev.zazu.servernotes.service.ServerPlayerService.ObservedPlayer;
import dev.zazu.servernotes.util.ServerAddressNormalizer;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.*;

/** Main-thread bridge from actual ping replies to local Server Notes. No extra pings. */
public final class ServerPingPlayerCollector {
    private static final Map<String, Sample> PENDING = new LinkedHashMap<>();
    private static boolean loggedFailure;
    private record Sample(List<ObservedPlayer> players, Instant observedAt) {}
    private ServerPingPlayerCollector() {}
    static void clearPending() { PENDING.clear(); }

    /** The vanilla pinger's listener captures the exact ServerData it is updating. */
    public static void nativeStatus(Object listener, Object status) {
        if (!ToolState.recordPingPlayers || listener == null || status == null) return;
        try {
            Object server = null;
            for (Class<?> type = listener.getClass(); type != null && server == null; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (!field.getType().getName().equals("net.minecraft.client.multiplayer.ServerData")) continue;
                    field.setAccessible(true);
                    server = field.get(listener);
                    if (server != null) break;
                }
            }
            if (server == null) return;
            Object address = Reflection.getField(server, "ip");
            if (!(address instanceof String endpoint) || endpoint.isBlank()) return;
            Object players = Reflection.invokeQuiet(status, "players");
            if (players instanceof Optional<?> optional) players = optional.orElse(null);
            Object profiles = Reflection.invokeQuiet(players, "sample");
            if (profiles instanceof Object[] array) profiles = Arrays.asList(array);
            if (!(profiles instanceof Iterable<?> values)) return;
            List<ObservedPlayer> sample = PingPlayerSamples.fromProfiles(values);
            Instant at = Instant.now();
            Object client = RuntimeAccess.minecraftInstance();
            Reflection.execute(client, () -> record(client, endpoint, sample, at, true));
        } catch (Exception | LinkageError failure) { report(failure); }
    }

    static void finderReply(Object client, String endpoint, List<ObservedPlayer> players) {
        if (!ToolState.recordPingPlayers || players.isEmpty()) return;
        Instant at = Instant.now();
        Reflection.execute(client, () -> record(client, endpoint, players, at, false));
    }

    private static void record(Object client, String endpoint, List<ObservedPlayer> players, Instant at, boolean savedServer) {
        if (!ToolState.recordPingPlayers || players.isEmpty()) return;
        try {
            ServerIdentity identity = ServerAddressNormalizer.normalize(endpoint);
            List<ObservedPlayer> sample = withoutLocalPlayer(client, players);
            if (sample.isEmpty()) return;
            var app = ZazusServerNotesClient.app();
            var existing = app.store().find(identity.key());
            if (savedServer || existing.isPresent()) {
                var profile = existing.orElseGet(() -> app.store().getOrCreate(identity));
                app.players().recordPingSample(profile, sample, at.toString());
                PENDING.remove(identity.key());
            } else {
                // Unsaved Finder candidates get a short-lived, bounded memory cache.
                // A profile is written only if that server is explicitly/automatically added.
                PENDING.entrySet().removeIf(entry -> entry.getValue().observedAt().isBefore(at.minusSeconds(300)));
                PENDING.put(identity.key(), new Sample(sample, at));
                while (PENDING.size() > 512) PENDING.remove(PENDING.keySet().iterator().next());
            }
        } catch (IllegalStateException uninitialized) {
            // Status probes can also run in the offline regression harness.
        } catch (Exception | LinkageError failure) { report(failure); }
    }

    static void serverAdded(Object client, String endpoint) {
        if (!ToolState.recordPingPlayers) { PENDING.clear(); return; }
        try {
            ServerIdentity identity = ServerAddressNormalizer.normalize(endpoint);
            Sample sample = PENDING.remove(identity.key());
            if (sample == null || sample.observedAt().isBefore(Instant.now().minusSeconds(300))) return;
            record(client, endpoint, sample.players(), sample.observedAt(), true);
        } catch (Exception | LinkageError failure) { report(failure); }
    }

    private static List<ObservedPlayer> withoutLocalPlayer(Object client, List<ObservedPlayer> players) {
        Object profile = Reflection.invokeQuiet(client, "getGameProfile");
        if (profile == null) return players;
        List<ObservedPlayer> local = PingPlayerSamples.fromProfiles(List.of(profile));
        if (local.isEmpty()) return players;
        ObservedPlayer own = local.get(0);
        return players.stream().filter(player -> !player.username().equalsIgnoreCase(own.username())
                && (own.uuid().isBlank() || !own.uuid().equalsIgnoreCase(player.uuid()))).toList();
    }

    private static void report(Throwable failure) {
        if (!loggedFailure) {
            loggedFailure = true;
            System.err.println("[Zazu's Server Seeker] Could not record a ping sample: " + failure.getClass().getSimpleName());
        }
    }
}
