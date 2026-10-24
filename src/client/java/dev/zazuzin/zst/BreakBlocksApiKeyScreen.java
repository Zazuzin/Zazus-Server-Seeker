package dev.zazuzin.zst;

import java.awt.Desktop;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/** Masked, per-instance API-key editor. Values never appear in status text or logs. */
final class BreakBlocksApiKeyScreen extends Screen {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final Screen parent;
    private final Runnable afterClose;
    private KeyBox keyBox;
    private String draft = "", status = "";
    private boolean loaded, shown, checking, closed;
    private long generation;
    private CompletableFuture<HttpResponse<String>> pending;

    BreakBlocksApiKeyScreen(Screen parent) { this(parent, () -> {}); }

    BreakBlocksApiKeyScreen(Screen parent, Runnable afterClose) {
        super(Component.literal("BreakBlocks API Key"));
        this.parent = parent;
        this.afterClose = afterClose;
    }

    @Override
    protected void init() {
        closed = false;
        if (!loaded) {
            ToolState.reloadBreakBlocksApiKey();
            draft = ToolState.breakBlocksApiKey();
            loaded = true;
        }
        int w = Math.min(420, width - 24), x = (width - w) / 2;
        keyBox = new KeyBox(x, 65, w - 70, 20);
        keyBox.setMaxLength(512);
        keyBox.setValue(draft);
        keyBox.setResponder(value -> draft = value);
        keyBox.addFormatter((value, offset) -> FormattedCharSequence.forward(shown ? value : "*".repeat(value.length()), Style.EMPTY));
        keyBox.active = !checking;
        addRenderableWidget(keyBox);
        Button visibility = button(shown ? "Hide" : "Show", x + w - 64, 65, 64, () -> { shown = !shown; redraw(); });
        visibility.active = !checking;
        int third = (w - 12) / 3;
        Button save = button("Save", x, height - 76, third, this::saveKey);
        Button test = button(checking ? "Checking..." : "Save & Test", x + third + 6, height - 76, third, this::testKey);
        save.active = !checking; test.active = !checking;
        button("Clear Key", x + 2 * (third + 6), height - 76, w - 2 * (third + 6), () -> { draft = ""; saveKey(); });
        int half = (w - 6) / 2;
        button("Get API Key", x, height - 52, half, this::openAccount);
        button("Back", x + half + 6, height - 52, half, this::onClose);
    }

    private boolean saveKey() {
        cancelCheck();
        try {
            if (!ToolState.setBreakBlocksApiKey(draft)) {
                status = "Could not save the key. Check this instance's folder permissions.";
                redraw(); return false;
            }
            draft = ToolState.breakBlocksApiKey();
            shown = false;
            status = draft.isBlank() ? "API key cleared." : "API key saved for this instance.";
            redraw(); return true;
        } catch (IllegalArgumentException invalid) {
            status = invalid.getMessage(); redraw(); return false;
        }
    }

