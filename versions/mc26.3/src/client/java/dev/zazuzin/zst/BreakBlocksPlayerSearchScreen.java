package dev.zazuzin.zst;

import java.awt.Desktop;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** On-demand VIP player search. No credentials or player results are written to disk. */
final class BreakBlocksPlayerSearchScreen extends Screen {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private enum View { NOTICE, SEARCH, DETAILS }
    private record Line(String text, int x, int y, int color) {}

    private final Screen parent;
    private final List<Line> lines = new ArrayList<>();
    private View view = View.NOTICE;
    private BreakBlocksPlayers.Mode mode = BreakBlocksPlayers.Mode.EXACT;
    private BreakBlocksPlayers.Access access;
    private List<BreakBlocksPlayers.Player> players = List.of();
    private BreakBlocksPlayers.Player selected;
    private String query = "", status = "", detailStatus = "", verifiedKey = "";
    private boolean started, loading, closed, history;
    private int resultPage, detailPage;
    private long generation;
    private CompletableFuture<HttpResponse<String>> pending;

    BreakBlocksPlayerSearchScreen(Screen parent) {
        super(Component.literal("BreakBlocks Player Search"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        if (!started) { started = true; checkAccess(); return; }
        lines.clear();
        int w = Math.min(480, width - 24), x = (width - w) / 2;
        label("Player Search - VIP Treatment", x, 15, 0xFFFFFFFF);
        button("Back", x + w - 64, 10, 64, this::onClose);
        if (view == View.NOTICE) drawNotice(x, w);
        else if (view == View.SEARCH) drawSearch(x, w);
        else drawDetails(x, w);
    }

    private void drawNotice(int x, int w) {
        int y = paragraph("This feature is only available with BreakBlocks' VIP Treatment tier on Patreon.", x, 40, w, 0xFFFFFFFF, 3);
        y = paragraph("Link Patreon to your BreakBlocks account, then configure your own API key to unlock Player Search.", x, y + 5, w, 0xFFCCCCCC, 3);
        paragraph(status, x, y + 5, w, 0xFFFFCC66, Math.min(3, Math.max(1, (height - 80 - y - 5) / 10)));
        int half = (w - 6) / 2;
        button("Join VIP on Patreon", x, height - 76, half, () -> openExternal(BreakBlocksPlayers.PATREON_URL, false));
        button("BreakBlocks Account", x + half + 6, height - 76, half, () -> openExternal(BreakBlocksPlayers.ACCOUNT_URL, false));
        button("Open API Key Config", x, height - 52, half, () -> {
            ToolState.save();
            openExternal(ToolState.configFile().toAbsolutePath().toString(), true);
        });
        Button check = button(loading ? "Checking..." : "Check Access", x + half + 6, height - 52, half, this::checkAccess);
        check.active = !loading;
    }

    private void drawSearch(int x, int w) {
        int part = (w - 8) / 3;
        for (BreakBlocksPlayers.Mode choice : BreakBlocksPlayers.Mode.values()) {
            Button b = button(choice.label, x + choice.ordinal() * (part + 4), 38, part,
                    () -> { mode = choice; redraw(); });
            b.active = !loading && mode != choice;
        }
        EditBox input = new EditBox(font, x, 64, w - 86, 20, Component.literal("Player name or UUID"));
        input.setMaxLength(36);
        input.setValue(query);
        input.setResponder(value -> query = value);
        input.active = !loading;
        addRenderableWidget(input);
        Button search = button(loading ? "Searching..." : "Search", x + w - 80, 64, 80, this::search);
        search.active = !loading;
        paragraph(status, x, 90, w, 0xFFFFCC66, 2);
        label(fit("Recorded sightings; current online status is not confirmed.", w), x, 112, 0xFFAAAAAA);
        int rows = rowsPerPage();
        for (int i = 0; i < rows; i++) {
            int index = resultPage * rows + i;
            if (index >= players.size()) break;
            BreakBlocksPlayers.Player player = players.get(index);
            button(fit(player.username() + " | " + player.servers().size() + " recorded servers | Details", w - 12),
                    x, 128 + i * 23, w, () -> {
                        selected = player; detailPage = 0; history = false; detailStatus = ""; view = View.DETAILS; redraw();
                    });
        }
        navigation(x, w, resultPage, players.size(), () -> { resultPage--; redraw(); }, () -> { resultPage++; redraw(); });
    }

    private void drawDetails(int x, int w) {
        button("< Results", x, 38, 82, () -> { view = View.SEARCH; redraw(); });
        Button copy = button("Copy UUID", x + w - 92, 38, 92, () -> copy(selected.uuid()));
        copy.active = !selected.uuid().isBlank();
        label(fit(selected.username() + " | " + selected.uuid(), w), x, 66, 0xFFFFFFFF);
        int half = (w - 6) / 2;
        Button servers = button("Servers (" + selected.servers().size() + ")", x, 82, half,
                () -> { history = false; detailPage = 0; redraw(); });
        Button names = button("Name history (" + selected.names().size() + ")", x + half + 6, 82, half,
                () -> { history = true; detailPage = 0; redraw(); });
        servers.active = history; names.active = !history;
        int rows = rowsPerPage();
        int size = history ? selected.names().size() : selected.servers().size();
        for (int i = 0; i < rows; i++) {
            int index = detailPage * rows + i;
            if (index >= size) break;
            int y = 128 + i * 23;
            if (history) {
                BreakBlocksPlayers.Name name = selected.names().get(index);
                button(fit(name.username() + " | " + name.changedAt() + " | Copy name", w - 12), x, y, w, () -> copy(name.username()));
            } else {
                BreakBlocksPlayers.Server server = selected.servers().get(index);
                button(fit(server.endpoint() + " | " + server.name(), w - 128), x, y, w - 116, () -> copy(server.endpoint()));
                button("Copy IP", x + w - 112, y, 58, () -> copy(server.endpoint()));
                boolean saved = false;
                try { saved = ServerFinderClient.ServerListBridge.contains(minecraft, server.endpoint()); }
                catch (Throwable ignored) { }
                Button add = button(saved ? "Saved" : "Add", x + w - 50, y, 50, () -> addServer(server));
                add.active = !saved && !ToolState.isBlocked(server.endpoint());
            }
        }
        label(fit(!detailStatus.isBlank() ? detailStatus : size == 0 ? "No records returned for this view."
                : "Historical records; not a live player location.", w), x, 112, 0xFFAAAAAA);
        navigation(x, w, detailPage, size, () -> { detailPage--; redraw(); }, () -> { detailPage++; redraw(); });
    }

    private int rowsPerPage() { return Math.max(1, Math.min(8, (height - 180) / 23)); }

    private void navigation(int x, int w, int page, int size, Runnable previous, Runnable next) {
        int pages = Math.max(1, (size + rowsPerPage() - 1) / rowsPerPage());
        Button prev = button("< Previous", x, height - 28, 90, previous);
        Button more = button("Next >", x + w - 90, height - 28, 90, next);
        prev.active = !loading && page > 0;
        more.active = !loading && page + 1 < pages;
        label("Page " + (page + 1) + "/" + pages, x + (w - 60) / 2, height - 22, 0xFFCCCCCC);
    }

    private String currentKey() {
        ToolState.reloadBreakBlocksApiKey();
        String key = ToolState.breakBlocksApiKey();
        BreakBlocksAccount.configure(key);
        return key;
    }

    private void checkAccess() {
        access = null; verifiedKey = ""; players = List.of(); selected = null; view = View.NOTICE;
        String key = currentKey();
        if (key.isBlank()) { loading = false; status = "No API key configured. Add your key, then choose Check Access."; redraw(); return; }
        send(ServerFinderClient.buildBreakBlocksRequest(BreakBlocksPlayers.ACCESS_URI, key), key, true);
    }

    private void search() {
        if (loading) return;
        String key = currentKey();
        if (!key.equals(verifiedKey) || access == null || !access.vip()) { checkAccess(); return; }
        try {
            HttpRequest request = BreakBlocksPlayers.searchRequest(mode, query, key, access);
            players = List.of(); resultPage = 0;
            send(request, key, false);
        } catch (IllegalArgumentException | SecurityException error) {
            status = error.getMessage(); redraw();
        }
    }

    private void send(HttpRequest request, String key, boolean checking) {
        if (pending != null) pending.cancel(true);
        long token = ++generation;
        BreakBlocksRateBudget.Snapshot allowance = BreakBlocksRateBudget.snapshot(true);
        if (allowance.paused() || (!allowance.unlimited() && allowance.remaining() <= 0)) {
            loading = false; status = "BreakBlocks rate limit: try again in " + allowance.resetSeconds() + " seconds."; redraw(); return;
        }
        loading = true;
        status = checking ? "Checking VIP access with BreakBlocks..." : "Searching BreakBlocks...";
        redraw();
        BreakBlocksRateBudget.recordSearchRequest(true);
        pending = HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        pending.whenComplete((response, error) -> Reflection.execute(minecraft, () -> {
            if (closed || token != generation) return;
            loading = false;
            if (!key.equals(currentKey())) { deny("API key changed. Choose Check Access again."); return; }
            if (error != null || response == null) {
                status = "Could not reach BreakBlocks. Check your connection and try again."; redraw(); return;
            }
            BreakBlocksRateBudget.observe(response, true);
            int code = response.statusCode();
            if (code == 401) { BreakBlocksAccount.rejected(); deny("API key rejected (HTTP 401). Check the key in your config."); return; }
            if (code == 403) { deny("BreakBlocks denied access (HTTP 403). Check your linked VIP membership."); return; }
            if (code == 429) {
                BreakBlocksRateBudget.noteRateLimit(response);
                status = "Rate limit reached. Try again in " + BreakBlocksRateBudget.snapshot(true).resetSeconds() + " seconds.";
                redraw(); return;
            }
            if (code == 404 && !checking) { players = List.of(); status = "No player record found (HTTP 404)."; redraw(); return; }
            if (code / 100 != 2) { status = "BreakBlocks returned HTTP " + code + ". Please try again later."; redraw(); return; }
            try {
                if (response.body().length() > 2_000_000) throw new IllegalArgumentException("Response too large");
                BreakBlocksPlayers.Access reported = BreakBlocksPlayers.parseAccess(response.body());
                if (reported.reported()) BreakBlocksAccount.observe(reported.authed(), reported.tier());
                if (checking) {
                    if (!reported.reported()) { deny("BreakBlocks did not confirm your account tier. Try Check Access again."); return; }
                    if (!reported.vip()) { deny(reported.authed() ? "Your current tier does not include VIP Treatment." : "BreakBlocks did not authenticate this API key."); return; }
                    access = reported; verifiedKey = key; view = View.SEARCH;
                    status = "VIP Treatment confirmed. Enter a player name or UUID.";
                } else {
                    if (reported.reported() && !reported.vip()) { deny("VIP access was not confirmed. Check your membership and API key."); return; }
                    BreakBlocksPlayers.Result result = BreakBlocksPlayers.parse(response.body());
                    players = result.players(); resultPage = 0;
                    status = players.size() + " player records found."
                            + (result.truncated() ? " Large result limited to 1,000 records per list; refine your search." : "");
                }
            } catch (RuntimeException malformed) { status = "Could not read BreakBlocks' response. Please try again later."; }
            redraw();
        }));
    }

    private void deny(String reason) {
        access = null; verifiedKey = ""; players = List.of(); selected = null;
        view = View.NOTICE; loading = false; status = reason; redraw();
    }

    private void addServer(BreakBlocksPlayers.Server server) {
        try {
            ServerFinderClient.ServerRecord record = new ServerFinderClient.ServerRecord(server.address(), server.port(),
                    "Unknown", 0, 0, server.name(), "", "", "", "", "", "", false, false, List.of(), -1, "BreakBlocks Player Search (historical)");
            boolean added = ServerFinderClient.ServerListBridge.addBrowsed(minecraft, record);
            detailStatus = added ? "Added " + server.endpoint() + " to Servers." : "Server is already saved or blocked.";
        } catch (Throwable error) { detailStatus = "Could not save this server."; }
        redraw();
    }

    private void copy(String text) {
        detailStatus = ClipboardCompat.copy(text) ? "Copied to clipboard." : "Could not copy to clipboard.";
        redraw();
    }

    private void openExternal(String target, boolean file) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(file ? Desktop.Action.OPEN : Desktop.Action.BROWSE)) {
                if (file) Desktop.getDesktop().open(Path.of(target).toFile());
                else Desktop.getDesktop().browse(URI.create(target));
            } else {
                String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
                List<String> command = os.contains("win") ? List.of("rundll32", "url.dll,FileProtocolHandler", file ? Path.of(target).toUri().toString() : target)
                        : List.of(os.contains("mac") ? "open" : "xdg-open", target);
                new ProcessBuilder(command).start();
            }
        } catch (Exception error) { status = "Could not open the link or file. Use Finder Settings to configure your API key."; redraw(); }
    }

    private Button button(String text, int x, int y, int w, Runnable pressed) {
        return addRenderableWidget(Button.builder(Component.literal(text), button -> pressed.run()).bounds(x, y, w, 20).build());
    }
    private void label(String text, int x, int y, int color) { lines.add(new Line(text, x, y, color)); }
    private String fit(String text, int w) {
        if (font.width(text) <= w) return text;
        while (!text.isEmpty() && font.width(text + "...") > w) text = text.substring(0, text.length() - 1);
        return text + "...";
    }
    private int paragraph(String text, int x, int y, int w, int color, int maxLines) {
        String remaining = text;
        for (int line = 0; line < maxLines && !remaining.isEmpty(); line++) {
            String part = remaining;
            while (part.length() > 1 && font.width(part) > w) part = part.substring(0, part.length() - 1);
            if (part.length() < remaining.length() && part.lastIndexOf(' ') > 0) part = part.substring(0, part.lastIndexOf(' '));
            label(part, x, y, color); y += 10;
            remaining = remaining.substring(part.length()).stripLeading();
        }
        return y;
    }
    private void redraw() { clearWidgets(); init(); }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        for (Line line : lines) graphics.text(font, line.text(), line.x(), line.y(), line.color(), true);
    }
    @Override
    public void removed() {
        closed = true; generation++;
        if (pending != null) pending.cancel(true);
        players = List.of(); selected = null; access = null; verifiedKey = "";
        super.removed();
    }
    @Override
    public void onClose() { this.minecraft.gui.setScreen(parent); }
}
