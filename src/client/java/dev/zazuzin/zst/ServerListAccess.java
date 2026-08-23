package dev.zazuzin.zst;

import java.lang.reflect.*;
import java.util.*;

/** Reflection-only access to Minecraft's saved-server list and multiplayer rows. */
final class ServerListAccess {
    record Saved(Object data, String endpoint, String name, boolean favourite) {}

    private ServerListAccess() {}

    static List<Saved> savedFromScreen(Object screen) {
        Object list = serverListObject(screen);
        if (list == null) return savedFromEntries(onlineEntries(screen));
        ArrayList<Saved> out = new ArrayList<>();
        for (Object data : serverDataList(list)) {
            String endpoint = endpoint(data);
            if (endpoint.isBlank()) continue;
            String name = name(data);
            out.add(new Saved(data, endpoint, name, name.startsWith("★ ")));
        }
        return out;
    }

    /**
     * Rebuilds the vanilla Multiplayer entries from an in-memory filtered
     * ServerList. This uses JoinMultiplayerScreen's already-loaded server data
     * and never reloads servers.dat on category switches.
     */
    static void applyCategory(Object client, Object screen, ServerCategoryStore.Tab tab) throws Exception {
        Object source = serverListObject(screen);
        Object listWidget = listWidget(screen);
        if (source == null || listWidget == null) return;

        Object filtered = createEmptyServerList(client);
        for (Object data : serverDataList(source)) {
            String endpoint = endpoint(data);
            boolean favourite = name(data).startsWith("★ ");
            boolean include = tab == null || switch (tab) {
                case FAVOURITES -> favourite;
                case SERVERS -> !favourite && !ServerCategoryStore.isScanned(endpoint);
                case SCANNED -> !favourite && ServerCategoryStore.isScanned(endpoint);
            };
            if (include) addServerData(filtered, data);
        }

        Method update = compatibleOneArgMethod(listWidget.getClass(), "updateOnlineServers", filtered);
        if (update == null) update = compatibleOneArgMethod(listWidget.getClass(), "setServers", filtered);
        if (update == null) throw new NoSuchMethodException("ServerSelectionList.updateOnlineServers(ServerList)");
        update.invoke(listWidget, filtered);
    }

    private static Object serverListObject(Object screen) {
        Object value = RuntimeAccess.invoke(screen, "getServers");
        if (value == null) value = RuntimeAccess.invoke(screen, "getServerList");
        if (value == null) value = RuntimeAccess.field(screen, "servers");
        if (value == null) value = RuntimeAccess.field(screen, "serverList");
        return value;
    }

