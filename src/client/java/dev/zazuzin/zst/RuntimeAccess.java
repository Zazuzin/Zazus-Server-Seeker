package dev.zazuzin.zst;

import java.lang.reflect.*;
import java.util.*;

/** Shared reflective runtime helpers for Zazu's Server Tool. */
final class RuntimeAccess {
    private RuntimeAccess() {}

    static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                try { field.setAccessible(true); } catch (Throwable ignored) {}
                return field;
            } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    static Object field(Object target, String name) {
        if (target == null) return null;
        try {
            Field field = findField(target.getClass(), name);
            return field == null ? null : field.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static Object staticField(String className, String name) {
        try {
            Class<?> type = Class.forName(className);
            Field field = findField(type, name);
            return field == null ? null : field.get(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static boolean staticBoolean(String className, String name, boolean fallback) {
        Object value = staticField(className, name);
        return value instanceof Boolean b ? b : fallback;
    }

    static long staticLong(String className, String name, long fallback) {
        Object value = staticField(className, name);
        return value instanceof Number n ? n.longValue() : fallback;
    }

    static String staticString(String className, String name) {
        Object value = staticField(className, name);
        return value == null ? "" : String.valueOf(value);
    }

    static Method findMethod(Class<?> type, String name, int parameterCount) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != parameterCount) continue;
                try { method.setAccessible(true); } catch (Throwable ignored) {}
                return method;
            }
        }
        return null;
    }

    static Object invoke(Object target, String name, Object... args) {
        if (target == null) return null;
        for (Class<?> current = target.getClass(); current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
                if (!compatible(method.getParameterTypes(), args)) continue;
                try {
                    method.setAccessible(true);
                    return method.invoke(target, args);
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    static Object invokeStatic(String className, String name, Object... args) {
        try {
            Class<?> type = Class.forName(className);
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                for (Method method : current.getDeclaredMethods()) {
                    if (!Modifier.isStatic(method.getModifiers()) || !method.getName().equals(name) || method.getParameterCount() != args.length) continue;
                    if (!compatible(method.getParameterTypes(), args)) continue;
                    try {
                        method.setAccessible(true);
                        return method.invoke(null, args);
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean compatible(Class<?>[] types, Object[] args) {
        for (int i = 0; i < types.length; i++) {
            if (args[i] == null) {
                if (types[i].isPrimitive()) return false;
                continue;
            }
            Class<?> type = wrap(types[i]);
            if (!type.isInstance(args[i])) return false;
        }
        return true;
    }

    private static Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == char.class) return Character.class;
        return type;
    }

    static boolean isScreen(Object screen, String simpleName) {
        return screen != null && (screen.getClass().getSimpleName().equals(simpleName)
                || screen.getClass().getName().endsWith("." + simpleName));
    }

    static Object minecraftInstance() {
        for (String name : List.of("net.minecraft.client.Minecraft", "net.minecraft.client.MinecraftClient")) {
            try {
                Class<?> type = Class.forName(name);
                for (String methodName : List.of("getInstance", "getMinecraft")) {
                    Method method = findMethod(type, methodName, 0);
                    if (method != null && Modifier.isStatic(method.getModifiers())) return method.invoke(null);
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    static void execute(Object client, Runnable task) {
        if (client != null) {
            Object result = invoke(client, "execute", task);
            if (result != null || findMethod(client.getClass(), "execute", 1) != null) return;
            result = invoke(client, "tell", task);
            if (result != null || findMethod(client.getClass(), "tell", 1) != null) return;
        }
        task.run();
    }

    static Object font(Object screen) {
        Object font = invoke(screen, "getFont");
        if (font == null) font = field(screen, "font");
        if (font == null) font = field(screen, "textRenderer");
        if (font == null) {
            Object client = minecraftInstance();
            font = field(client, "font");
            if (font == null) font = field(client, "textRenderer");
        }
        return font;
    }

    static int intField(Object target, String name, int fallback) {
        Object value = field(target, name);
        return value instanceof Number n ? n.intValue() : fallback;
    }

    static int width(Object font, String text) {
        Object value = invoke(font, "width", text);
        if (!(value instanceof Number)) value = invoke(font, "getWidth", text);
        return value instanceof Number n ? n.intValue() : text.length() * 6;
    }

    static String trimToWidth(Object font, String text, int maxWidth) {
        if (maxWidth <= 0 || width(font, text) <= maxWidth) return text;
        String ellipsis = "…";
        int end = text.length();
        while (end > 0 && width(font, text.substring(0, end) + ellipsis) > maxWidth) end--;
        return text.substring(0, Math.max(0, end)) + ellipsis;
    }

    static void drawText(Object extractor, Object font, String text, int x, int y, int color) {
        if (extractor == null || font == null || text == null) return;
        for (Method method : extractor.getClass().getMethods()) {
            if (!method.getName().equals("text")) continue;
            Class<?>[] p = method.getParameterTypes();
            if (p.length != 5 && p.length != 6) continue;
            if (!p[0].isAssignableFrom(font.getClass()) || p[1] != String.class
                    || p[2] != int.class || p[3] != int.class || p[4] != int.class) continue;
            try {
                if (p.length == 5) method.invoke(extractor, font, text, x, y, color);
                else if (p[5] == boolean.class) method.invoke(extractor, font, text, x, y, color, false);
                else continue;
                return;
            } catch (Throwable ignored) {}
        }
    }

    static void setButtonText(Object button, String text) {
        if (button == null) return;
        try {
            Class<?> component = Class.forName("net.minecraft.network.chat.Component");
            Method literal = component.getMethod("literal", String.class);
            Object message = literal.invoke(null, text);
            Object result = invoke(button, "setMessage", message);
            if (result != null || findMethod(button.getClass(), "setMessage", 1) != null) return;
        } catch (Throwable ignored) {}
    }

    static void registerEvent(Object event, Object listener) throws Exception {
        if (event == null) throw new IllegalArgumentException("event");

        // Fabric's concrete ArrayBackedEvent implementation is package-private in
        // Fabric API 0.157.0. Invoking its otherwise-public register method via a
        // Method whose declaring class is ArrayBackedEvent throws IllegalAccessException.
        // Invoke through Fabric's public Event interface instead.
        try {
            Class<?> eventInterface = Class.forName("net.fabricmc.fabric.api.event.Event");
            Method register = eventInterface.getMethod("register", Object.class);
            register.invoke(event, listener);
            return;
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof Exception e) throw e;
            if (cause instanceof Error e) throw e;
            throw ex;
        } catch (ReflectiveOperationException primary) {
            // Compatibility fallback for unusual Fabric API layouts.
            for (Class<?> iface : event.getClass().getInterfaces()) {
                for (Method method : iface.getMethods()) {
                    if (!method.getName().equals("register") || method.getParameterCount() != 1) continue;
                    method.invoke(event, listener);
                    return;
                }
            }
            throw primary;
        }
    }

    static Object objectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> "ZazusServerToolCallback";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == (args == null || args.length == 0 ? null : args[0]);
            default -> null;
        };
    }

    static String componentText(Object value) {
        if (value == null) return "";
        Object text = invoke(value, "getString");
        return text == null ? String.valueOf(value) : String.valueOf(text);
    }
}
