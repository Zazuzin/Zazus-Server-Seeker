package dev.zazuzin.zst;

import java.lang.reflect.*;
import java.util.*;

/** Robust extraction/classification of Minecraft connection-failure text. */
final class DisconnectReason {
    enum CleanupCause {
        NONE(""),
        WHITELIST("Whitelist rejection"),
        REQUIRED_MODS("Required client mods"),
        INVALID_ADDRESS("Invalid address or DNS"),
        UNREACHABLE("Unreachable server");

        private final String label;
        CleanupCause(String label) { this.label = label; }
        String label() { return label; }
    }

    private static final List<String> ACCESSORS = List.of(
            "reason", "getReason", "message", "getMessage", "title", "getTitle",
            "description", "getDescription", "info", "details", "getDetails",
            "component", "getComponent", "getNarrationMessage"
    );

    private DisconnectReason() {}

    static boolean isDisconnectScreen(Object screen) {
        if (screen == null) return false;
        for (Class<?> c = screen.getClass(); c != null; c = c.getSuperclass()) {
            String simple = c.getSimpleName().toLowerCase(Locale.ROOT);
            if (simple.contains("disconnected") || simple.contains("disconnectscreen") || simple.contains("connectionfailed")) {
                return true;
            }
        }
        return false;
    }

    static String extract(Object screen) {
        LinkedHashSet<String> texts = new LinkedHashSet<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        collect(screen, texts, seen, 0);

        // Some 26.2 failure screens keep the user-visible reason in a child
        // component instead of a named reason/details field. Inspect widget text
        // as a final structured fallback.
        try {
            for (Object widget : Reflection.widgets(screen)) collect(widget, texts, seen, 1);
        } catch (Throwable ignored) {}

        return String.join(" | ", texts);
    }

    static boolean isWhitelistRejection(String reason) {
        String normalized = normalize(reason);
        if (normalized.isBlank()) return false;
        return normalized.contains("whitelist")
                || normalized.contains("white list")
                || normalized.contains("white listed")
                || normalized.contains("not whitelisted")
                || normalized.contains("not white listed")
                || normalized.contains("not on the whitelist")
                || normalized.contains("not on whitelist")
                || normalized.contains("not on the white list");
    }

    /**
     * Returns only cleanup causes that are safe to act on. Minecraft protocol
     * or client-version mismatches are deliberately excluded because
     * ViaFabricPlus may reconnect successfully using a different version.
     */
    static CleanupCause cleanupCause(String reason) {
        String normalized = normalize(reason);
        if (normalized.isBlank()) return CleanupCause.NONE;
        if (isWhitelistRejection(normalized)) return CleanupCause.WHITELIST;
        if (isVersionMismatch(normalized)) return CleanupCause.NONE;

        if (containsAny(normalized,
                "missing required mod", "missing required mods", "missing mods",
                "requires the following mod", "requires the following mods",
                "required client mod", "required client mods", "required mods:",
                "client mod required", "client mods required", "mod is required", "mods are required",
                "fabric mods are required", "forge mods are required",
                "fabric loader is required", "forge is required", "neoforge is required",
                "quilt loader is required", "requires fabric loader", "requires forge",
                "requires neoforge", "requires quilt loader",
                "please install fabric loader", "please install forge", "please install neoforge",
                "please install quilt loader", "running fabric, but you are not",
                "running forge, but you are not", "running neoforge, but you are not",
                "running quilt, but you are not",
                "install the following mod", "install the following mods",
                "you need to install the mod", "you need to install the following",
                "failed mod list check", "mod list is not compatible")) {
            return CleanupCause.REQUIRED_MODS;
        }
        if (containsAny(normalized,
                "unknown host", "no such host", "unresolved address",
                "cannot resolve hostname", "could not resolve hostname",
                "invalid hostname", "invalid server address", "invalid address")) {
            return CleanupCause.INVALID_ADDRESS;
        }
        if (containsAny(normalized,
                "connection refused", "no route to host", "network is unreachable",
                "network unreachable", "port unreachable", "getsockopt")) {
            return CleanupCause.UNREACHABLE;
        }
        return CleanupCause.NONE;
    }

    static boolean isVersionMismatch(String reason) {
        String normalized = normalize(reason);
        if (normalized.isBlank()) return false;
        return containsAny(normalized,
                "outdated client", "outdated server", "incompatible client",
                "unsupported client version", "unsupported minecraft version",
                "incorrect protocol version", "protocol version mismatch",
                "please use minecraft", "server is on version",
                "requires minecraft version", "different minecraft version");
    }

    static boolean isRateLimited(String reason) {
        String normalized = normalize(reason);
        if (normalized.isBlank()) return false;
        return normalized.contains("ratelimiter")
                || normalized.contains("rate limiter")
                || normalized.contains("rate limit")
                || normalized.contains("too many requests")
                || normalized.contains("disallowed request");
    }

