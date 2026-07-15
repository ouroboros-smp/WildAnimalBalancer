package com.ouroboros.wildlife.core;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Small read-only YAML section wrapper with dotted-path accessors. */
public final class YamlSection {
    private final Map<String, Object> values;

    private YamlSection(Map<String, Object> values) {
        this.values = Collections.unmodifiableMap(values);
    }

    /** Loads a YAML document using SnakeYAML's safe constructor. */
    public static YamlSection load(InputStream input) {
        if (input == null) return new YamlSection(Map.of());
        Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
        if (!(root instanceof Map<?, ?> map)) return new YamlSection(Map.of());
        return new YamlSection(copyMap(map));
    }

    /** Wraps a map, recursively copying it into a read-only section. */
    public static YamlSection from(Map<String, ?> values) {
        return new YamlSection(copyMap(values));
    }

    /** Returns an integer value or the supplied default. */
    public int getInt(String path, int defaultValue) {
        Object value = value(path);
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? defaultValue : Integer.parseInt(value.toString());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    /** Returns a long value or the supplied default. */
    public long getLong(String path, long defaultValue) {
        Object value = value(path);
        if (value instanceof Number number) return number.longValue();
        try {
            return value == null ? defaultValue : Long.parseLong(value.toString());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    /** Returns a boolean value or the supplied default. */
    public boolean getBoolean(String path, boolean defaultValue) {
        Object value = value(path);
        if (value instanceof Boolean bool) return bool;
        return value == null ? defaultValue : Boolean.parseBoolean(value.toString());
    }

    /** Returns a string value or the supplied default. */
    public String getString(String path, String defaultValue) {
        Object value = value(path);
        return value == null ? defaultValue : value.toString();
    }

    /** Returns string representations of a configured list. */
    public List<String> getStringList(String path) {
        Object value = value(path);
        if (!(value instanceof List<?> list)) return List.of();
        List<String> strings = new ArrayList<>(list.size());
        for (Object entry : list) {
            if (entry != null) strings.add(entry.toString());
        }
        return List.copyOf(strings);
    }

    /** Returns a nested section, or null when the path is not a map. */
    public YamlSection getSection(String path) {
        Object value = value(path);
        if (!(value instanceof Map<?, ?> map)) return null;
        return new YamlSection(copyMap(map));
    }

    /** Returns the direct keys in this section. */
    public Set<String> keys() {
        return values.keySet();
    }

    private Object value(String path) {
        Object current = values;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) return null;
            current = map.get(part);
        }
        return current;
    }

    private static Map<String, Object> copyMap(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(String.valueOf(key), copyValue(value)));
        return copy;
    }

    private static Object copyValue(Object value) {
        if (value instanceof Map<?, ?> map) return Collections.unmodifiableMap(copyMap(map));
        if (value instanceof List<?> list) return List.copyOf(list);
        return value;
    }
}
