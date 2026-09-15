package dev.zazu.servernotes.ui;

import dev.zazu.servernotes.client.ZazusServerNotesClient;
import dev.zazu.servernotes.model.ServerLocation;
import dev.zazu.servernotes.util.TimeUtil;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

public final class LocationsListScreen extends BaseServerScreen {
    private static final int PAGE_SIZE = 3;
    private int page;

    public LocationsListScreen(Screen parent, String profileKey) {
        super(Component.literal("Saved Locations"), parent, profileKey);
    }

    @Override
    protected void init() {
        int center = width / 2;
        List<ServerLocation> locations = profile().locations();
        int maxPage = Math.max(0, (locations.size() - 1) / PAGE_SIZE);
        if (page > maxPage) page = maxPage;
        int start = page * PAGE_SIZE;
        int end = Math.min(locations.size(), start + PAGE_SIZE);

        for (int i = start; i < end; i++) {
            ServerLocation location = locations.get(i);
            int row = i - start;
            int y = 56 + row * 42;
            addRenderableWidget(Button.builder(Component.literal("Edit"), button ->
                minecraft.gui.setScreen(new LocationEditScreen(this, profileKey, location.id())))
                .bounds(center + 40, y, 46, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Delete"), button -> {
                ZazusServerNotesClient.app().locations().delete(profile(), location.id());
                minecraft.gui.setScreen(new LocationsListScreen(parent, profileKey));
            }).bounds(center + 90, y, 56, 20).build());
        }

        addRenderableWidget(Button.builder(Component.literal("Add Current"), button -> addCurrent())
            .bounds(center - 100, height - 76, 96, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Add Manual"), button ->
            minecraft.gui.setScreen(new LocationEditScreen(this, profileKey, null)))
            .bounds(center + 4, height - 76, 96, 20).build());
        addRenderableWidget(Button.builder(Component.literal("< Previous"), button -> {
            if (page > 0) minecraft.gui.setScreen(new LocationsListScreen(parent, profileKey).withPage(page - 1));
        }).bounds(center - 100, height - 52, 96, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Next >"), button -> {
            if ((page + 1) * PAGE_SIZE < profile().locations().size()) {
                minecraft.gui.setScreen(new LocationsListScreen(parent, profileKey).withPage(page + 1));
            }
        }).bounds(center + 4, height - 52, 96, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Back"), button -> onClose())
            .bounds(center - 100, height - 28, 200, 20).build());
    }

    private LocationsListScreen withPage(int value) {
        this.page = value;
        return this;
    }

    private void addCurrent() {
        if (minecraft.player == null || minecraft.level == null) return;
        minecraft.gui.setScreen(new LocationNameScreen(this, profileKey,
            minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ(),
            minecraft.level.dimension().identifier().toString()));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        centered(graphics, "Locations - " + profile().displayAddress(), 18, 0xFFFFFFFF);
        List<ServerLocation> locations = profile().locations();
        if (locations.isEmpty()) {
            centered(graphics, "No saved locations yet.", 70, 0xFFAAAAAA);
            return;
        }
        int left = width / 2 - 160;
        int start = page * PAGE_SIZE;
        int end = Math.min(locations.size(), start + PAGE_SIZE);
        for (int i = start; i < end; i++) {
            ServerLocation location = locations.get(i);
            int row = i - start;
            int y = 54 + row * 42;
            graphics.text(font, trim(location.name(), 26) + "  [" + trim(location.dimension(), 20) + "]", left, y, 0xFFFFFFFF, true);
            graphics.text(font, String.format("X %.1f  Y %.1f  Z %.1f  • %s", location.x(), location.y(), location.z(), TimeUtil.display(location.createdAt())), left, y + 12, 0xFF999999, true);
        }
        centered(graphics, "Page " + (page + 1) + " / " + (Math.max(0, (locations.size() - 1) / PAGE_SIZE) + 1), 34, 0xFF999999);
    }
}
