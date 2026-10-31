package dev.zazuzin.zst;

import java.lang.reflect.*;
import java.util.*;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.network.chat.Component;

/** Exercises the packaged UI callbacks using test-only Minecraft widget adapters. */
public final class SeekerNavigationTest {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    public static final class Client {
        Object screen;
        final Object titleScreen = new Object();
        public void execute(Runnable task) { task.run(); }
    }
    public static final class ListWidget {
        final List<Object> onlineServers = new ArrayList<>();
        int updates;
        public void updateOnlineServers(ServerList list) { updates++; onlineServers.clear(); onlineServers.addAll(list.servers); }
    }
    public static final class MultiplayerScreen {
        final List<Object> children = new ArrayList<>(), renderables = new ArrayList<>(), narratables = new ArrayList<>();
        final ListWidget serverSelectionList = new ListWidget();
        ServerList servers = new ServerList();
        final int width, height;
        final Client client;
        int nativeRefreshes, nativeCloses;
        MultiplayerScreen(Client client, int width, int height) {
            this.client = client; this.width = width; this.height = height; client.screen = this;
        }
        public Object addRenderableWidget(Object widget) {
            children.add(widget); renderables.add(widget); narratables.add(widget); return widget;
        }
        public void removeWidget(Object widget) { children.remove(widget); renderables.remove(widget); narratables.remove(widget); }
        public void refreshServerList() { nativeRefreshes++; client.screen = client.titleScreen; }
        public void onClose() { nativeCloses++; client.screen = client.titleScreen; }
        // Models the native close callback's cancellation boundary, not a game screen.
        void escape() { if (!ServerFinderClient.closeForScreen(this)) onClose(); }
    }

    @SuppressWarnings("unchecked")
    private static ServerFinderClient.OverlayState overlay(MultiplayerScreen screen) throws Exception {
        Field f = ServerFinderClient.class.getDeclaredField("STATES"); f.setAccessible(true);
        return ((Map<Object, ServerFinderClient.OverlayState>) f.get(null)).get(screen);
    }
    private static String label(Object widget) { return ((Button) widget).getMessage().getString(); }
    private static Button button(List<Object> widgets, String text) {
        return widgets.stream().filter(w -> w instanceof Button && label(w).equals(text))
                .map(w -> (Button) w).findFirst().orElseThrow(() -> new AssertionError("Missing button: " + text));
    }

    public static void main(String[] args) throws Exception {
        ToolState.load(); ToolState.autoAddDefault = false; ToolState.authDetectionEnabled = false;
        ToolState.contributeVerifiedServers = false; ToolState.skipAddedHistory = true;
        check(ServerFinderClient.readyStatus(299, true).equals("299 servers already added. These are skipped automatically."), "Added-history wording changed");
        check(ServerFinderClient.readyStatus(1, true).equals("1 server already added. It is skipped automatically."), "Singular added-history wording is incorrect");
        check(ServerFinderClient.readyStatus(299, false).contains("off in Settings"), "Disabled skipping is described as automatic");

        for (int width : new int[]{320, 412, 854}) {
            for (boolean escape : new boolean[]{false, true}) closeAndLayout(width, escape);
        }
        blockedReturnsToSettings(false);
        blockedReturnsToSettings(true);
        restoreManagedView("HUB");
        restoreManagedView("SERVERS");
        System.out.println("Seeker Back/Escape routing, widget cleanup, responsive BreakBlocks layout, Blocked Settings navigation and status wording passed.");
    }

    private static void closeAndLayout(int width, boolean escape) throws Exception {
        Client client = new Client(); MultiplayerScreen screen = new MultiplayerScreen(client, width, 258);
        Button original = Button.builder(Component.literal("Back"), b -> screen.onClose()).bounds(8, 230, 70, 20).build();
        screen.addRenderableWidget(original);
        ServerFinderClient.openOverlay(client, screen, width, 258);
        var s = overlay(screen);
        check(!original.visible && !original.active, "Multiplayer controls remain active under Seeker");
        check(label(s.closeButton).equals("Back"), "Seeker exit label is confusing");
        check(label(s.browserButton).equals("BreakBlocks"), "BreakBlocks shortcut was not renamed");
        Button browse = (Button) s.browserButton, settings = (Button) s.settingsButton, sort = (Button) s.sortButton;
        check(browse.getWidth() >= 2 * sort.getWidth(), "BreakBlocks did not gain the freed button space");
        check(browse.getX() + browse.getWidth() + 4 <= settings.getX() && settings.getX() + settings.getWidth() <= width,
                "BreakBlocks shortcut overlaps Settings or the screen edge");
        check(s.widgets.stream().noneMatch(w -> label(w).startsWith("Blocked Servers")), "Blocked Servers remains on the main Seeker screen");
        List<Object> owned = new ArrayList<>(s.widgets);
        s.loading = true; s.autoAdd = true; s.browserLoading = true;
        long search = s.searchGeneration, browser = s.browserRequestToken;
        if (escape) screen.escape(); else ((Button) s.closeButton).onPress();
        check(client.screen == screen && screen.nativeCloses == 0 && screen.nativeRefreshes == 0,
                "Seeker close navigated away from Multiplayer or called native refresh");
        check(!s.open && !s.loading && !s.autoAdd && !s.browserLoading && !ServerFinderClient.isOverlayOpen(screen), "Closing Seeker left work active");
        check(s.searchGeneration > search && s.browserRequestToken > browser, "Closing Seeker did not invalidate old requests");
        check(original.visible && original.active, "Multiplayer widgets were not restored");
        for (Object w : owned) check(!screen.children.contains(w) && !screen.renderables.contains(w) && !screen.narratables.contains(w), "Seeker left a ghost widget");
        screen.escape();
        check(client.screen == client.titleScreen && screen.nativeCloses == 1, "Normal Multiplayer Escape was intercepted after Seeker closed");
    }

