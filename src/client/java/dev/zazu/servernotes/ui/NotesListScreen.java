package dev.zazu.servernotes.ui;

import dev.zazu.servernotes.client.ZazusServerNotesClient;
import dev.zazu.servernotes.model.ServerNote;
import dev.zazu.servernotes.model.ServerProfile;
import dev.zazu.servernotes.util.TimeUtil;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

public final class NotesListScreen extends BaseServerScreen {
    private static final int PAGE_SIZE = 3;
    private int page;

    public NotesListScreen(Screen parent, String profileKey) {
        super(Component.literal("Server Notes"), parent, profileKey);
    }

    @Override
    protected void init() {
        ServerProfile profile = profile();
        int maxPage = Math.max(0, (profile.notes().size() - 1) / PAGE_SIZE);
        if (page > maxPage) page = maxPage;

        int center = width / 2;
        int start = page * PAGE_SIZE;
        int end = Math.min(profile.notes().size(), start + PAGE_SIZE);

        for (int i = start; i < end; i++) {
            ServerNote note = profile.notes().get(i);
            int row = i - start;
            int y = 56 + row * 42;
            addRenderableWidget(Button.builder(Component.literal("Edit"), button ->
                minecraft.gui.setScreen(new NoteEditScreen(this, profileKey, note.id(), false)))
                .bounds(center + 40, y, 46, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Delete"), button -> {
                ZazusServerNotesClient.app().notes().delete(profile(), note.id());
                minecraft.gui.setScreen(new NotesListScreen(parent, profileKey));
            }).bounds(center + 90, y, 56, 20).build());
        }

        addRenderableWidget(Button.builder(Component.literal("Add Note"), button ->
            minecraft.gui.setScreen(new NoteEditScreen(this, profileKey, null, false)))
            .bounds(center - 100, height - 76, 200, 20).build());
        addRenderableWidget(Button.builder(Component.literal("< Previous"), button -> {
            if (page > 0) {
                minecraft.gui.setScreen(new NotesListScreen(parent, profileKey).withPage(page - 1));
            }
        }).bounds(center - 100, height - 52, 96, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Next >"), button -> {
            if ((page + 1) * PAGE_SIZE < profile().notes().size()) {
                minecraft.gui.setScreen(new NotesListScreen(parent, profileKey).withPage(page + 1));
            }
        }).bounds(center + 4, height - 52, 96, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Back"), button -> onClose())
            .bounds(center - 100, height - 28, 200, 20).build());
    }


    private NotesListScreen withPage(int page) {
        this.page = page;
        return this;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        centered(graphics, "Notes - " + profile().displayAddress(), 18, 0xFFFFFFFF);

        List<ServerNote> notes = profile().notes();
        if (notes.isEmpty()) {
            centered(graphics, "No notes saved yet.", 70, 0xFFAAAAAA);
            return;
        }

        int start = page * PAGE_SIZE;
        int end = Math.min(notes.size(), start + PAGE_SIZE);
        int left = width / 2 - 160;
        for (int i = start; i < end; i++) {
            ServerNote note = notes.get(i);
            int row = i - start;
            int y = 54 + row * 42;
            String prefix = note.category().isBlank() ? "" : "[" + note.category() + "] ";
            graphics.text(font, trim(prefix + note.text(), 48), left, y, 0xFFFFFFFF, true);
            String time = "Added " + TimeUtil.display(note.createdAt());
            if (note.editedAt() != null) time += " • edited " + TimeUtil.display(note.editedAt());
            graphics.text(font, trim(time, 52), left, y + 12, 0xFF999999, true);
        }
        centered(graphics, "Page " + (page + 1) + " / " + (Math.max(0, (notes.size() - 1) / PAGE_SIZE) + 1), 34, 0xFF999999);
    }
}
