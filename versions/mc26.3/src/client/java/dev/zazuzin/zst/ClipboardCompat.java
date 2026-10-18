package dev.zazuzin.zst;

import java.lang.reflect.Method;

/** Shared clipboard helper that uses the same Minecraft keyboard path as Copy IP. */
public final class ClipboardCompat {
    private ClipboardCompat() {}

    public static boolean copy(String text) {
        if (text == null || text.isBlank()) return false;
        try {
            Object client = RuntimeAccess.minecraftInstance();
            if (client == null) return false;
            Object keyboard = Reflection.getField(client, "keyboardHandler", "keyboard", "keyboardManager");
            if (keyboard == null) return false;
            Method setter = Reflection.findCompatibleMethod(keyboard.getClass(), "setClipboard", text);
            if (setter == null) return false;
            setter.invoke(keyboard, text);
            return true;
        } catch (Throwable t) {
            System.err.println("[Zazu's Server Seeker] Clipboard copy failed: " + Reflection.unwrap(t));
            return false;
        }
    }
}
