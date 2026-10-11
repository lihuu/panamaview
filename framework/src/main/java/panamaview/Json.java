package panamaview;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The smallest JSON codec that can carry a bridge message.
 *
 * <p>The bridge deliberately speaks JSON text rather than handing {@code NSDictionary}
 * around: it is the one representation that JavaScript, the Objective-C message body and
 * Java all agree on, and it keeps the native layer free of type conversion logic.
 */
public final class Json {

    private Json() {
    }

    // ------------------------------------------------------------------ write

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
        switch (value) {
            case null -> out.append("null");
            case String s -> escape(s, out);
            case Boolean b -> out.append(b);
            case Number n -> out.append(number(n));
            case Map<?, ?> map -> {
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    escape(String.valueOf(e.getKey()), out);
                    out.append(':');
                    write(e.getValue(), out);
                }
                out.append('}');
            }
            case Iterable<?> it -> {
                out.append('[');
                boolean first = true;
                for (Object o : it) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    write(o, out);
                }
                out.append(']');
            }
            case Object[] array -> {
                out.append('[');
                for (int i = 0; i < array.length; i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    write(array[i], out);
                }
                out.append(']');
            }
            default -> escape(String.valueOf(value), out);
        }
    }

    /** Integral doubles are written without a fractional part, so JSON round-trips as 1, not 1.0. */
    private static String number(Number n) {
        if (n instanceof Double || n instanceof Float) {
            double d = n.doubleValue();
            if (Double.isFinite(d) && d == Math.rint(d) && Math.abs(d) < 9.007199254740992E15) {
                return Long.toString((long) d);
            }
            return Double.toString(d);
        }
        return n.toString();
    }

    private static void escape(String s, StringBuilder out) {
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
                // U+2028/U+2029 are valid JSON but line terminators in JavaScript.
                case '\u2028' -> out.append("\\u2028");
                case '\u2029' -> out.append("\\u2029");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    // ------------------------------------------------------------------ parse

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWhitespace();
        Object value = p.value();
        p.skipWhitespace();
        if (p.pos != text.length()) {
            throw new IllegalArgumentException("trailing content at offset " + p.pos);
        }
        return value;
    }

    private static final class Parser {
        private final String src;
        private int pos;

        Parser(String src) {
            this.src = src;
        }

        void skipWhitespace() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }

        Object value() {
            if (pos >= src.length()) {
                throw err("unexpected end of input");
            }
            char c = src.charAt(pos);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // '{'
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = string();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                map.put(key, value());
                skipWhitespace();
                char c = next();
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw err("expected ',' or '}' but found '" + c + "'");
                }
            }
        }

        private List<Object> array() {
            List<Object> list = new ArrayList<>();
            pos++; // '['
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                skipWhitespace();
                list.add(value());
                skipWhitespace();
                char c = next();
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw err("expected ',' or ']' but found '" + c + "'");
                }
            }
        }

        private String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                char esc = next();
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > src.length()) {
                            throw err("truncated \\u escape");
                        }
                        sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw err("bad escape \\" + esc);
                }
            }
        }

        private Double number() {
            int start = pos;
            if (peek() == '-' || peek() == '+') {
                pos++;
            }
            while (pos < src.length() && "+-.eE0123456789".indexOf(src.charAt(pos)) >= 0) {
                pos++;
            }
            if (start == pos) {
                throw err("expected a value");
            }
            return Double.valueOf(src.substring(start, pos));
        }

        private Object literal(String word, Object value) {
            if (!src.startsWith(word, pos)) {
                throw err("expected " + word);
            }
            pos += word.length();
            return value;
        }

        private char peek() {
            return pos < src.length() ? src.charAt(pos) : '\0';
        }

        private char next() {
            if (pos >= src.length()) {
                throw err("unexpected end of input");
            }
            return src.charAt(pos++);
        }

        private void expect(char c) {
            if (next() != c) {
                throw err("expected '" + c + "'");
            }
        }

        private IllegalArgumentException err(String message) {
            return new IllegalArgumentException(message + " (offset " + pos + ")");
        }
    }
}
