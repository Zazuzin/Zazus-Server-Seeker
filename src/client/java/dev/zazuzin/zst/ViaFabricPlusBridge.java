package dev.zazuzin.zst;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Optional ViaFabricPlus compatibility bridge. No hard dependency is required. */
final class ViaFabricPlusBridge {
    private static final Pattern VERSION_PATTERN = Pattern.compile("(26\\.\\d+(?:\\.\\d+)?|1\\.\\d+(?:\\.\\d+){0,2})");
    private static Boolean available;

    private ViaFabricPlusBridge() {}

    static synchronized boolean isAvailable() {
        if (available != null) return available;
        try {
            Class.forName("com.viaversion.viafabricplus.ViaFabricPlus");
            Class.forName("com.viaversion.viaversion.api.protocol.version.ProtocolVersion");
            available = true;
        } catch (Throwable ignored) {
            available = false;
        }
        return available;
    }

    static boolean setTargetProtocol(int protocol) {
        if (!isAvailable() || protocol <= 0) return false;
        try {
            Class<?> protocolVersion = Class.forName("com.viaversion.viaversion.api.protocol.version.ProtocolVersion");
            Object match = protocolVersion.getMethod("getProtocol", int.class).invoke(null, protocol);
            if (match == null) return false;
            try {
                Object known = match.getClass().getMethod("isKnown").invoke(match);
                if (known instanceof Boolean b && !b) return false;
            } catch (Throwable ignored) {}
            return applyTarget(match);
        } catch (Throwable t) {
            logError(t);
            return false;
        }
    }

    static boolean setTargetVersion(String version) {
        if (!isAvailable()) return false;
        String newest = extractNewestVersion(version);
        if (newest.isBlank()) return false;
        try {
            Class<?> protocolVersion = Class.forName("com.viaversion.viaversion.api.protocol.version.ProtocolVersion");
            Object match = protocolVersion.getMethod("getClosest", String.class).invoke(null, newest);
            if (match == null) {
                System.err.println("[Zazu's Server Tool] ViaFabricPlus has no protocol match for " + version);
                return false;
            }
            return applyTarget(match);
        } catch (Throwable t) {
            logError(t);
            return false;
        }
    }

    private static boolean applyTarget(Object version) throws Exception {
        Class<?> via = Class.forName("com.viaversion.viafabricplus.ViaFabricPlus");
        Object impl = via.getMethod("getImpl").invoke(null);
        Method target = null;
        for (Method m : impl.getClass().getMethods()) {
            if (m.getName().equals("setTargetVersion") && m.getParameterCount() == 1
                    && m.getParameterTypes()[0].isInstance(version)) {
                target = m; break;
            }
        }
        if (target == null) {
            for (Method m : impl.getClass().getDeclaredMethods()) {
                if (m.getName().equals("setTargetVersion") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isInstance(version)) {
                    try { m.setAccessible(true); } catch (Throwable ignored) {}
                    target = m; break;
                }
            }
        }
        if (target == null) {
            System.err.println("[Zazu's Server Tool] ViaFabricPlus setTargetVersion API was not found.");
            return false;
        }
        target.invoke(impl, version);
        return true;
    }

    private static String extractNewestVersion(String source) {
        if (source == null) return "";
        Matcher matcher = VERSION_PATTERN.matcher(source);
        List<String> versions = new ArrayList<>();
        while (matcher.find()) versions.add(matcher.group(1));
        if (versions.isEmpty()) return "";
        String newest = versions.get(0);
        for (int i = 1; i < versions.size(); i++) {
            if (compareVersions(versions.get(i), newest) > 0) newest = versions.get(i);
        }
        return newest;
    }

    private static int compareVersions(String a, String b) {
        int[] aa = parseVersion(a), bb = parseVersion(b);
        for (int i = 0; i < Math.max(aa.length, bb.length); i++) {
            int av = i < aa.length ? aa[i] : 0;
            int bv = i < bb.length ? bb[i] : 0;
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
    }

    private static int[] parseVersion(String value) {
        String[] parts = value.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { out[i] = Integer.parseInt(parts[i]); } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    private static void logError(Throwable t) {
        System.err.println("[Zazu's Server Tool] ViaFabricPlus integration error: " + Reflection.unwrap(t));
    }
}
