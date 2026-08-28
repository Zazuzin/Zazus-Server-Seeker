package dev.zazuzin.zst;

import java.lang.reflect.*;
import java.util.*;

/** Robust extraction/classification of Minecraft connection-failure text. */
final class DisconnectReason {
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

    static boolean isRateLimited(String reason) {
        String normalized = normalize(reason);
        if (normalized.isBlank()) return false;
        return normalized.contains("ratelimiter")
                || normalized.contains("rate limiter")
                || normalized.contains("rate limit")
                || normalized.contains("too many requests")
                || normalized.contains("disallowed request");
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
