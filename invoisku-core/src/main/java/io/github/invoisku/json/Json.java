package io.github.invoisku.json;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small, strict JSON reader and minified writer.
 *
 * <p>Objects are {@link LinkedHashMap} (key order is kept, which matters for signing), arrays are {@link List},
 * numbers are {@link BigDecimal} (never floating point), plus {@link String}, {@link Boolean} and {@code null}.
 * Writing always produces minified output: no whitespace between tokens, and only the escapes JSON requires.
 */
public final class Json {

    private Json() {}

    // ------------------------------------------------------------------ writing

    /** Writes a value as minified JSON. */
    public static String write(Object value) {
        StringBuilder out = new StringBuilder(256);
        write(value, out);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String s) {
            writeString(s, out);
        } else if (value instanceof Boolean b) {
            out.append(b ? "true" : "false");
        } else if (value instanceof BigDecimal d) {
            out.append(d.toPlainString());
        } else if (value instanceof Integer || value instanceof Long || value instanceof Short) {
            out.append(value);
        } else if (value instanceof Number n) {
            out.append(new BigDecimal(n.toString()).toPlainString());
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) out.append(',');
                first = false;
                writeString(String.valueOf(e.getKey()), out);
                out.append(':');
                write(e.getValue(), out);
            }
            out.append('}');
        } else if (value instanceof List<?> list) {
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) out.append(',');
                write(list.get(i), out);
            }
            out.append(']');
        } else {
            throw new IllegalArgumentException("Not a JSON value: " + value.getClass().getName());
        }
    }

    private static void writeString(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }

    // ------------------------------------------------------------------ reading

    /** Parses JSON text. Throws {@link JsonException} with the character position on bad input. */
    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.value(0);
        p.skipWs();
        if (p.pos != text.length()) throw p.error("Unexpected content after the JSON value");
        return v;
    }

    /** Parses JSON text that must be an object. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) throw new JsonException("Expected a JSON object", 0);
        return (Map<String, Object>) v;
    }

    /** Re-serialises JSON text without whitespace, keeping key order and number text. */
    public static String minify(String text) {
        return write(parse(text));
    }

    /** A deep copy made of mutable maps and lists, so a parsed or built tree can be edited safely. */
    @SuppressWarnings("unchecked")
    public static <T> T deepCopy(T value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(String.valueOf(k), deepCopy(v)));
            return (T) copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object v : list) copy.add(deepCopy(v));
            return (T) copy;
        }
        return value;
    }

    private static final int MAX_DEPTH = 200;

    private static final class Parser {
        final String s;
        int pos;

        Parser(String s) {
            this.s = s;
        }

        JsonException error(String message) {
            return new JsonException(message + " at position " + pos, pos);
        }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') pos++;
                else break;
            }
        }

        Object value(int depth) {
            if (depth > MAX_DEPTH) throw error("JSON nested too deeply");
            if (pos >= s.length()) throw error("Unexpected end of JSON");
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> object(depth);
                case '[' -> array(depth);
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) yield number();
                    throw error("Unexpected character '" + c + "'");
                }
            };
        }

        Object literal(String word, Object v) {
            if (!s.startsWith(word, pos)) throw error("Invalid literal");
            pos += word.length();
            return v;
        }

        Map<String, Object> object(int depth) {
            pos++;
            Map<String, Object> map = new LinkedHashMap<>();
            skipWs();
            if (pos < s.length() && s.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != '"') throw error("Expected a property name");
                String key = string();
                skipWs();
                expect(':');
                skipWs();
                if (map.containsKey(key)) throw error("Duplicate property \"" + key + "\"");
                map.put(key, value(depth + 1));
                skipWs();
                if (pos >= s.length()) throw error("Unterminated object");
                char c = s.charAt(pos++);
                if (c == '}') return map;
                if (c != ',') {
                    pos--;
                    throw error("Expected ',' or '}'");
                }
            }
        }

        List<Object> array(int depth) {
            pos++;
            List<Object> list = new ArrayList<>();
            skipWs();
            if (pos < s.length() && s.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                skipWs();
                list.add(value(depth + 1));
                skipWs();
                if (pos >= s.length()) throw error("Unterminated array");
                char c = s.charAt(pos++);
                if (c == ']') return list;
                if (c != ',') {
                    pos--;
                    throw error("Expected ',' or ']'");
                }
            }
        }

        void expect(char c) {
            if (pos >= s.length() || s.charAt(pos) != c) throw error("Expected '" + c + "'");
            pos++;
        }

        String string() {
            pos++;
            StringBuilder b = new StringBuilder();
            while (true) {
                if (pos >= s.length()) throw error("Unterminated string");
                char c = s.charAt(pos++);
                if (c == '"') return b.toString();
                if (c < 0x20) {
                    pos--;
                    throw error("Control character in string");
                }
                if (c != '\\') {
                    b.append(c);
                    continue;
                }
                if (pos >= s.length()) throw error("Unterminated escape");
                char e = s.charAt(pos++);
                switch (e) {
                    case '"' -> b.append('"');
                    case '\\' -> b.append('\\');
                    case '/' -> b.append('/');
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'n' -> b.append('\n');
                    case 'r' -> b.append('\r');
                    case 't' -> b.append('\t');
                    case 'u' -> {
                        if (pos + 4 > s.length()) throw error("Bad unicode escape");
                        try {
                            b.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw error("Bad unicode escape");
                        }
                        pos += 4;
                    }
                    default -> {
                        pos--;
                        throw error("Bad escape '\\" + e + "'");
                    }
                }
            }
        }

        BigDecimal number() {
            int start = pos;
            if (s.charAt(pos) == '-') pos++;
            int intStart = pos;
            while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
            if (pos == intStart) throw error("Bad number");
            if (pos - intStart > 1 && s.charAt(intStart) == '0') throw error("Leading zero in number");
            if (pos < s.length() && s.charAt(pos) == '.') {
                pos++;
                int f = pos;
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
                if (pos == f) throw error("Bad number");
            }
            if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                pos++;
                if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) pos++;
                int x = pos;
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
                if (pos == x) throw error("Bad number");
            }
            return new BigDecimal(s.substring(start, pos));
        }
    }

    /** Convenience for building trees: an immutable-looking but mutable single-entry list. */
    public static List<Object> list(Object... items) {
        List<Object> l = new ArrayList<>(items.length);
        Collections.addAll(l, items);
        return l;
    }
}
