package dev.zazu.servernotes.ui;

import dev.zazu.servernotes.client.ZazusServerNotesClient;
import dev.zazu.servernotes.model.ServerProfile;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public abstract class BaseServerScreen extends Screen {
    protected final Screen parent;
    protected final String profileKey;

    protected BaseServerScreen(Component title, Screen parent, String profileKey) {
        super(title);
        this.parent = parent;
        this.profileKey = profileKey;
    }

    protected ServerProfile profile() {
        return ZazusServerNotesClient.app().store().find(profileKey)
            .orElseThrow(() -> new IllegalStateException("Missing server profile " + profileKey));
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }

    protected void centered(GuiGraphicsExtractor graphics, String text, int y, int color) {
        graphics.text(this.font, text, (this.width - this.font.width(text)) / 2, y, color, true);
    }

    protected static String trim(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxChars ? text : text.substring(0, Math.max(0, maxChars - 3)) + "...";
    }
}
