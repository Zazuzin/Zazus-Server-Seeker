package dev.zazu.servernotes.ui;

import dev.zazu.servernotes.client.ZazusServerNotesClient;
import dev.zazu.servernotes.model.ServerNote;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class NoteEditScreen extends BaseServerScreen {
    private final String noteId;
    private final boolean quick;
    private EditBox textBox;
    private EditBox categoryBox;
    private String error = "";

    public NoteEditScreen(Screen parent, String profileKey, String noteId, boolean quick) {
        super(Component.literal(noteId == null ? (quick ? "Quick Note" : "Add Note") : "Edit Note"), parent, profileKey);
        this.noteId = noteId;
        this.quick = quick;
    }

    @Override
    protected void init() {
        int center = width / 2;
        textBox = new EditBox(font, center - 130, 66, 260, 20, Component.literal("Note text"));
        textBox.setMaxLength(500);
        categoryBox = new EditBox(font, center - 130, 108, 260, 20, Component.literal("Optional category"));
        categoryBox.setMaxLength(40);

        if (noteId != null) {
            profile().notes().stream().filter(note -> note.id().equals(noteId)).findFirst().ifPresent(note -> {
                textBox.setValue(note.text());
                categoryBox.setValue(note.category());
            });
        }

        addRenderableWidget(textBox);
        addRenderableWidget(categoryBox);
        addRenderableWidget(Button.builder(Component.literal("Save"), button -> save())
            .bounds(center - 100, 148, 96, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> onClose())
            .bounds(center + 4, 148, 96, 20).build());
    }

    private void save() {
        String text = textBox.getValue().trim();
        if (text.isEmpty()) {
            error = "Note text cannot be empty.";
            return;
        }
        if (noteId == null) {
            ZazusServerNotesClient.app().notes().add(profile(), text, categoryBox.getValue());
        } else {
            ZazusServerNotesClient.app().notes().edit(profile(), noteId, text, categoryBox.getValue());
        }
        onClose();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        centered(graphics, noteId == null ? (quick ? "Quick Note" : "Add Note") : "Edit Note", 20, 0xFFFFFFFF);
        graphics.text(font, "Note", width / 2 - 130, 52, 0xFFCCCCCC, true);
        graphics.text(font, "Category (optional)", width / 2 - 130, 94, 0xFFCCCCCC, true);
        if (!error.isEmpty()) centered(graphics, error, 180, 0xFFFF6666);
    }
}
