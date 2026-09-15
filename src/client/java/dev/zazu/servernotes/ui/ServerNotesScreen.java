package dev.zazu.servernotes.ui;

import dev.zazu.servernotes.model.ServerPlayerRecord;
import dev.zazu.servernotes.model.ServerProfile;
import dev.zazu.servernotes.util.TimeUtil;
import dev.zazuzin.zst.ClipboardCompat;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class ServerNotesScreen extends BaseServerScreen {
    private static final int PLAYER_PAGE_SIZE = 5;

    private final boolean playersView;
    private int playerPage;

    public ServerNotesScreen(Screen parent, String profileKey) {
        this(parent, profileKey, false, 0);
    }

    private ServerNotesScreen(Screen parent, String profileKey, boolean playersView, int playerPage) {
        super(Component.literal(playersView ? "Server Players" : "Zazu's Server Notes"), parent, profileKey);
        this.playersView = playersView;
        this.playerPage = Math.max(0, playerPage);
    }

    @Override
    protected void init() {
        if (playersView) {
            initPlayersView();
            return;
        }
        initOverview();
    }

    private void initOverview() {
        int center = this.width / 2;
        int y = 84;
        int small = 98;

        addRenderableWidget(Button.builder(Component.literal("Notes"), button ->
            this.minecraft.gui.setScreen(new NotesListScreen(this, profileKey)))
            .bounds(center - 101, y, small, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Locations"), button ->
            this.minecraft.gui.setScreen(new LocationsListScreen(this, profileKey)))
            .bounds(center + 3, y, small, 20).build());

        y += 24;
        addRenderableWidget(Button.builder(Component.literal("Players"), button -> {
            try {
                System.out.println("[Zazu's Server Seeker] Opening saved Players view for " + profileKey);
                this.minecraft.gui.setScreen(new ServerNotesScreen(this, profileKey, true, 0));
            } catch (Throwable t) {
                System.err.println("[Zazu's Server Seeker] Could not open saved Players view: " + root(t));
            }
        }).bounds(center - 101, y, 202, 20).build());

        y += 24;
        addRenderableWidget(Button.builder(Component.literal("Add Quick Note"), button ->
            this.minecraft.gui.setScreen(new NoteEditScreen(this, profileKey, null, true)))
            .bounds(center - 101, y, 202, 20).build());

        y += 24;
        addRenderableWidget(Button.builder(Component.literal("Add Current Location"), button -> addCurrentLocation())
            .bounds(center - 101, y, 202, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Back"), button -> onClose())
            .bounds(center - 100, this.height - 28, 200, 20).build());
    }

    private void initPlayersView() {
        int center = width / 2;
        List<ServerPlayerRecord> players = safeOrderedPlayers();
        int maxPage = Math.max(0, (players.size() - 1) / PLAYER_PAGE_SIZE);
        if (playerPage > maxPage) playerPage = maxPage;

        int start = playerPage * PLAYER_PAGE_SIZE;
        int end = Math.min(players.size(), start + PLAYER_PAGE_SIZE);
        int left = center - 150;

        for (int i = start; i < end; i++) {
            ServerPlayerRecord record = players.get(i);
            if (!validPlayer(record)) continue;
            int y = 50 + (i - start) * 25;
            addRenderableWidget(Button.builder(Component.literal("Copy"), button -> {
                if (ClipboardCompat.copy(record.username())) {
                    button.setMessage(Component.literal("Copied"));
                }
            }).bounds(left + 248, y, 52, 20).build());
        }

        addRenderableWidget(Button.builder(Component.literal("< Previous"), button -> {
            if (playerPage > 0) {
                minecraft.gui.setScreen(new ServerNotesScreen(parent, profileKey, true, playerPage - 1));
            }
        }).bounds(center - 100, height - 52, 96, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Next >"), button -> {
            if ((playerPage + 1) * PLAYER_PAGE_SIZE < safeOrderedPlayers().size()) {
                minecraft.gui.setScreen(new ServerNotesScreen(parent, profileKey, true, playerPage + 1));
            }
        }).bounds(center + 4, height - 52, 96, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Back"), button -> onClose())
            .bounds(center - 100, height - 28, 200, 20).build());
    }

    private void addCurrentLocation() {
        if (this.minecraft.player == null || this.minecraft.level == null) {
            return;
        }
        this.minecraft.gui.setScreen(new LocationNameScreen(
            this,
            profileKey,
            this.minecraft.player.getX(),
            this.minecraft.player.getY(),
            this.minecraft.player.getZ(),
            this.minecraft.level.dimension().identifier().toString()
        ));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        if (playersView) {
            renderPlayersView(graphics);
            return;
        }

        ServerProfile profile = profile();
        centered(graphics, "Zazu's Server Notes", 18, 0xFFFFFFFF);
        centered(graphics, profile.displayAddress(), 34, 0xFFB0B0B0);

        int left = this.width / 2 - 101;
        graphics.text(this.font, "Notes: " + profile.notes().size(), left, 56, 0xFFFFFFFF, true);
        graphics.text(this.font, "Locations: " + profile.locations().size(), left + 104, 56, 0xFFFFFFFF, true);
        graphics.text(this.font, "Players: " + profile.players().size(), left, 68, 0xFFFFFFFF, true);
    }

    private void renderPlayersView(GuiGraphicsExtractor graphics) {
        try {
            ServerProfile profile = profile();
            List<ServerPlayerRecord> players = safeOrderedPlayers();
            int pages = Math.max(1, (players.size() + PLAYER_PAGE_SIZE - 1) / PLAYER_PAGE_SIZE);
            if (playerPage >= pages) playerPage = pages - 1;

            centered(graphics, "Players - " + profile.displayAddress(), 18, 0xFFFFFFFF);
            centered(graphics, "Tracked: " + players.size(), 34, 0xFFAAAAAA);

            if (players.isEmpty()) {
                centered(graphics, "No players recorded yet.", 76, 0xFFAAAAAA);
                centered(graphics, "Players are added automatically while connected.", 90, 0xFF888888);
                return;
            }

            int start = playerPage * PLAYER_PAGE_SIZE;
            int end = Math.min(players.size(), start + PLAYER_PAGE_SIZE);
            int left = width / 2 - 150;
            for (int i = start; i < end; i++) {
                ServerPlayerRecord record = players.get(i);
                if (!validPlayer(record)) continue;
                int y = 54 + (i - start) * 25;
                graphics.text(font, trim(record.username(), 28), left, y, 0xFFFFFFFF, true);
                graphics.text(font,
                    "First: " + TimeUtil.display(record.firstSeenAt()) + "  Seen: " + record.encounters(),
                    left, y + 11, 0xFF999999, true);
            }
            centered(graphics, "Page " + (playerPage + 1) + " / " + pages, height - 68, 0xFF888888);
        } catch (Throwable t) {
            centered(graphics, "Players", 18, 0xFFFFFFFF);
            centered(graphics, "Could not load saved player history.", 76, 0xFFFF7777);
            System.err.println("[Zazu's Server Seeker] Players view render failed: " + root(t));
        }
    }

    private List<ServerPlayerRecord> safeOrderedPlayers() {
        try {
            List<ServerPlayerRecord> players = new ArrayList<>();
            for (ServerPlayerRecord record : profile().players().values()) {
                if (validPlayer(record)) players.add(record);
            }
            players.sort(Comparator.comparing(ServerPlayerRecord::username, String.CASE_INSENSITIVE_ORDER));
            return players;
        } catch (Throwable t) {
            System.err.println("[Zazu's Server Seeker] Could not read saved player history: " + root(t));
            return List.of();
        }
    }

    private static boolean validPlayer(ServerPlayerRecord record) {
        return record != null && record.username() != null && !record.username().isBlank();
    }

    private static String root(Throwable t) {
        Throwable current = t;
        while (current != null && current.getCause() != null && current.getCause() != current) current = current.getCause();
        return String.valueOf(current == null ? t : current);
    }
}