    private static Object createEmptyServerList(Object client) throws Exception {
        Class<?> type = Class.forName("net.minecraft.client.multiplayer.ServerList");
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            try { constructor.trySetAccessible(); } catch (Throwable ignored) {}
            Class<?>[] p = constructor.getParameterTypes();
            try {
                if (p.length == 1 && client != null && p[0].isInstance(client)) return constructor.newInstance(client);
                if (p.length == 0) return constructor.newInstance();
            } catch (Throwable ignored) {}
        }
        throw new IllegalStateException("Unsupported ServerList constructor");
    }

    private static void addServerData(Object list, Object data) throws Exception {
        for (Class<?> c = list.getClass(); c != null; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (!method.getName().equals("add") || method.getParameterCount() < 1) continue;
                Class<?>[] p = method.getParameterTypes();
                if (!p[0].isInstance(data) && !p[0].isAssignableFrom(data.getClass())) continue;
                Object[] args = new Object[p.length];
                args[0] = data;
                boolean compatible = true;
                for (int i = 1; i < p.length; i++) {
                    if (p[i] == boolean.class || p[i] == Boolean.class) args[i] = false;
                    else if (!p[i].isPrimitive()) args[i] = null;
                    else { compatible = false; break; }
                }
                if (!compatible) continue;
                try {
                    method.trySetAccessible();
                    method.invoke(list, args);
                    return;
                } catch (Throwable ignored) {}
            }
        }
        List<Object> backing = mutableServerDataList(list);
        if (backing == null) throw new IllegalStateException("Could not populate temporary ServerList");
        backing.add(data);
    }

    private static Method compatibleOneArgMethod(Class<?> owner, String name, Object value) {
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != 1) continue;
                if (value != null && !method.getParameterTypes()[0].isInstance(value)) continue;
                try { method.trySetAccessible(); } catch (Throwable ignored) {}
                return method;
            }
        }
        return null;
    }

    private static List<Object> serverDataList(Object list) {
        List<Object> backing = mutableServerDataList(list);
        if (backing != null) return new ArrayList<>(backing);
        Object sizeValue = RuntimeAccess.invoke(list, "size");
        if (sizeValue instanceof Number n) {
            ArrayList<Object> out = new ArrayList<>();
            for (int i = 0; i < n.intValue(); i++) {
                Object data = RuntimeAccess.invoke(list, "get", i);
                if (data != null) out.add(data);
            }
            return out;
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> mutableServerDataList(Object list) {
        if (list == null) return null;
        for (Class<?> c = list.getClass(); c != null; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (!List.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.trySetAccessible();
                    Object value = field.get(list);
                    if (!(value instanceof List<?> raw)) continue;
                    for (Object element : raw) {
                        if (element != null && serverData(element) == element) return (List<Object>) raw;
                    }
                    // An empty first List field on ServerList is the normal visible
                    // server list; hiddenServerList follows it.
                    if (raw.isEmpty() && field.getName().toLowerCase(Locale.ROOT).contains("server")) return (List<Object>) raw;
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    static List<Saved> savedFromEntries(Collection<Object> entries) {
        ArrayList<Saved> out = new ArrayList<>();
        if (entries == null) return out;
        for (Object entry : entries) {
            Object data = serverData(entry);
            if (data == null) continue;
            String endpoint = endpoint(data);
            if (endpoint.isBlank()) continue;
            String name = name(data);
            out.add(new Saved(data, endpoint, name, name.startsWith("★ ")));
        }
        return out;
    }

    static String selectedEndpoint(Object screen) {
        Object selected = RuntimeAccess.invoke(screen, "getSelected");
        Object data = serverData(selected);
        return data == null ? "" : endpoint(data);
    }

    /**
     * Returns every mutable-looking list on the Multiplayer selection widget that
     * contains saved-server entry objects. Minecraft 26.2 can render from a
     * different backing list than the legacy onlineServers field, so category
     * filtering must keep all of these lists in sync.
     */
    @SuppressWarnings("unchecked")
    static List<List<Object>> serverEntryLists(Object screen) {
        Object listWidget = listWidget(screen);
        if (listWidget == null) return List.of();

        ArrayList<List<Object>> out = new ArrayList<>();
        Set<List<Object>> seen = Collections.newSetFromMap(new IdentityHashMap<>());

        List<Object> preferred = onlineEntries(screen);
        if (preferred != null && seen.add(preferred)) out.add(preferred);

        for (Class<?> c = listWidget.getClass(); c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!List.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object value = f.get(listWidget);
                    if (value instanceof List<?> raw) {
                        List<Object> list = (List<Object>) raw;
                        if (seen.add(list) && containsServerEntry(list)) out.add(list);
                    }
                } catch (Throwable ignored) {}
            }
        }

        Object children = RuntimeAccess.invoke(listWidget, "children");
        if (children instanceof List<?> raw) {
            List<Object> list = (List<Object>) raw;
            if (seen.add(list) && containsServerEntry(list)) out.add(list);
        }
        return out;
    }

    private static boolean containsServerEntry(Collection<?> list) {
        if (list == null) return false;
        for (Object value : list) if (serverData(value) != null) return true;
        return false;
    }

    static Object listWidget(Object screen) {
        Object listWidget = RuntimeAccess.field(screen, "serverSelectionList");
        if (listWidget == null) listWidget = RuntimeAccess.field(screen, "serverList");
        return listWidget;
    }

    @SuppressWarnings("unchecked")
    static List<Object> onlineEntries(Object screen) {
        Object listWidget = listWidget(screen);
        if (listWidget == null) return null;
        Object online = RuntimeAccess.field(listWidget, "onlineServers");
        if (!(online instanceof List<?>)) online = RuntimeAccess.field(listWidget, "serverEntries");
        if (online instanceof List<?> list) return (List<Object>) list;
        Object children = RuntimeAccess.invoke(listWidget, "children");
        if (children instanceof List<?> list) return (List<Object>) list;
        return null;
    }

    static Object serverData(Object entry) {
        if (entry == null) return null;
        Object value = RuntimeAccess.invoke(entry, "getServerData");
        if (value != null) return value;
        value = RuntimeAccess.field(entry, "server");
        if (value == null) value = RuntimeAccess.field(entry, "serverData");
        if (value != null) return value;
        if (RuntimeAccess.field(entry, "ip") != null || RuntimeAccess.field(entry, "address") != null) return entry;
        return null;
    }

    static String endpoint(Object data) {
        if (data == null) return "";
        Object value = RuntimeAccess.field(data, "ip");
        if (value == null) value = RuntimeAccess.field(data, "address");
        if (value == null) value = RuntimeAccess.invoke(data, "ip");
        if (value == null) value = RuntimeAccess.invoke(data, "address");
        if (value == null) value = RuntimeAccess.invoke(data, "getAddress");
        return value == null ? "" : String.valueOf(value).trim();
    }

    static String name(Object data) {
        Object value = RuntimeAccess.field(data, "name");
        return value == null ? "" : String.valueOf(value);
    }

    static boolean forceRemove(Object client, String targetEndpoint) {
        String target = normalize(targetEndpoint);
        if (target.isBlank()) return false;
        try {
            Object list = createLoadedList(client);
            List<Object> all = servers(list);
            Object found = null;
            for (Object data : all) {
                if (normalize(endpoint(data)).equals(target)) { found = data; break; }
            }
            if (found == null) return false;
            if (!invokeRemove(list, found)) {
                if (!all.remove(found)) return false;
            }
            Method save = RuntimeAccess.findMethod(list.getClass(), "save", 0);
            if (save == null) throw new NoSuchMethodException("ServerList.save()");
            save.invoke(list);
            return true;
        } catch (Throwable t) {
            System.err.println("[Zazu's Server Tool] Forced whitelist deletion failed for " + targetEndpoint + ": " + root(t));
            return false;
        }
    }

    static String signature(List<Saved> servers) {
        StringBuilder out = new StringBuilder();
        for (Saved s : servers) {
            out.append(normalize(s.endpoint())).append('|').append(s.favourite() ? 'F' : 'N')
                    .append('|').append(ServerCategoryStore.isScanned(s.endpoint()) ? 'S' : 'V').append(';');
        }
        return out.toString();
    }

    private static Object createLoadedList(Object client) throws Exception {
        Class<?> type = Class.forName("net.minecraft.client.multiplayer.ServerList");
        Object list = null;
        for (Constructor<?> c : type.getDeclaredConstructors()) {
            try { c.setAccessible(true); } catch (Throwable ignored) {}
            Class<?>[] p = c.getParameterTypes();
            try {
                if (p.length == 1 && client != null && p[0].isInstance(client)) { list = c.newInstance(client); break; }
                if (p.length == 0) { list = c.newInstance(); break; }
            } catch (Throwable ignored) {}
        }
        if (list == null) throw new IllegalStateException("Unsupported ServerList constructor");
        Method load = RuntimeAccess.findMethod(type, "load", 0);
        if (load != null) try { load.invoke(list); } catch (Throwable ignored) {}
        return list;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> servers(Object list) {
        for (String method : List.of("getServers", "servers")) {
            Object value = RuntimeAccess.invoke(list, method);
            if (value instanceof List<?> l) return (List<Object>) l;
        }
        for (Class<?> c = list.getClass(); c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!List.class.isAssignableFrom(f.getType())) continue;
                try { f.setAccessible(true); Object v = f.get(list); if (v instanceof List<?> l) return (List<Object>) l; }
                catch (Throwable ignored) {}
            }
        }
        return new ArrayList<>();
    }

    private static boolean invokeRemove(Object list, Object server) {
        for (Class<?> c = list.getClass(); c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("remove") || m.getParameterCount() != 1) continue;
                try { m.setAccessible(true); } catch (Throwable ignored) {}
                Class<?> p = m.getParameterTypes()[0];
                try {
                    if (p.isInstance(server) || p.isAssignableFrom(server.getClass())) { m.invoke(list, server); return true; }
                    if (p == int.class || p == Integer.class) {
                        List<Object> all = servers(list); int idx = all.indexOf(server);
                        if (idx >= 0) { m.invoke(list, idx); return true; }
                    }
                } catch (Throwable ignored) {}
            }
        }
        return false;
    }

    static String normalize(String endpoint) {
        return endpoint == null ? "" : endpoint.trim().toLowerCase(Locale.ROOT);
    }

    private static Throwable root(Throwable t) {
        Throwable current = t;
        while ((current instanceof InvocationTargetException || current instanceof ExceptionInInitializerError) && current.getCause() != null) current = current.getCause();
        return current;
    }
}
