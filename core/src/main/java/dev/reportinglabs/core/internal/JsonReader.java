package dev.reportinglabs.core.internal;

import java.util.*;

/**
 * Tiny JSON parser. Only what's needed to read a reporting-labs.history.json
 * file back at the start of a run. Handles objects, arrays, strings,
 * numbers, booleans and null. No streaming, no schema — fine for a file
 * that stays a few tens of KB.
 */
final class JsonReader {
    private final String s;
    private int i;

    JsonReader(String s) { this.s = s; this.i = 0; }

    Object read() {
        skipWs();
        Object out = readValue();
        skipWs();
        return out;
    }

    private Object readValue() {
        skipWs();
        if (i >= s.length()) throw err("unexpected end");
        char c = s.charAt(i);
        if (c == '{') return readObject();
        if (c == '[') return readArray();
        if (c == '"') return readString();
        if (c == 't' || c == 'f') return readBool();
        if (c == 'n') return readNull();
        return readNumber();
    }

    private Map<String, Object> readObject() {
        expect('{');
        Map<String, Object> m = new LinkedHashMap<>();
        skipWs();
        if (peek() == '}') { i++; return m; }
        while (true) {
            skipWs();
            String key = readString();
            skipWs();
            expect(':');
            Object v = readValue();
            m.put(key, v);
            skipWs();
            char c = s.charAt(i);
            if (c == ',') { i++; continue; }
            if (c == '}') { i++; return m; }
            throw err("expected , or }");
        }
    }

    private List<Object> readArray() {
        expect('[');
        List<Object> out = new ArrayList<>();
        skipWs();
        if (peek() == ']') { i++; return out; }
        while (true) {
            out.add(readValue());
            skipWs();
            char c = s.charAt(i);
            if (c == ',') { i++; continue; }
            if (c == ']') { i++; return out; }
            throw err("expected , or ]");
        }
    }

    private String readString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') return sb.toString();
            if (c == '\\' && i < s.length()) {
                char esc = s.charAt(i++);
                switch (esc) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u':
                        if (i + 4 > s.length()) throw err("bad \\u escape");
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default: sb.append(esc);
                }
            } else {
                sb.append(c);
            }
        }
        throw err("unterminated string");
    }

    private Boolean readBool() {
        if (s.startsWith("true", i))  { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        throw err("bad boolean");
    }

    private Object readNull() {
        if (s.startsWith("null", i)) { i += 4; return null; }
        throw err("bad null");
    }

    private Number readNumber() {
        int start = i;
        if (peek() == '-') i++;
        while (i < s.length() && "0123456789.eE+-".indexOf(s.charAt(i)) >= 0) i++;
        String tok = s.substring(start, i);
        if (tok.indexOf('.') < 0 && tok.indexOf('e') < 0 && tok.indexOf('E') < 0) {
            try { return Long.parseLong(tok); } catch (NumberFormatException ignore) {}
        }
        return Double.parseDouble(tok);
    }

    private void expect(char c) {
        if (i >= s.length() || s.charAt(i) != c) throw err("expected " + c);
        i++;
    }

    private char peek() { return i < s.length() ? s.charAt(i) : '\0'; }

    private void skipWs() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

    private IllegalArgumentException err(String msg) {
        return new IllegalArgumentException("JSON parse: " + msg + " at index " + i);
    }
}
