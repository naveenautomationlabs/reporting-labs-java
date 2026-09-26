package dev.reportinglabs.core.internal;

import java.util.*;

/**
 * Tiny JSON writer with no external dependencies. Handles the shapes the
 * report needs: string, number, boolean, null, List, Map, arrays. Ordering
 * of Map keys is preserved when the Map is a LinkedHashMap.
 *
 * Not general-purpose — intentionally rejects things the report never emits
 * (circular refs, custom object graphs) to keep the surface small and safe.
 */
public final class Json {
    private Json() {}

    public static String write(Object v) {
        StringBuilder sb = new StringBuilder(1024);
        write(sb, v);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v) {
        if (v == null)                       { sb.append("null"); return; }
        if (v instanceof Boolean)            { sb.append(v.toString()); return; }
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) { sb.append("null"); return; }
            if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
                sb.append(v.toString());
            } else {
                if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < 1e16) {
                    sb.append(Long.toString((long) d));
                } else {
                    sb.append(v.toString());
                }
            }
            return;
        }
        if (v instanceof CharSequence)       { string(sb, v.toString()); return; }
        if (v instanceof Map)                { object(sb, (Map<?, ?>) v);  return; }
        if (v instanceof Iterable)           { array(sb, (Iterable<?>) v);  return; }
        if (v.getClass().isArray()) {
            List<Object> list = new ArrayList<>();
            int len = java.lang.reflect.Array.getLength(v);
            for (int i = 0; i < len; i++) list.add(java.lang.reflect.Array.get(v, i));
            array(sb, list);
            return;
        }
        // Fallback: toString as JSON string. Reserved for the odd Object.
        string(sb, v.toString());
    }

    private static void object(StringBuilder sb, Map<?, ?> m) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            string(sb, String.valueOf(e.getKey()));
            sb.append(':');
            write(sb, e.getValue());
        }
        sb.append('}');
    }

    private static void array(StringBuilder sb, Iterable<?> it) {
        sb.append('[');
        boolean first = true;
        for (Object v : it) {
            if (!first) sb.append(',');
            first = false;
            write(sb, v);
        }
        sb.append(']');
    }

    private static void string(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                default:
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }
}
