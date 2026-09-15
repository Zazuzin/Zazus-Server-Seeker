package dev.zazu.servernotes.ui;

import dev.zazu.servernotes.client.ZazusServerNotesClient;
import dev.zazu.servernotes.model.ServerLocation;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class LocationEditScreen extends BaseServerScreen {
    private final String locationId;
    private EditBox nameBox;
    private EditBox xBox;
    private EditBox yBox;
    private EditBox zBox;
    private EditBox dimensionBox;
    private String error = "";

    public LocationEditScreen(Screen parent, String profileKey, String locationId) {
        super(Component.literal(locationId == null ? "Add Manual Location" : "Edit Location"), parent, profileKey);
        this.locationId = locationId;
    }

    @Override
    protected void init() {
        ServerLocation location = locationId == null ? null : profile().locations().stream()
            .filter(item -> item.id().equals(locationId))
            .findFirst()
            .orElseThrow();

        int center = width / 2;
        int boxX = center - 100;
        nameBox = new EditBox(font, boxX, 44, 200, 20, Component.literal("Name"));
        xBox = new EditBox(font, boxX, 74, 200, 20, Component.literal("X"));
        yBox = new EditBox(font, boxX, 104, 200, 20, Component.literal("Y"));
        zBox = new EditBox(font, boxX, 134, 200, 20, Component.literal("Z"));
        dimensionBox = new EditBox(font, boxX, 164, 200, 20, Component.literal("Dimension"));

        nameBox.setMaxLength(80);
        xBox.setMaxLength(32);
        yBox.setMaxLength(32);
        zBox.setMaxLength(32);
        dimensionBox.setMaxLength(100);
        if (location != null) {
            nameBox.setValue(location.name());
            xBox.setValue(Double.toString(location.x()));
            yBox.setValue(Double.toString(location.y()));
            zBox.setValue(Double.toString(location.z()));
            dimensionBox.setValue(location.dimension());
        } else {
            xBox.setValue("0");
            yBox.setValue("64");
            zBox.setValue("0");
            dimensionBox.setValue("minecraft:overworld");
        }

        addRenderableWidget(nameBox);
        addRenderableWidget(xBox);
        addRenderableWidget(yBox);
        addRenderableWidget(zBox);
        addRenderableWidget(dimensionBox);
        addRenderableWidget(Button.builder(Component.literal("Save"), button -> save())
            .bounds(center - 100, 194, 96, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> onClose())
            .bounds(center + 4, 194, 96, 20).build());
    }

    private void save() {
        String name = nameBox.getValue().trim();
        String dimension = dimensionBox.getValue().trim();
        if (name.isEmpty() || dimension.isEmpty()) {
            error = "Name and dimension are required.";
            return;
        }
        try {
            double x = Double.parseDouble(xBox.getValue().trim());
            double y = Double.parseDouble(yBox.getValue().trim());
            double z = Double.parseDouble(zBox.getValue().trim());
            if (locationId == null) {
                ZazusServerNotesClient.app().locations().add(profile(), name, x, y, z, dimension);
            } else {
                ZazusServerNotesClient.app().locations().edit(profile(), locationId, name, x, y, z, dimension);
            }
            onClose();
        } catch (NumberFormatException exception) {
            error = "X, Y and Z must be valid numbers.";
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        centered(graphics, locationId == null ? "Add Manual Location" : "Edit Location", 18, 0xFFFFFFFF);
        int left = width / 2 - 100;
        graphics.text(font, "Name", left, 32, 0xFFCCCCCC, true);
        graphics.text(font, "X", left, 62, 0xFFCCCCCC, true);
        graphics.text(font, "Y", left, 92, 0xFFCCCCCC, true);
        graphics.text(font, "Z", left, 122, 0xFFCCCCCC, true);
        graphics.text(font, "Dimension", left, 152, 0xFFCCCCCC, true);
        if (!error.isEmpty()) centered(graphics, error, 218, 0xFFFF6666);
    }
}
