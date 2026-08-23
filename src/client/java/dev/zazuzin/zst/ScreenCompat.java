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

    static Object currentScreen(Object client) {
        if (client == null) return null;

        // Older/current layouts may expose the screen directly on Minecraft.
        for (String fieldName : new String[]{"screen", "currentScreen"}) {
            try {
                Field field = findField(client.getClass(), fieldName);
                if (field != null) {
                    Object value = field.get(client);
                    if (looksLikeScreen(value)) return value;
                }
            } catch (Throwable ignored) {}
        }
        for (String methodName : new String[]{"getScreen", "screen"}) {
            try {
                Method method = findNoArgMethod(client.getClass(), methodName);
                if (method != null) {
                    Object value = method.invoke(client);
                    if (looksLikeScreen(value)) return value;
                }
            } catch (Throwable ignored) {}
        }

        // Minecraft 26.2 keeps screen ownership behind the GUI object. Locate
        // that owner by shape, then read a Screen-looking field or getter.
        for (Class<?> type = client.getClass(); type != null; type = type.getSuperclass()) {
            for (Field ownerField : type.getDeclaredFields()) {
                Object owner;
                try {
                    ownerField.trySetAccessible();
                    owner = ownerField.get(client);
                } catch (Throwable ignored) {
                    continue;
                }
                if (owner == null || owner == client) continue;

                for (Class<?> ownerType = owner.getClass(); ownerType != null; ownerType = ownerType.getSuperclass()) {
                    for (Field field : ownerType.getDeclaredFields()) {
                        try {
                            field.trySetAccessible();
                            Object value = field.get(owner);
                            if (looksLikeScreen(value)) return value;
                        } catch (Throwable ignored) {}
                    }
                }
                for (String methodName : new String[]{"getScreen", "screen"}) {
                    try {
                        Method method = findNoArgMethod(owner.getClass(), methodName);
                        if (method != null) {
                            Object value = method.invoke(owner);
                            if (looksLikeScreen(value)) return value;
                        }
                    } catch (Throwable ignored) {}
                }
            }
        }
        return null;
    }

    private static boolean looksLikeScreen(Object value) {
        if (value == null) return false;
        Class<?> type = value.getClass();
        while (type != null) {
            if (type.getName().equals("net.minecraft.client.gui.screens.Screen")
                    || type.getSimpleName().endsWith("Screen")) return true;
            type = type.getSuperclass();
        }
        return false;
    }

    private static Field findField(Class<?> owner, String name) {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                try { field.trySetAccessible(); } catch (Throwable ignored) {}
                return field;
            } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    private static Method findNoArgMethod(Class<?> owner, String name) {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != 0) continue;
                try { method.trySetAccessible(); } catch (Throwable ignored) {}
                return method;
            }
        }
        return null;
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