    private static void blockedReturnsToSettings(boolean directSettings) throws Exception {
        Client client = new Client(); MultiplayerScreen screen = new MultiplayerScreen(client, 412, 258);
        ToolState.skipAddedHistory = true;
        if (directSettings) ServerFinderClient.openSettingsOverlay(client, screen, 412, 258);
        else ServerFinderClient.openOverlay(client, screen, 412, 258);
        var s = overlay(screen);
        if (!directSettings) ((Button) s.settingsButton).onPress();
        for (int i = 0; i < 12 && s.blockedButton == null; i++) button(s.subWidgets, "Next >").onPress();
        check(s.blockedButton != null, "Blocked Servers is missing from Settings");
        int page = s.settingsPage;
        List<Object> preferences = new ArrayList<>(s.subWidgets);
        ((Button) s.blockedButton).onPress();
        check(s.subWidgets.stream().anyMatch(w -> label(w).startsWith("Blocked / Deleted Servers")), "Blocked Servers does not open from Settings");
        for (Object w : preferences) check(!screen.renderables.contains(w) && !screen.narratables.contains(w), "Settings left ghost controls behind Blocked Servers");
        button(s.subWidgets, "Back").onPress();
        check(!s.mainVisible && s.settingsPage == page && s.subWidgets.stream().anyMatch(w -> label(w).equals("Zazu's Server Seeker Settings")), "Blocked Back did not return to the same Settings page");
        ToolState.skipAddedHistory = false;
        button(s.subWidgets, "Back").onPress();
        if (directSettings) check(!s.open && client.screen == screen, "Direct Settings lost its Multiplayer return path");
        else {
            check(s.open && s.mainVisible && label(s.statusButton).contains("off in Settings"), "Settings Back lost Seeker or left a stale skipping message");
            ServerFinderClient.closeForScreen(screen);
        }
        check(screen.nativeCloses == 0 && screen.nativeRefreshes == 0, "Settings navigation called a native screen exit");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void restoreManagedView(String viewName) throws Exception {
        Client client = new Client(); MultiplayerScreen screen = new MultiplayerScreen(client, 412, 258);
        Class<?> type = Class.forName("dev.zazuzin.zst.ServerTabsEntrypoint$State");
        Constructor<?> ctor = type.getDeclaredConstructor(Object.class, Object.class, Object.class, int.class, int.class);
        ctor.setAccessible(true); Object state = ctor.newInstance(client, screen, screen, 412, 258);
        Field view = type.getDeclaredField("view"); view.setAccessible(true);
        view.set(state, Enum.valueOf((Class) view.getType(), viewName));
        Field list = type.getDeclaredField("listWidget"); list.setAccessible(true); list.set(state, screen.serverSelectionList);
        Field states = ServerTabsEntrypoint.class.getDeclaredField("STATES"); states.setAccessible(true);
        Map<Object, Object> registered = (Map<Object, Object>) states.get(null); registered.put(screen, state);
        try {
            ServerList.persistedVisible.clear();
            ServerList.persistedVisible.add(new ServerData("New saved server", "saved.example:25565"));
            ServerFinderClient.openOverlay(client, screen, 412, 258);
            check(ServerFinderClient.closeForScreen(screen), "Managed Seeker did not consume close");
            check(client.screen == screen && screen.nativeCloses == 0 && screen.nativeRefreshes == 0, "Managed view was replaced when closing Seeker");
            check(view.get(state).toString().equals(viewName), "Managed category changed after Seeker closed");
            check(screen.servers.servers.size() == 1 && screen.serverSelectionList.updates > 0, "Managed view did not refresh saved server data in place");
        } finally { registered.remove(screen); }
    }
}
