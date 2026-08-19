package com.example.hms.hl7.mllp;

/**
 * Light-weight MSH parser. Just enough to route the message and build
 * an ACK — the full body parsing is delegated to whatever domain handler
 * the dispatcher chooses (e.g. {@code Hl7v2MessageBuilder.parseOruR01}).
 */
public final class Hl7MessageInspector {

    private Hl7MessageInspector() {}

    /**
     * @throws MllpProtocolException if the body does not start with {@code MSH}
     *         or has fewer than the minimum required fields.
     */
    public static Hl7MessageHeader parseHeader(String body) {
        if (body == null || body.isEmpty()) {
            throw new MllpProtocolException("Empty HL7 body");
        }
        if (!body.startsWith("MSH")) {
            throw new MllpProtocolException("HL7 body does not start with MSH");
        }
        char fieldSep = body.charAt(3);

        // Take the first segment line — segments end with CR (0x0D) or LF.
        int eol = indexOfSegmentTerminator(body, 4);
        String mshSegment = (eol < 0) ? body : body.substring(0, eol);

        String[] f = split(mshSegment, fieldSep);
        // f[0]="MSH", f[1]=encoding chars; routing fields start at f[2].
        return new Hl7MessageHeader(
            String.valueOf(fieldSep),
            field(f, 1),
            field(f, 2),
            field(f, 3),
            field(f, 4),
            field(f, 5),
            field(f, 6),
            field(f, 8),
            field(f, 9),
            field(f, 10),
            field(f, 11)
        );
    }

    private static int indexOfSegmentTerminator(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\r' || c == '\n') return i;
        }
        return -1;
    }

    private static String[] split(String segment, char sep) {
        // Manual split that does NOT use regex (sep can be '|' which is regex special).
        java.util.List<String> out = new java.util.ArrayList<>();
        int start = 0;
        for (int i = 0; i < segment.length(); i++) {
            if (segment.charAt(i) == sep) {
                out.add(segment.substring(start, i));
                start = i + 1;
            }
        }
        out.add(segment.substring(start));
        return out.toArray(new String[0]);
    }

    private static String field(String[] f, int idx) {
        if (idx < 0 || idx >= f.length) return "";
        return f[idx] == null ? "" : f[idx];
    }
}
