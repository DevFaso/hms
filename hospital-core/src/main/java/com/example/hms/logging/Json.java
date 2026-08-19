package com.example.hms.logging;

import java.util.Map;

/**
 * Minimal JSON writer for {@link SplunkHecAppender}.
 *
 * <p>Why not Jackson: Jackson initializes its own classloader machinery, which during early
 * bootstrap can re-enter the logging subsystem and stack-overflow if a Logback appender depends
 * on it. The appender's payload shape is a fixed envelope of strings, numbers, booleans, and
 * nested maps — escaping rules from RFC 8259 cover it in &lt;60 lines.
 *
 * <p>Package-private: this is an internal collaborator of {@link SplunkHecAppender}, never a
 * public API. If we ever need first-class JSON elsewhere, use Jackson.
 */
final class Json {

    private Json() {}

    /** Serialize a value to JSON. Supported: null, Boolean, Number, String, Map. */
    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        writeValue(out, value);
        return out.toString();
    }

    private static void writeValue(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Boolean b) {
            out.append(b);
        } else if (value instanceof Number n) {
            // Avoid scientific notation for whole longs but keep decimals readable. Splunk HEC
            // accepts both — Double.toString is safe.
            out.append(n);
        } else if (value instanceof Map<?, ?> map) {
            writeMap(out, map);
        } else {
            // Anything else (incl. arbitrary toString'd objects) → render as a string so we never
            // emit invalid JSON for a stray field someone adds later.
            writeString(out, String.valueOf(value));
        }
    }

    private static void writeMap(StringBuilder out, Map<?, ?> map) {
        out.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) out.append(',');
            first = false;
            writeString(out, String.valueOf(entry.getKey()));
            out.append(':');
            writeValue(out, entry.getValue());
        }
        out.append('}');
    }

    private static void writeString(StringBuilder out, String s) {
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
}
