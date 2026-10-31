package dev.zazuzin.zst;

import java.util.function.Consumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/** Keep native widget types out of Finder's otherwise reflection-based class. */
final class DetailedFilterWidgets {
    private DetailedFilterWidgets() {}
    static Object textBox(Object screen, int x, int y, int width, String label, String value, Consumer<String> changed) {
        EditBox box = new EditBox((Font) Reflection.getField(screen, "font"), x, y, width, 20, Component.literal(label));
        box.setMaxLength(ServerSearchFilters.MAX_LENGTH);
        box.setValue(value);
        box.setResponder(changed);
        return box;
    }
}
