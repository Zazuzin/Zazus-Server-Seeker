package dev.zazu.servernotes.model;

import dev.zazu.servernotes.util.TimeUtil;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class ServerLocation {
    private String id;
    private String name;
    private double x;
    private double y;
    private double z;
    private String dimension;
    private String createdAt;
    private String editedAt;

    public ServerLocation(String name, double x, double y, double z, String dimension) {
        this(UUID.randomUUID().toString(), name, x, y, z, dimension, TimeUtil.now(), null);
    }

    public ServerLocation(String id, String name, double x, double y, double z, String dimension, String createdAt, String editedAt) {
        this.id = id;
        this.name = name;
        this.x = x;
        this.y = y;
        this.z = z;
        this.dimension = dimension;
        this.createdAt = createdAt;
        this.editedAt = editedAt;
    }

    public String id() { return id; }
    public String name() { return name; }
    public double x() { return x; }
    public double y() { return y; }
    public double z() { return z; }
    public String dimension() { return dimension; }
    public String createdAt() { return createdAt; }
    public String editedAt() { return editedAt; }

    public void update(String newName, double newX, double newY, double newZ, String newDimension) {
        this.name = newName;
        this.x = newX;
        this.y = newY;
        this.z = newZ;
        this.dimension = newDimension;
        this.editedAt = TimeUtil.now();
    }

    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("name", name);
        map.put("x", x);
        map.put("y", y);
        map.put("z", z);
        map.put("dimension", dimension);
        map.put("createdAt", createdAt);
        map.put("editedAt", editedAt);
        return map;
    }

    public static ServerLocation fromMap(Map<String, Object> map) {
        return new ServerLocation(
            string(map, "id", UUID.randomUUID().toString()),
            string(map, "name", "Saved Location"),
            number(map, "x", 0.0),
            number(map, "y", 0.0),
            number(map, "z", 0.0),
            string(map, "dimension", "minecraft:overworld"),
            string(map, "createdAt", TimeUtil.now()),
            nullableString(map, "editedAt")
        );
    }

    private static String string(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value instanceof String string ? string : fallback;
    }

    private static String nullableString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof String string ? string : null;
    }

    private static double number(Map<String, Object> map, String key, double fallback) {
        Object value = map.get(key);
        return value instanceof Number number ? number.doubleValue() : fallback;
    }
}