    /** Returns the useful disconnect detail without generic screen/button text. */
    static String concise(String reason) {
        if (reason == null || reason.isBlank()) return "Connection failed (Minecraft did not provide a reason).";
        String best = "";
        for (String part : reason.split("\\s*\\|\\s*")) {
            String clean = part.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
            if (clean.isBlank()) continue;
            String lower = clean.toLowerCase(Locale.ROOT);
            if (lower.equals("connection lost") || lower.equals("disconnected")
                    || lower.equals("failed to connect to server") || lower.equals("failed to connect to the server")
                    || lower.equals("back to server list") || lower.equals("back to title screen")
                    || lower.equals("cancel") || lower.equals("done")) continue;
            if (clean.length() > best.length()) best = clean;
        }
        if (best.isBlank()) return "Connection failed (Minecraft did not provide a reason).";
        return best.length() <= 500 ? best : best.substring(0, 497) + "...";
    }

    static String normalize(String reason) {
        if (reason == null) return "";
        return reason.toLowerCase(Locale.ROOT)
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replace('-', ' ')
                .replace('_', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }

    private static void collect(Object source, LinkedHashSet<String> texts, Set<Object> seen, int depth) {
        if (source == null || depth > 4 || texts.size() >= 40) return;
        if (source instanceof CharSequence chars) {
            addText(texts, chars.toString());
            return;
        }
        if (source instanceof Throwable throwable) {
            addText(texts, throwable.getMessage());
            collect(throwable.getCause(), texts, seen, depth + 1);
            return;
        }
        if (isScalar(source.getClass())) return;
        if (!seen.add(source)) return;

        String className = source.getClass().getName();
        if (looksLikeTextComponent(className)) {
            addText(texts, RuntimeAccess.componentText(source));
        }

        for (String accessor : ACCESSORS) {
            Object value = Reflection.invokeQuiet(source, accessor);
            if (value != null && value != source) {
                if (looksLikeTextValue(value)) addText(texts, RuntimeAccess.componentText(value));
                collect(value, texts, seen, depth + 1);
            }
        }

        // Record components are common for modern Minecraft detail carriers
        // (for example disconnection details). Their accessor names can change
        // independently of field accessibility, so inspect all record values.
        try {
            if (source.getClass().isRecord()) {
                for (RecordComponent component : source.getClass().getRecordComponents()) {
                    try {
                        Method accessor = component.getAccessor();
                        accessor.trySetAccessible();
                        Object value = accessor.invoke(source);
                        if (value != null && value != source) collect(value, texts, seen, depth + 1);
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}

        for (Class<?> c = source.getClass(); c != null; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                String name = field.getName().toLowerCase(Locale.ROOT);
                if (!interestingField(name, field.getType())) continue;
                try {
                    field.trySetAccessible();
                    Object value = field.get(source);
                    if (value != null && value != source) collect(value, texts, seen, depth + 1);
                } catch (Throwable ignored) {}
            }
        }

        if (source instanceof Collection<?> collection) {
            int count = 0;
            for (Object value : collection) {
                if (count++ >= 16) break;
                collect(value, texts, seen, depth + 1);
            }
        } else if (source.getClass().isArray() && !source.getClass().getComponentType().isPrimitive()) {
            int length = Math.min(Array.getLength(source), 16);
            for (int i = 0; i < length; i++) collect(Array.get(source, i), texts, seen, depth + 1);
        }
    }

    private static boolean interestingField(String name, Class<?> type) {
        if (name.contains("reason") || name.contains("message") || name.contains("title")
                || name.contains("detail") || name.contains("info") || name.contains("cause")
                || name.contains("description") || name.contains("component") || name.contains("text")) {
            return true;
        }
        String typeName = type.getName().toLowerCase(Locale.ROOT);
        return typeName.contains("component") || typeName.contains("disconnect") || typeName.contains("message");
    }

    private static boolean looksLikeTextValue(Object value) {
        if (value instanceof CharSequence) return true;
        return looksLikeTextComponent(value.getClass().getName());
    }

    private static boolean looksLikeTextComponent(String className) {
        String lower = className.toLowerCase(Locale.ROOT);
        return lower.contains("component") || lower.contains("message") || lower.contains("disconnect");
    }

    private static boolean isScalar(Class<?> type) {
        return type.isPrimitive() || Number.class.isAssignableFrom(type) || type == Boolean.class
                || type == Character.class || type.isEnum() || type == Class.class;
    }

    private static void addText(Set<String> texts, String text) {
        if (text == null) return;
        String clean = text.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
        if (clean.isBlank() || clean.length() > 2_000) return;
        // Avoid noisy default Object#toString values when RuntimeAccess had no
        // Component#getString method to call.
        if (clean.matches("^[\\w.$]+@[0-9a-fA-F]+$")) return;
        texts.add(clean);
    }
}
