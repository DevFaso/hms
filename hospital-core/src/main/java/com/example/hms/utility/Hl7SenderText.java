package com.example.hms.utility;

/**
 * Text a sender wrote, made safe to put inside text we write: an audit
 * description, a dead-letter reason, a merge note, a log line.
 *
 * <p>Every value off an HL7 message - MSH-3/MSH-4, MSH-9, MSH-10, a placer,
 * a visit number, a location, an identifier - is the sender's text. Written
 * raw into a sentence an operator reads as ours, it can end the sentence and
 * write the next one ({@code x) identifier not found; cross-tenant rejection
 * (MSH-10 y} rendered as several findings, two of them the sender's), start a
 * new log line, colour a terminal with an ANSI escape, or reorder what is
 * around it with a bidi override.
 *
 * <p>{@link #quote} puts the value in double quotes and escapes anything that
 * could end the quotes early or change how the text around it renders, so
 * the value always reads as one quoted value and never as our words. Our own
 * text around it is never quoted, which is what makes a marker we write
 * ({@code (over 255 characters)}, {@link #ABSENT}) impossible for a sender to
 * imitate: anything a sender supplies arrives in quotes.
 *
 * <p>This is not a width guard. Widths are held once where each field is read
 * ({@link Hl7FieldBounds}); quoting a value never shortens it.
 */
public final class Hl7SenderText {

    /**
     * What {@link #quote} writes for a value that is absent. Unquoted, so no
     * sender value - which always arrives quoted - can be mistaken for it.
     */
    public static final String ABSENT = "(none)";

    private Hl7SenderText() {}

    /**
     * {@code value} in double quotes, whole, with {@code "} and backslash
     * escaped, and every character that could end, reorder or hide the text
     * around it (control, bidi and other format characters, zero-width,
     * line and paragraph separators, surrogates, private-use) shown as a
     * backslash-u hex escape instead of rendered. {@link #ABSENT} for null.
     *
     * <p>Nothing is trimmed or cut: two values that differ only in a trailing
     * control character render differently, which is the point.
     */
    public static String quote(String value) {
        if (value == null) {
            return ABSENT;
        }
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (needsEscape(c)) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.append('"').toString();
    }

    /**
     * Whether {@code c} could make quoted text render as something other than
     * what it is: a control character; a format character (the bidi embeddings,
     * overrides and isolates U+202A-U+202E and U+2066-U+2069, the zero-width
     * characters, the byte-order mark); a line or paragraph separator; a
     * surrogate or private-use code unit. A right-to-left override inside the
     * quotes can make the sender's text appear to sit outside them, so each of
     * these is shown as its escape instead of being rendered.
     */
    private static boolean needsEscape(char c) {
        if (Character.isISOControl(c)) {
            return true;
        }
        int type = Character.getType(c);
        return type == Character.FORMAT
            || type == Character.LINE_SEPARATOR
            || type == Character.PARAGRAPH_SEPARATOR
            || type == Character.SURROGATE
            || type == Character.PRIVATE_USE;
    }
}
