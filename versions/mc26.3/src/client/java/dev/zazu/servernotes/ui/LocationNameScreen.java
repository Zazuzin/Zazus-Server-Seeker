package dev.zazu.servernotes.ui;

import dev.zazu.servernotes.client.ZazusServerNotesClient;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class LocationNameScreen extends BaseServerScreen {
    private final double x;
    private final double y;
    private final double z;
    private final String dimension;
    private EditBox nameBox;
    private String error = "";

    public LocationNameScreen(Screen parent, String profileKey, double x, double y, double z, String dimension) {
        super(Component.literal("Add Current Location"), parent, profileKey);
        this.x = x;
        this.y = y;
        this.z = z;
        this.dimension = dimension;
    }

    @Override
    protected void init() {
        int center = width / 2;
        nameBox = new EditBox(font, center - 120, 88, 240, 20, Component.literal("Location name"));
        nameBox.setMaxLength(80);
        addRenderableWidget(nameBox);
        addRenderableWidget(Button.builder(Component.literal("Save"), button -> save())
            .bounds(center - 100, 126, 96, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> onClose())
            .bounds(center + 4, 126, 96, 20).build());
    }

    private void save() {
        String name = nameBox.getValue().trim();
        if (name.isEmpty()) {
            error = "Enter a location name.";
            return;
        }
        ZazusServerNotesClient.app().locations().add(profile(), name, x, y, z, dimension);
        onClose();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        centered(graphics, "Add Current Location", 20, 0xFFFFFFFF);
        centered(graphics, dimension, 42, 0xFFAAAAAA);
        centered(graphics, String.format("X %.1f   Y %.1f   Z %.1f", x, y, z), 56, 0xFFFFFFFF);
        graphics.text(font, "Name", width / 2 - 120, 74, 0xFFCCCCCC, true);
        if (!error.isEmpty()) centered(graphics, error, 158, 0xFFFF6666);
    }
}
