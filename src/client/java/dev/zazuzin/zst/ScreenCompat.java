package dev.zazuzin.zst;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Minecraft 26.2-compatible screen switching for all mod UI flows. */
final class ScreenCompat {
    private ScreenCompat() {}

    static void setScreen(Object client, Object screen) throws Exception {
        if (client == null) throw new IllegalArgumentException("Minecraft client is null");

        Method direct = findScreenSetter(client.getClass(), screen);
        if (direct != null) {
            direct.invoke(client, screen);
            return;
        }

        // Minecraft 26.2 moved screen switching behind Minecraft.gui.setScreen(...).
        // Locate that owner by shape instead of depending on a mapped field name.
        for (Class<?> type = client.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                Object value;
                try {
                    field.trySetAccessible();
                    value = field.get(client);
                } catch (Throwable ignored) {
                    continue;
                }
                if (value == null || value == client) continue;

                Method setter = findScreenSetter(value.getClass(), screen);
                if (setter == null) continue;
                setter.invoke(value, screen);
                return;
            }
        }

        throw new IllegalStateException("No compatible screen setter found on Minecraft or its GUI owner");
    }

    private static Method findScreenSetter(Class<?> owner, Object screen) {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals("setScreen") || method.getParameterCount() != 1) continue;
                Class<?> parameter = method.getParameterTypes()[0];
                if (screen != null && !parameter.isInstance(screen)) continue;
                if (screen == null && parameter.isPrimitive()) continue;
                try { method.trySetAccessible(); } catch (Throwable ignored) {}
                return method;
            }
        }
        for (Method method : owner.getMethods()) {
            if (!method.getName().equals("setScreen") || method.getParameterCount() != 1) continue;
            Class<?> parameter = method.getParameterTypes()[0];
            if (screen != null && !parameter.isInstance(screen)) continue;
            if (screen == null && parameter.isPrimitive()) continue;
            return method;
        }
        return null;
    }


}