    private void testKey() {
        if (!saveKey()) return;
        String key = ToolState.breakBlocksApiKey();
        if (key.isBlank()) { status = "Paste a key before using Save & Test."; return; }
        BreakBlocksRateBudget.Snapshot budget = BreakBlocksRateBudget.snapshot(true);
        if (budget.paused() || (!budget.unlimited() && budget.remaining() <= 0)) {
            status = "Key saved. Rate limit: try testing again in " + budget.resetSeconds() + " seconds.";
            return;
        }
        long token = ++generation;
        checking = true; status = "Key saved. Checking authentication with BreakBlocks..."; redraw();
        BreakBlocksRateBudget.recordSearchRequest(true);
        pending = HTTP.sendAsync(ServerFinderClient.buildBreakBlocksRequest(BreakBlocksPlayers.ACCESS_URI, key), HttpResponse.BodyHandlers.ofString());
        pending.whenComplete((response, error) -> Reflection.execute(minecraft, () -> {
            if (closed || token != generation) return;
            checking = false;
            ToolState.reloadBreakBlocksApiKey();
            String current = ToolState.breakBlocksApiKey();
            if (!key.equals(current)) {
                BreakBlocksAccount.configure(current);
                status = "Saved key changed during the check. Test the current key again.";
                redraw(); return;
            }
            if (error != null || response == null) {
                status = "Key saved. Could not reach BreakBlocks; try testing again later.";
                redraw(); return;
            }
            BreakBlocksRateBudget.observe(response, true);
            int code = response.statusCode();
            if (code == 401) {
                BreakBlocksAccount.rejected(); status = "HTTP 401: BreakBlocks rejected the key. Check or regenerate it.";
            } else if (code == 403) {
                status = "HTTP 403: BreakBlocks denied this request; key acceptance is unconfirmed.";
            } else if (code == 429) {
                BreakBlocksRateBudget.noteRateLimit(response);
                status = "Key saved. Rate limited; retry in " + BreakBlocksRateBudget.snapshot(true).resetSeconds() + " seconds.";
            } else if (code / 100 != 2) {
                status = "Key saved. BreakBlocks returned HTTP " + code + "; try testing later.";
            } else {
                try {
                    BreakBlocksPlayers.Access access = BreakBlocksPlayers.parseAccess(response.body());
                    if (access.reported()) BreakBlocksAccount.observe(access.authed(), access.tier());
                    status = !access.reported() ? "No authentication status returned. Key acceptance is unconfirmed."
                            : access.authed() ? "Key authenticated. Tier: " + (access.tier().isBlank() ? "None reported" : access.tier())
                            : "BreakBlocks reported authed=false. Check or regenerate the key.";
                } catch (RuntimeException invalid) { status = "Key saved. BreakBlocks returned an unreadable response."; }
            }
            redraw();
        }));
    }

    private void cancelCheck() {
        generation++; checking = false;
        if (pending != null) pending.cancel(true);
    }

    private void openAccount() {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(BreakBlocksPlayers.ACCOUNT_URL));
            } else {
                String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
                List<String> command = os.contains("win") ? List.of("rundll32", "url.dll,FileProtocolHandler", BreakBlocksPlayers.ACCOUNT_URL)
                        : List.of(os.contains("mac") ? "open" : "xdg-open", BreakBlocksPlayers.ACCOUNT_URL);
                new ProcessBuilder(command).start();
            }
        } catch (Exception unavailable) { status = "Open breakblocks.com in your browser to create an API key."; }
    }

    private Button button(String text, int x, int y, int w, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(text), b -> action.run()).bounds(x, y, w, 20).build());
    }
    private void redraw() { clearWidgets(); init(); }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        int w = Math.min(420, width - 24), x = (width - w) / 2;
        graphics.text(font, "BreakBlocks API Key", x, 18, 0xFFFFFFFF, true);
        graphics.text(font, "Paste your key below. It is hidden until you choose Show.", x, 42, 0xFFCCCCCC, true);
        String remaining = status;
        int maxLines = Math.max(1, Math.min(4, (height - 180) / 10));
        for (int i = 0; i < maxLines && !remaining.isEmpty(); i++) {
            String part = remaining;
            while (part.length() > 1 && font.width(part) > w) part = part.substring(0, part.length() - 1);
            if (part.length() < remaining.length() && part.lastIndexOf(' ') > 0) part = part.substring(0, part.lastIndexOf(' '));
            graphics.text(font, part, x, 94 + i * 10, 0xFFFFCC66, true);
            remaining = remaining.substring(part.length()).stripLeading();
        }
    }

    @Override
    public void removed() {
        closed = true; cancelCheck(); shown = false; loaded = false;
        draft = "";
        if (keyBox != null) keyBox.setValue("");
        super.removed();
    }
    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
        afterClose.run();
    }

    private final class KeyBox extends EditBox {
        KeyBox(int x, int y, int w, int h) { super(BreakBlocksApiKeyScreen.this.font, x, y, w, h, Component.literal("BreakBlocks API key")); }
        @Override
        protected MutableComponent createNarrationMessage() {
            return Component.literal(shown ? "BreakBlocks API key, visible" : "BreakBlocks API key, hidden");
        }
    }
}
