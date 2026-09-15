package dev.zazu.servernotes.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SimpleJson {
    private SimpleJson() {
    }

    public static Object parse(String input) {
        Parser parser = new Parser(input);
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.isAtEnd()) {
            throw new IllegalArgumentException("Trailing JSON content at position " + parser.index);
        }
        return value;
    }

    public static String stringify(Object value) {
        StringBuilder out = new StringBuilder();
        writeValue(value, out, 0);
        out.append('\n');
        return out.toString();
    }

    private static void writeValue(Object value, StringBuilder out, int indent) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String string) {
            writeString(string, out);
        } else if (value instanceof Boolean || value instanceof Number) {
            out.append(value);
        } else if (value instanceof Map<?, ?> map) {
            writeObject(map, out, indent);
        } else if (value instanceof Iterable<?> iterable) {
            writeArray(iterable, out, indent);
        } else {
            writeString(value.toString(), out);
        }
    }

    private static void writeObject(Map<?, ?> map, StringBuilder out, int indent) {
        out.append('{');
        if (!map.isEmpty()) {
            out.append('\n');
            int index = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                indent(out, indent + 1);
                writeString(String.valueOf(entry.getKey()), out);
                out.append(": ");
                writeValue(entry.getValue(), out, indent + 1);
                if (++index < map.size()) {
                    out.append(',');
                }
                out.append('\n');
            }
            indent(out, indent);
        }
        out.append('}');
    }

    private static void writeArray(Iterable<?> iterable, StringBuilder out, int indent) {
        List<Object> values = new ArrayList<>();
        for (Object item : iterable) {
            values.add(item);
        }
        out.append('[');
        if (!values.isEmpty()) {
            out.append('\n');
            for (int i = 0; i < values.size(); i++) {
                indent(out, indent + 1);
                writeValue(values.get(i), out, indent + 1);
                if (i + 1 < values.size()) {
                    out.append(',');
                }
                out.append('\n');
            }
            indent(out, indent);
        }
        out.append(']');
    }

    private static void writeString(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
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

    private static void indent(StringBuilder out, int depth) {
        out.append("  ".repeat(Math.max(0, depth)));
    }

    private static final class Parser {
        private final String input;
        private int index;

        private Parser(String input) {
            this.input = input == null ? "" : input;
        }

        private Object parseValue() {
            skipWhitespace();
            if (isAtEnd()) {
                throw error("Unexpected end of JSON");
            }
            return switch (input.charAt(index)) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObject() {
            expect('{');
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            skipWhitespace();
            if (consume('}')) {
                return result;
            }
            while (true) {
                skipWhitespace();
                if (isAtEnd() || input.charAt(index) != '"') {
                    throw error("Expected object key");
                }
                String key = parseString();
                skipWhitespace();
                expect(':');
                result.put(key, parseValue());
                skipWhitespace();
                if (consume('}')) {
                    return result;
                }
                expect(',');
            }
        }

        private List<Object> parseArray() {
            expect('[');
            ArrayList<Object> result = new ArrayList<>();
            skipWhitespace();
            if (consume(']')) {
                return result;
            }
            while (true) {
                result.add(parseValue());
                skipWhitespace();
                if (consume(']')) {
                    return result;
                }
                expect(',');
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (!isAtEnd()) {
                char c = input.charAt(index++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (isAtEnd()) {
                    throw error("Unterminated escape sequence");
                }
                char escaped = input.charAt(index++);
                switch (escaped) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> out.append(parseUnicodeEscape());
                    default -> throw error("Unsupported escape sequence \\" + escaped);
                }
            }
            throw error("Unterminated string");
        }

        private char parseUnicodeEscape() {
            if (index + 4 > input.length()) {
                throw error("Incomplete unicode escape");
            }
            String hex = input.substring(index, index + 4);
            index += 4;
            try {
                return (char) Integer.parseInt(hex, 16);
            } catch (NumberFormatException exception) {
                throw error("Invalid unicode escape");
            }
        }

        private Object parseNumber() {
            int start = index;
            if (input.charAt(index) == '-') {
                index++;
            }
            while (!isAtEnd() && Character.isDigit(input.charAt(index))) {
                index++;
            }
            boolean decimal = false;
            if (!isAtEnd() && input.charAt(index) == '.') {
                decimal = true;
                index++;
                while (!isAtEnd() && Character.isDigit(input.charAt(index))) {
                    index++;
                }
            }
            if (!isAtEnd() && (input.charAt(index) == 'e' || input.charAt(index) == 'E')) {
                decimal = true;
                index++;
                if (!isAtEnd() && (input.charAt(index) == '+' || input.charAt(index) == '-')) {
                    index++;
                }
                while (!isAtEnd() && Character.isDigit(input.charAt(index))) {
                    index++;
                }
            }
            if (start == index) {
                throw error("Expected JSON value");
            }
            String raw = input.substring(start, index);
            try {
                return decimal ? Double.parseDouble(raw) : Long.parseLong(raw);
            } catch (NumberFormatException exception) {
                throw error("Invalid number " + raw);
            }
        }

        private Object parseLiteral(String expected, Object value) {
            if (!input.startsWith(expected, index)) {
                throw error("Expected " + expected);
            }
            index += expected.length();
            return value;
        }

        private void skipWhitespace() {
            while (!isAtEnd() && Character.isWhitespace(input.charAt(index))) {
                index++;
            }
        }

        private boolean consume(char c) {
            if (!isAtEnd() && input.charAt(index) == c) {
                index++;
                return true;
            }
            return false;
        }

        private void expect(char c) {
            if (!consume(c)) {
                throw error("Expected '" + c + "'");
            }
        }

        private boolean isAtEnd() {
            return index >= input.length();
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at position " + index);
        }
    }
}
