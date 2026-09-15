package dev.zazu.servernotes.service;

import dev.zazu.servernotes.model.ServerProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public final class PlayerTrackingService {
    private static final int POLL_INTERVAL_TICKS = 20;

    private final CurrentServerService currentServer;
    private final ServerPlayerService players;
    private final LinkedHashSet<String> onlineKeys = new LinkedHashSet<>();
    private String onlineProfileKey;
    private int ticksUntilPoll;

    public PlayerTrackingService(CurrentServerService currentServer, ServerPlayerService players) {
        this.currentServer = currentServer;
        this.players = players;
    }

    public void tick(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.getConnection() == null) {
            reset();
            return;
        }

        // Player-list tracking only needs a one-second cadence. Previously the
        // server profile/address was resolved every client tick before this
        // interval check, which added avoidable in-game overhead.
        if (ticksUntilPoll-- > 0) return;
        ticksUntilPoll = POLL_INTERVAL_TICKS - 1;

        Optional<ServerProfile> profile = currentServer.currentProfile(minecraft);
        if (profile.isEmpty()) {
            reset();
            return;
        }

        ServerProfile serverProfile = profile.get();
        if (!serverProfile.key().equals(onlineProfileKey)) {
            onlineProfileKey = serverProfile.key();
            onlineKeys.clear();
        }

        LocalIdentity local = readLocalIdentity(minecraft);
        players.removeLocalPlayer(serverProfile, local.uuid(), local.username());

        Collection<PlayerInfo> online = minecraft.getConnection().getOnlinePlayers();
        List<ServerPlayerService.ObservedPlayer> observed = new ArrayList<>();
        if (online != null) {
            for (PlayerInfo info : online) {
                ServerPlayerService.ObservedPlayer player = read(info);
                if (player != null && !isLocalPlayer(player, local)) {
                    observed.add(player);
                }
            }
        }

        Set<String> previous = new LinkedHashSet<>(onlineKeys);
        players.sync(serverProfile, observed, previous);
        onlineKeys.clear();
        onlineKeys.addAll(players.keys(observed));
    }

    public boolean isOnline(String profileKey, String playerKey) {
        return profileKey != null && profileKey.equals(onlineProfileKey) && onlineKeys.contains(playerKey);
    }

    public int onlineCount(String profileKey) {
        return profileKey != null && profileKey.equals(onlineProfileKey) ? onlineKeys.size() : 0;
    }

    public Set<String> onlineKeys(String profileKey) {
        if (profileKey == null || !profileKey.equals(onlineProfileKey)) {
            return Collections.emptySet();
        }
        return Set.copyOf(onlineKeys);
    }

    private void reset() {
        onlineProfileKey = null;
        onlineKeys.clear();
        ticksUntilPoll = 0;
    }

    private static ServerPlayerService.ObservedPlayer read(PlayerInfo info) {
        if (info == null) {
            return null;
        }
        Object profile = info.getProfile();
        if (profile == null) {
            return null;
        }

        String name = invokeString(profile, "name", "getName");
        if (name == null || name.isBlank()) {
            return null;
        }
        String uuid = invokeString(profile, "id", "getId");
        String key = uuid != null && !uuid.isBlank()
            ? "uuid:" + uuid.toLowerCase(Locale.ROOT)
            : "name:" + name.toLowerCase(Locale.ROOT);
        return new ServerPlayerService.ObservedPlayer(key, uuid == null ? "" : uuid, name);
    }

    private static LocalIdentity readLocalIdentity(Minecraft minecraft) {
        Object localProfile = invokeNoArg(minecraft, "getGameProfile");
        if (localProfile == null) {
            return LocalIdentity.EMPTY;
        }
        String uuid = invokeString(localProfile, "id", "getId");
        String username = invokeString(localProfile, "name", "getName");
        return new LocalIdentity(uuid == null ? "" : uuid, username == null ? "" : username);
    }

    private static boolean isLocalPlayer(ServerPlayerService.ObservedPlayer player, LocalIdentity local) {
        if (player == null) {
            return false;
        }
        if (!local.uuid().isBlank() && !player.uuid().isBlank()
            && local.uuid().equalsIgnoreCase(player.uuid())) {
            return true;
        }
        return !local.username().isBlank() && player.username() != null
            && local.username().equalsIgnoreCase(player.username());
    }

    private static Object invokeNoArg(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (ReflectiveOperationException | SecurityException ignored) {
            return null;
        }
    }

    private static String invokeString(Object target, String... methodNames) {
        for (String methodName : methodNames) {
            try {
                Method method = target.getClass().getMethod(methodName);
                Object value = method.invoke(target);
                if (value != null) {
                    return value.toString();
                }
            } catch (ReflectiveOperationException | SecurityException ignored) {
            }
        }
        return null;
    }

    private record LocalIdentity(String uuid, String username) {
        private static final LocalIdentity EMPTY = new LocalIdentity("", "");
    }
}
