import java.util.*;

/**
 * Minimal, dependency-free JSON reader/writer.
 *
 * Supports objects, arrays, strings, numbers, true/false/null — enough to
 * read our own settings files and to read STAR-CCM+/ParaView-style
 * ".colormap" exports (which are Python dict reprs using single quotes;
 * swap those for double quotes before calling parse()).
 *
 * Not a general-purpose JSON library — no unicode escapes beyond \n \t \r
 * \" \\, and object keys are assumed to always be quoted strings. That's
 * true for every file this app produces or consumes.
 */
public class MiniJson {

    // ---- Parsing ------------------------------------------------------

    public static Object parse(String text) {
        Parser p = new Parser(text);
        return p.parseValue();
    }

    private static class Parser {
        final String s;
        int i = 0;

        Parser(String s) { this.s = s; }

        void skipWhitespace() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        Object parseValue() {
            skipWhitespace();
            char c = s.charAt(i);
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (c == 't' || c == 'f') return parseBoolean();
            if (c == 'n') { i += 4; return null; }
            return parseNumber();
        }

        Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            i++; // consume '{'
            skipWhitespace();
            if (s.charAt(i) == '}') { i++; return map; }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                i++; // consume ':'
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                char c = s.charAt(i);
                i++; // consume ',' or '}'
                if (c == '}') break;
            }
            return map;
        }

        List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            i++; // consume '['
            skipWhitespace();
            if (s.charAt(i) == ']') { i++; return list; }
            while (true) {
                Object value = parseValue();
                list.add(value);
                skipWhitespace();
                char c = s.charAt(i);
                i++; // consume ',' or ']'
                if (c == ']') break;
            }
            return list;
        }

        String parseString() {
            i++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (s.charAt(i) != '"') {
                char c = s.charAt(i);
                if (c == '\\') {
                    i++;
                    char esc = s.charAt(i);
                    switch (esc) {
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        case 'r': sb.append('\r'); break;
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        default: sb.append(esc);
                    }
                } else {
                    sb.append(c);
                }
                i++;
            }
            i++; // closing quote
            return sb.toString();
        }

        Boolean parseBoolean() {
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            i += 5;
            return Boolean.FALSE;
        }

        Double parseNumber() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            return Double.parseDouble(s.substring(start, i));
        }
    }

    // ---- Writing (only what our own settings files need) --------------

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(value, sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(Object value, StringBuilder sb) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Map) {
            sb.append("{");
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) value).entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append('"').append(escape(e.getKey())).append("\":");
                writeValue(e.getValue(), sb);
            }
            sb.append("}");
        } else if (value instanceof List) {
            sb.append("[");
            boolean first = true;
            for (Object v : (List<Object>) value) {
                if (!first) sb.append(",");
                first = false;
                writeValue(v, sb);
            }
            sb.append("]");
        } else if (value instanceof String) {
            sb.append('"').append(escape((String) value)).append('"');
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value.toString());
        } else {
            sb.append('"').append(escape(value.toString())).append('"');
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ---- Convenience accessors -----------------------------------------

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object o) { return (Map<String, Object>) o; }

    @SuppressWarnings("unchecked")
    public static List<Object> asArray(Object o) { return (List<Object>) o; }

    public static double asDouble(Object o) { return ((Number) o).doubleValue(); }

    public static String asString(Object o) { return (String) o; }
}
