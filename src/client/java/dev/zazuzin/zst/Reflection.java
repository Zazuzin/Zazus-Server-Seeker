package dev.zazuzin.zst;

import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;

/**
 * Small reflection compatibility layer. The released 0.3.x mod intentionally
 * avoided hard-linking most Minecraft GUI classes so minor mapping/API changes
 * would not break class loading.
 */
final class Reflection {
    private Reflection() {}

    static Class<?> firstClass(String... names) throws ClassNotFoundException {
        ClassNotFoundException last = null;
        for (String name : names) {
            try { return Class.forName(name); }
            catch (ClassNotFoundException ex) { last = ex; }
        }
        throw last == null ? new ClassNotFoundException(Arrays.toString(names)) : last;
    }

    static Field findField(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                try { f.setAccessible(true); } catch (Throwable ignored) {}
                return f;
            } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    static Object getField(Object target, String... names) {
        if (target == null) return null;
        for (String name : names) {
            Field f = findField(target.getClass(), name);
            if (f != null) {
                try { return f.get(target); } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    static boolean setField(Object target, String name, Object value) {
        if (target == null) return false;
        Field f = findField(target.getClass(), name);
        if (f == null) return false;
        try { f.set(target, value); return true; }
        catch (Throwable ignored) { return false; }
    }

    static Method findMethod(Class<?> type, String name, int parameterCount) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == parameterCount) {
                    try { m.setAccessible(true); } catch (Throwable ignored) {}
                    return m;
                }
            }
        }
        for (Method m : type.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == parameterCount) return m;
        }
        return null;
    }

    static Method findCompatibleMethod(Class<?> type, String name, Object... args) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
                Class<?>[] p = m.getParameterTypes();
                boolean ok = true;
                for (int i = 0; i < p.length; i++) {
                    if (!compatible(p[i], args[i])) { ok = false; break; }
                }
                if (ok) {
                    try { m.setAccessible(true); } catch (Throwable ignored) {}
                    return m;
                }
            }
        }
        return null;
    }

    static boolean compatible(Class<?> type, Object value) {
        if (value == null) return !type.isPrimitive();
        if (!type.isPrimitive()) return type.isInstance(value);
        return (type == boolean.class && value instanceof Boolean)
                || (type == int.class && value instanceof Integer)
                || (type == long.class && value instanceof Long)
                || (type == double.class && value instanceof Double)
                || (type == float.class && value instanceof Float)
                || (type == short.class && value instanceof Short)
                || (type == byte.class && value instanceof Byte)
                || (type == char.class && value instanceof Character);
    }

    static Object invoke(Object target, String name, Object... args) throws Exception {
        if (target == null) throw new NullPointerException("target");
        Method m = findCompatibleMethod(target.getClass(), name, args);
        if (m == null) throw new NoSuchMethodException(target.getClass().getName() + "." + name);
        try { return m.invoke(target, args); }
        catch (InvocationTargetException e) { throw rethrow(e.getCause()); }
    }

    static Object invokeQuiet(Object target, String name, Object... args) {
        try { return invoke(target, name, args); } catch (Throwable ignored) { return null; }
    }

    static Exception rethrow(Throwable t) {
        if (t instanceof Exception e) return e;
        return new RuntimeException(t);
    }

    static Throwable unwrap(Throwable t) {
        Throwable x = t;
        while (x instanceof InvocationTargetException && x.getCause() != null) x = x.getCause();
        while (x.getCause() != null && x.getCause() != x) x = x.getCause();
        return x;
    }

    static Object objectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> "ZazusServerSeekerCallback@" + Integer.toHexString(System.identityHashCode(proxy));
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == (args == null || args.length == 0 ? null : args[0]);
            default -> null;
        };
    }

    static Object literal(String text) throws Exception {
        Class<?> component = firstClass("net.minecraft.network.chat.Component", "net.minecraft.text.Text");
        for (String name : List.of("literal", "of")) {
            try {
                Method m = component.getMethod(name, String.class);
                return m.invoke(null, text);
            } catch (NoSuchMethodException ignored) {}
        }
        throw new NoSuchMethodException("Component.literal/Text.of");
    }

    @SuppressWarnings("unchecked")
    static List<Object> widgets(Object screen) throws Exception {
        Class<?> screens = Class.forName("net.fabricmc.fabric.api.client.screen.v1.Screens");
        for (String n : List.of("getWidgets", "getButtons")) {
            for (Method m : screens.getMethods()) {
                if (m.getName().equals(n) && Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 1) {
                    Object value = m.invoke(null, screen);
                    if (value instanceof List<?> list) return (List<Object>) list;
                }
            }
        }
        throw new NoSuchMethodException("Screens.getWidgets(Screen)");
    }

    /** Returns every distinct object referenced by Screen list fields, including
     * Fabric's clickable widget list plus renderables/narratables. This is used
     * only for duplicate-control cleanup; callers must operate on a snapshot. */
    static List<Object> screenListElements(Object screen) {
        if (screen == null) return List.of();
        ArrayList<Object> out = new ArrayList<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        try {
            for (Object value : widgets(screen)) if (value != null && seen.add(value)) out.add(value);
        } catch (Throwable ignored) {}
        for (Class<?> c = screen.getClass(); c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!List.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.trySetAccessible();
                    Object value = f.get(screen);
                    if (!(value instanceof List<?> list)) continue;
                    for (Object element : list) if (element != null && seen.add(element)) out.add(element);
                } catch (Throwable ignored) {}
            }
        }
        return out;
    }

    static void addWidget(Object screen, Object widget) throws Exception {
        if (screen == null || widget == null) return;
        for (String n : List.of("addRenderableWidget", "addDrawableChild", "addWidget")) {
            Method m = findCompatibleMethod(screen.getClass(), n, widget);
            if (m != null) {
                try { m.invoke(screen, widget); return; }
                catch (InvocationTargetException e) { throw rethrow(e.getCause()); }
            }
        }
        widgets(screen).add(widget);
    }

    static void removeWidget(Object screen, Object widget) {
        if (screen == null || widget == null) return;

        // Invoke Minecraft's removal hook when available, but always continue with
        // an identity sweep. On 26.2 some Screen implementations remove a widget
        // from children while leaving the same object in renderables/narratables,
        // which produces the ghost/overlapping buttons seen on Multiplayer.
        Method remove = findCompatibleMethod(screen.getClass(), "removeWidget", widget);
        if (remove == null) remove = findCompatibleMethod(screen.getClass(), "remove", widget);
        if (remove != null) {
            try { remove.invoke(screen, widget); } catch (Throwable ignored) {}
        }

        try {
            List<Object> fabricWidgets = widgets(screen);
            fabricWidgets.removeIf(v -> v == widget);
        } catch (Throwable ignored) {}

        for (Class<?> c = screen.getClass(); c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!List.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.trySetAccessible();
                    Object value = f.get(screen);
                    if (value instanceof List<?> raw) {
                        @SuppressWarnings("unchecked") List<Object> list = (List<Object>) raw;
                        list.removeIf(v -> v == widget);
                    }
                } catch (Throwable ignored) {}
            }
        }
    }

    static Object makeButton(String text, int x, int y, int width, int height, Consumer<Object> pressed) throws Exception {
        Class<?> buttonClass = firstClass("net.minecraft.client.gui.components.Button", "net.minecraft.client.gui.widget.ButtonWidget");
        Object message = literal(text);

        // Modern Mojmap/Fabric: Button.builder(Component, Button.OnPress)
        for (Class<?> nested : buttonClass.getDeclaredClasses()) {
            if (!nested.getSimpleName().equals("OnPress")) continue;
            Object callback = Proxy.newProxyInstance(nested.getClassLoader(), new Class<?>[]{nested}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
                if (args != null && args.length > 0) pressed.accept(args[0]);
                else pressed.accept(null);
                return null;
            });
            Method builder = null;
            for (Method m : buttonClass.getMethods()) {
                if (Modifier.isStatic(m.getModifiers()) && m.getName().equals("builder") && m.getParameterCount() == 2) {
                    builder = m; break;
                }
            }
            if (builder != null) {
                Object b = builder.invoke(null, message, callback);
                Method bounds = findMethod(b.getClass(), "bounds", 4);
                if (bounds != null) bounds.invoke(b, x, y, width, height);
                else {
                    invokeQuiet(b, "position", x, y);
                    invokeQuiet(b, "size", width, height);
                }
                Method build = findMethod(b.getClass(), "build", 0);
                if (build == null) throw new NoSuchMethodException("Button.Builder.build()");
                return build.invoke(b);
            }
        }

        // Yarn fallback: ButtonWidget.builder(Text, PressAction)
        for (Method m : buttonClass.getMethods()) {
            if (!Modifier.isStatic(m.getModifiers()) || !m.getName().equals("builder") || m.getParameterCount() != 2) continue;
            Class<?> callbackType = m.getParameterTypes()[1];
            Object callback = Proxy.newProxyInstance(callbackType.getClassLoader(), new Class<?>[]{callbackType}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
                pressed.accept(args != null && args.length > 0 ? args[0] : null);
                return null;
            });
            Object b = m.invoke(null, message, callback);
            Method dimensions = findMethod(b.getClass(), "dimensions", 4);
            if (dimensions != null) dimensions.invoke(b, x, y, width, height);
            else {
                Method pos = findMethod(b.getClass(), "position", 2);
                if (pos != null) pos.invoke(b, x, y);
                Method size = findMethod(b.getClass(), "size", 2);
                if (size != null) size.invoke(b, width, height);
            }
            Method build = findMethod(b.getClass(), "build", 0);
            if (build != null) return build.invoke(b);
        }
        throw new NoSuchMethodException("No supported Button builder found");
    }

    static void setButtonText(Object button, String text) {
        if (button == null) return;
        try {
            Object component = literal(text);
            Method m = findCompatibleMethod(button.getClass(), "setMessage", component);
            if (m == null) m = findCompatibleMethod(button.getClass(), "setMessage", component);
            if (m != null) { m.invoke(button, component); return; }
            m = findCompatibleMethod(button.getClass(), "setText", component);
            if (m != null) m.invoke(button, component);
        } catch (Throwable ignored) {}
    }

    static boolean readBoolean(Object target, String field, boolean fallback) {
        Object v = getField(target, field);
        return v instanceof Boolean b ? b : fallback;
    }

    static void setBoolean(Object target, String field, boolean value) {
        if (!setField(target, field, value)) {
            String setter = "set" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
            invokeQuiet(target, setter, value);
        }
    }

    static int intValue(Object target, String name, int fallback) {
        Object v = invokeQuiet(target, name);
        if (v instanceof Number n) return n.intValue();
        v = getField(target, name);
        return v instanceof Number n ? n.intValue() : fallback;
    }

    static double doubleValue(Object target, String name, double fallback) {
        Object v = invokeQuiet(target, name);
        if (v instanceof Number n) return n.doubleValue();
        v = getField(target, name);
        return v instanceof Number n ? n.doubleValue() : fallback;
    }

    static void setPosition(Object widget, int x, int y) {
        if (widget == null) return;
        if (invokeQuiet(widget, "setPosition", x, y) != null) return;
        invokeQuiet(widget, "setX", x);
        invokeQuiet(widget, "setY", y);
        setField(widget, "x", x);
        setField(widget, "y", y);
    }

    static Object currentScreen(Object client) {
        return ScreenCompat.currentScreen(client);
    }

    static void setScreen(Object client, Object screen) throws Exception {
        ScreenCompat.setScreen(client, screen);
    }

    static void execute(Object client, Runnable task) {
        if (client == null) { task.run(); return; }
        Method m = findCompatibleMethod(client.getClass(), "execute", task);
        if (m == null) m = findCompatibleMethod(client.getClass(), "tell", task);
        if (m != null) {
            try { m.invoke(client, task); return; } catch (Throwable ignored) {}
        }
        task.run();
    }

    static Object minecraftInstance() {
        for (String name : List.of("net.minecraft.client.Minecraft", "net.minecraft.client.MinecraftClient")) {
            try {
                Class<?> c = Class.forName(name);
                for (String method : List.of("getInstance", "getInstance")) {
                    try { return c.getMethod(method).invoke(null); } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    static int screenWidth(Object screen, int fallback) {
        int w = intValue(screen, "width", fallback);
        if (w == fallback) w = intValue(screen, "getWidth", fallback);
        return w;
    }

    static int screenHeight(Object screen, int fallback) {
        int h = intValue(screen, "height", fallback);
        if (h == fallback) h = intValue(screen, "getHeight", fallback);
        return h;
    }

    static boolean isScreen(Object screen, String simpleName) {
        if (screen == null) return false;
        for (Class<?> c = screen.getClass(); c != null; c = c.getSuperclass()) {
            if (c.getSimpleName().equals(simpleName)) return true;
        }
        return false;
    }

    static Object registerStaticEvent(String holderClassName, String fieldName, String callbackInterfaceName, InvocationHandler handler) throws Exception {
        Class<?> holder = Class.forName(holderClassName);
        Class<?> callback = Class.forName(callbackInterfaceName);
        Object event = holder.getField(fieldName).get(null);
        Object listener = Proxy.newProxyInstance(callback.getClassLoader(), new Class<?>[]{callback}, handler);
        RuntimeAccess.registerEvent(event, listener);
        return listener;
    }
}
