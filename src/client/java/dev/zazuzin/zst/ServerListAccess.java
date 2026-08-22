package dev.zazuzin.zst;

import java.lang.reflect.*;
import java.util.*;

/** Reflection-only access to Minecraft's saved-server list and multiplayer rows. */
final class ServerListAccess {
    record Saved(Object data, String endpoint, String name, boolean favourite) {}

    private ServerListAccess() {}

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

    static List<Object> filterServerRows(List<Object> source, ServerCategoryStore.Tab tab) {
        ArrayList<Object> out = new ArrayList<>();
        if (source == null) return out;
        for (Object entry : source) {
            Object data = serverData(entry);
            if (data == null) {
                out.add(entry);
                continue;
            }
            String endpoint = endpoint(data);
            boolean favourite = name(data).startsWith("★ ");
            boolean include = switch (tab) {
                case FAVOURITES -> favourite;
                case SERVERS -> !favourite && !ServerCategoryStore.isScanned(endpoint);
                case SCANNED -> !favourite && ServerCategoryStore.isScanned(endpoint);
            };
            if (include) out.add(entry);
        }
        return out;
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
