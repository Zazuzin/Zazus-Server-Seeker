package dev.zazu.servernotes.client;

import dev.zazu.servernotes.service.CurrentServerService;
import dev.zazu.servernotes.service.ServerLocationService;
import dev.zazu.servernotes.service.ServerNotesService;
import dev.zazu.servernotes.service.ServerPlayerService;
import dev.zazu.servernotes.service.PlayerTrackingService;
import dev.zazu.servernotes.storage.ServerProfileStore;

public final class ServerNotesApp {
    private final ServerProfileStore store;
    private final ServerNotesService notes;
    private final ServerLocationService locations;
    private final ServerPlayerService players;
    private final CurrentServerService currentServer;
    private final PlayerTrackingService playerTracking;

    public ServerNotesApp(ServerProfileStore store) {
        this.store = store;
        this.notes = new ServerNotesService(store);
        this.locations = new ServerLocationService(store);
        this.players = new ServerPlayerService(store);
        this.currentServer = new CurrentServerService(store);
        this.playerTracking = new PlayerTrackingService(currentServer, players);
    }

    public ServerProfileStore store() { return store; }
    public ServerNotesService notes() { return notes; }
    public ServerLocationService locations() { return locations; }
    public ServerPlayerService players() { return players; }
    public CurrentServerService currentServer() { return currentServer; }
    public PlayerTrackingService playerTracking() { return playerTracking; }
}
