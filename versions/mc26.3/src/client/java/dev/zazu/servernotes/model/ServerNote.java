package dev.zazu.servernotes.model;

import dev.zazu.servernotes.util.TimeUtil;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class ServerNote {
    private String id;
    private String text;
    private String category;
    private String createdAt;
    private String editedAt;

    public ServerNote(String text, String category) {
        this(UUID.randomUUID().toString(), text, category, TimeUtil.now(), null);
    }

    public ServerNote(String id, String text, String category, String createdAt, String editedAt) {
        this.id = id;
        this.text = text;
        this.category = category == null ? "" : category;
        this.createdAt = createdAt;
        this.editedAt = editedAt;
    }

    public String id() { return id; }
    public String text() { return text; }
    public String category() { return category; }
    public String createdAt() { return createdAt; }
    public String editedAt() { return editedAt; }

    public void update(String newText, String newCategory) {
        this.text = newText;
        this.category = newCategory == null ? "" : newCategory;
        this.editedAt = TimeUtil.now();
    }

    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("text", text);
        map.put("category", category);
        map.put("createdAt", createdAt);
        map.put("editedAt", editedAt);
        return map;
    }

    public static ServerNote fromMap(Map<String, Object> map) {
        return new ServerNote(
            value(map, "id", UUID.randomUUID().toString()),
            value(map, "text", ""),
            value(map, "category", ""),
            value(map, "createdAt", TimeUtil.now()),
            nullableValue(map, "editedAt")
        );
    }

    private static String value(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value instanceof String string ? string : fallback;
    }

    private static String nullableValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof String string ? string : null;
    }
}
