#!/usr/bin/env bash
# Headless UI adapters only. Never used by Loom or included in a release JAR.
set -euo pipefail
fixture_dir="$1"
mkdir -p "$fixture_dir/net/minecraft/network/chat" "$fixture_dir/net/minecraft/client/gui/components" "$fixture_dir/net/minecraft/client/gui/screens"
cat > "$fixture_dir/net/minecraft/network/chat/Component.java" <<'JAVA'
package net.minecraft.network.chat;
public interface Component {
    String getString();
    static Component literal(String value) { return new Literal(value); }
    record Literal(String getString) implements Component {}
}
JAVA
cat > "$fixture_dir/net/minecraft/client/gui/screens/Screen.java" <<'JAVA'
package net.minecraft.client.gui.screens;
public class Screen {}
JAVA
cat > "$fixture_dir/net/minecraft/client/gui/components/Button.java" <<'JAVA'
package net.minecraft.client.gui.components;
import net.minecraft.network.chat.Component;
public final class Button {
    public boolean visible = true, active = true;
    private int x, y, width, height;
    private Component message;
    private final OnPress pressed;
    public interface OnPress { void onPress(Button button); }
    private Button(Component message, OnPress pressed) { this.message = message; this.pressed = pressed; }
    public static Builder builder(Component text, OnPress pressed) { return new Builder(text, pressed); }
    public static final class Builder {
        private final Button button;
        Builder(Component text, OnPress pressed) { button = new Button(text, pressed); }
        public Builder bounds(int x, int y, int width, int height) {
            button.x=x; button.y=y; button.width=width; button.height=height; return this;
        }
        public Button build() { return button; }
    }
    public int getX() { return x; } public int getY() { return y; }
    public int getWidth() { return width; } public int getHeight() { return height; }
    public void setX(int value) { x=value; } public void setY(int value) { y=value; }
    public void setWidth(int value) { width=value; } public void setHeight(int value) { height=value; }
    public Component getMessage() { return message; }
    public void setMessage(Component value) { message=value; }
    public void onPress() { if (visible && active) pressed.onPress(this); }
}
JAVA
